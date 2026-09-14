# GraalVM native image design

## Status

**Reverted.** Implemented and merged (#10–#13), but abandoned after three separate native-image-specific runtime crashes surfaced from real-world testing in sequence (missing sibling JDK libraries for AWT/ImageIO, missing JNI reflection metadata for the JPEG codec, and a Debian Leptonica package-naming mismatch) — see the three "Update" notes in the Testing section below for the full history. The Dockerfile was rolled back to the original JVM-based build (`eclipse-temurin`); see `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md`, which is active again. The Gradle plugin, regeneration script, and checked-in reachability metadata are left in the repo unused (not deleted), in case native-image is revisited later — this spec is kept as a record of what was tried and why it didn't pan out, not as a description of current deployment behavior.

## Context

The app currently deploys to Cloud Run as a JVM application: a multi-stage `Dockerfile` builds a Spring Boot fat jar on `eclipse-temurin:25-jdk`, then runs it with `java -jar` on `eclipse-temurin:25-jre` (see `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md`). This spec switches that to a GraalVM native image running on `debian:bookworm-slim`, for faster startup and a smaller/simpler runtime.

The main technical risk going in was Tess4J's JNA-based binding to native Tesseract/Leptonica libraries: JNA relies on runtime reflection and dynamic proxies, which GraalVM native-image cannot discover through static analysis alone, and no prior art was found of anyone running Tess4J under native-image.

**This risk has been resolved by a spike.** Using GraalVM CE 25 (installed locally via SDKMAN, not Docker), the following was verified end-to-end, outside this repo's normal build:

1. Added the `org.graalvm.buildtools.native` Gradle plugin temporarily and built the jar (`./gradlew bootJar`), which triggers Spring's AOT processing.
2. Ran the jar under GraalVM's `-agentlib:native-image-agent`, then sent a real request to `/api/ocr/extract` with the existing test fixture (`src/test/resources/ocr/sample-receipt.png`). This captured the JNA/reflection/JNI configuration Tess4J needs, including `com.sun.jna.*` proxy and callback types.
3. Fed that captured config into `./gradlew nativeCompile`, which succeeded (~1m50s, 94MB executable).
4. Ran the resulting native executable directly (no JVM) and re-sent the same OCR request: it started in **0.137s** and returned the same correctly-extracted Vietnamese receipt text as the JVM run.

All experimental changes (plugin addition, generated config) were discarded after the spike — this spec defines how the real, checked-in version of this config is produced and maintained.

One more thing confirmed during the spike: Spring's AOT processing (`processAot`, a prerequisite of `nativeCompile`) does **not** require `SHEETS_*`/`TELEGRAM_*` env vars to be set — it completed successfully with none of them present. So `docker build` needs no secrets injected at build time; the existing Cloud Run runtime-env-var model (see the Sheets export and deployment specs) is unaffected.

## Goals

- Replace the JVM-based Docker image with a GraalVM native-image build, running on `debian:bookworm-slim`.
- Keep local development (`./gradlew bootRun`, `./gradlew test`) on the regular JDK, unaffected by this change.
- Keep the existing Cloud Run deploy command and secrets model unchanged.

## Non-goals

- Switching the OCR library away from Tess4J.
- Tuning Cloud Run `--memory` or Cloud Build machine resources preemptively — both are left as-is; any issues from native image's different resource profile are discovered and addressed at actual deploy time (see "Open questions / follow-ups").
- Automating deploys (still a manual, human-run `gcloud run deploy --source .`, per the existing deployment spec).
- Any change to OCR/Sheets/Telegram application logic.

## Approach considered and rejected

Spring Boot's buildpacks-based native build (`./gradlew bootBuildImage` with native support, using Paketo buildpacks) was considered as an alternative to a hand-written Dockerfile. Rejected because buildpacks choose their own runtime base image and can't be pinned to `debian:bookworm-slim`, which is an explicit requirement here.

Keeping a second, JVM-based Dockerfile alongside the native one (for fallback) was also considered. Rejected as unnecessary complexity: the spike already validated the risky part (Tess4J under native-image) works, so there's no open feasibility question left to hedge against, and the ask is to switch, not to dual-run.

## Gradle changes

Add one plugin to `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.1'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'org.graalvm.buildtools.native' version '1.1.11'
}
```

This provides the `nativeCompile` task and wires in Spring's AOT processing automatically. No toolchain/vendor pinning is needed — confirmed in the spike, Gradle just uses whichever JDK 25 it's currently running under, and the Docker build stage's base image supplies GraalVM for that. Nothing else in `build.gradle` changes.

`./gradlew bootRun` and `./gradlew test` are unaffected and keep using the regular (non-GraalVM) JDK for local dev — `nativeCompile` is only invoked inside the Docker build stage. (`./gradlew build`/`bootJar` do run Spring's AOT processing as part of the regular JDK build, same as before; see the note on directory naming below for a packaging wrinkle this caused.)

## Reachability metadata (Tess4J/JNA config)

Spring-managed code gets its native-image reflection hints automatically from Spring's own AOT processing. Google API client libraries (used for Sheets) already ship their own reachability metadata via the community `graalvm-reachability-metadata` repository, pulled in automatically by the native-build-tools Gradle plugin — confirmed working in the spike with no extra configuration. Only Tess4J's JNA-based interface (`ITesseract`/`TessAPI`, loaded as a dynamic proxy) needs project-specific config, since that's application-specific and not something a generic library's metadata can cover.

That config is checked in at:

```
src/main/resources/META-INF/native-image/com.tung/receipt-extractor-tess4j/reachability-metadata.json
```

Note this uses `receipt-extractor-tess4j`, not the app's own group:artifact (`com.tung/receipt-extractor`). The spike discarded all its experimental changes afterward, so it never exercised a second `bootJar` run with the config already checked in. Discovered while implementing this spec: Spring's AOT processing (wired into `bootJar` by the native-image plugin) generates its own file at exactly `META-INF/native-image/com.tung/receipt-extractor/reachability-metadata.json` at build time — using that same path for the checked-in file makes `bootJar`/`build` fail with a Gradle "duplicate entry, no duplicate handling strategy" error, since two different source files would land at the same jar entry. Spring's generated file is substantial (hundreds of reflection entries covering logback, the Google API clients, etc.), not something safe to silently drop via a `duplicatesStrategy`, so the fix is a non-colliding directory rather than picking a winner. native-image discovers and merges metadata from every `META-INF/native-image/**/reachability-metadata.json` on the classpath regardless of the group/artifact directory name (confirmed by the dozens of per-library directories the native-build-tools plugin already collects under `build/native-reachability-metadata/`), so this has no effect on what native-image sees at `nativeCompile` time.

A new script, `scripts/regenerate-native-image-config.sh`, automates regenerating it:

1. Build the jar (`./gradlew bootJar`).
2. Run it under GraalVM's tracing agent (`-agentlib:native-image-agent=config-output-dir=...`), using a GraalVM install at a fixed local path (see below) — not the system default JDK.
3. Send a request to `/api/ocr/extract` with `src/test/resources/ocr/sample-receipt.png`, exercising the Tess4J/JNA path.
4. Stop the app and copy the generated `reachability-metadata.json` over the checked-in file.

**When to re-run it:** after upgrading the `tess4j` (or transitive JNA) dependency version, or after changing how `OcrService`/`TesseractConfig` calls into `ITesseract`. This is a manual, occasional step — not run on every build — matching the repo's existing no-CI/CD, manual-deploy approach.

**Local GraalVM install** (for running the script; not needed for anything else in local dev):

```bash
sdk install java 25.3.4+1.r25-graalce
```

Answer "n" when asked to set it as default — the regen script invokes it by its explicit SDKMAN path, so the regular dev JDK default is untouched. (This distinction matters: setting it as default during the spike inadvertently changed the shell's default JDK and had to be reverted.)

## Dockerfile

Multi-stage build, replacing the current JDK/JRE-based one:

1. **Build stage** — `FROM ghcr.io/graalvm/native-image-community:25i3` (GraalVM CE with `native-image` preinstalled). Runs `./gradlew nativeCompile`, producing `build/native/nativeCompile/receipt-extractor` **plus a set of sibling `.so` files** (`libawt.so`, `libawt_headless.so`, `libawt_xawt.so`, `libfontmanager.so`, `libjavajpeg.so`, `liblcms.so`, `libmlib_image.so`, `libjava.so`, `libjvm.so`, `libmanagement_ext.so`) — GraalVM's "jdk_library" build artifacts. `OcrService.detectFormat` uses `javax.imageio.ImageIO` to sniff the uploaded image's format, and native-image ships AWT/ImageIO's native codec support (JPEG decoding, color management, etc.) as these separate shared libraries rather than statically linking them into the main executable, loaded via JNI `System.loadLibrary` at first use — **all of them must ship alongside the executable, not just the executable itself**, or any code path that touches `ImageIO` fails with `UnsatisfiedLinkError: Can't load library: awt` (surfacing as `NoClassDefFoundError: Could not initialize class javax.imageio.ImageIO` on the request thread once that class's init has failed). This was missed initially: local verification (Task 3) ran the executable directly from `build/native/nativeCompile/`, where these files happen to already be sitting right next to it, so the gap only surfaced once the app ran from a directory that had received just the copied executable. Of these, only `libfontmanager.so` pulls in extra system libraries (`libfreetype`, `libharfbuzz`, `libpng16`, etc., for font/text layout) beyond `libc`/`libm`/`libdl` — not needed for this app's headless image-format-detection usage, so no extra `apt-get` packages are required in the runtime stage for this.
2. **Runtime stage** — `FROM debian:bookworm-slim`. Installs `libtesseract5 liblept5` via `apt-get` (runtime-only shared libraries — no `-dev`/header packages needed, since nothing compiles against them; Tess4J's JNA layer only needs the `.so` files present for `dlopen`). Copies the `tessdata` directory (same as today, since Tesseract's native layer still can't read a path inside a jar/executable) to `/app/tessdata` and sets `OCR_TESSDATA_PATH=/app/tessdata`. Copies the *entire* `build/native/nativeCompile/` directory from the build stage (executable and all sibling `.so` files together) to `/app/`, so they stay co-located exactly as GraalVM produced them. `ENTRYPOINT` runs the executable directly — no JVM, no `java -jar`, in the runtime image at all.

`.dockerignore` is unchanged.

## Cloud Run deploy procedure

Unchanged from the existing deployment spec:

```bash
gcloud run deploy receipt-extractor \
  --source . \
  --region <REGION> \
  --no-allow-unauthenticated \
  --memory=1Gi \
  --env-vars-file deploy/sheets-env.yaml
```

`gcloud run deploy --source .` still builds via Cloud Build (not a local `docker build`/`push`), by explicit choice — see "Open questions / follow-ups" for the resource risk this carries. `--memory=1Gi` (the Cloud Run service's runtime memory) is left unchanged rather than preemptively tuned down for native image's likely-lower footprint; that's a possible future optimization, out of scope here.

## Testing

No new automated unit tests — this is build/infrastructure tooling, not application logic (same stance as the existing deployment spec).

The highest-risk part is already verified, outside Docker: the spike (see "Context" above) proved GraalVM native-image + Tess4J/JNA + a real Tesseract OCR call works end-to-end with correct output.

**Not yet verified** (same gap as the existing deployment spec, which also never completed its Docker verification step): an actual `docker build .` of the new Dockerfile; a container-run OCR request against the resulting image; a real (non-dummy-credentialed) Sheets append; a real Telegram photo message end-to-end; and the real `gcloud run deploy --source .` run. These require a Docker-capable / GCP-authenticated machine, which this sandbox is not. All of these must be run before relying on this in production.

**Update:** a real Telegram photo message was tried against a real Docker build of this image and hit `NoClassDefFoundError: Could not initialize class javax.imageio.ImageIO` (root cause: the Dockerfile only copied the native executable, not the sibling `.so` "jdk_library" files GraalVM produces alongside it — see the "Dockerfile" section above). Fixed by copying the whole `build/native/nativeCompile/` directory instead of just the executable; reproduced and confirmed fixed by isolating the executable with and without its sibling libraries outside Docker (this sandbox still has no Docker).

**Update 2:** after that fix, the same real-Telegram-photo test hit a second, more specific error: `NoClassDefFoundError: Could not initialize class com.sun.imageio.plugins.jpeg.JPEGImageReader`, with the underlying cause (reproduced locally with a real JPEG file) `NoSuchMethodError: com.sun.imageio.plugins.jpeg.JPEGImageReader.readInputData([BII)I` thrown from `JPEGImageReader.initReaderIDs` — a native method the JPEG codec's static initializer calls to cache JNI method IDs for native-to-Java callbacks. GraalVM needs these specific callback methods registered as JNI-accessible at build time, and the checked-in reachability metadata never captured them because the tracing script only ever exercised a PNG fixture — Telegram always sends photos as JPEG, so this codec path had never actually run under native-image before. Fixed by adding a JPEG test fixture (`src/test/resources/ocr/sample-receipt.jpg`) and having `scripts/regenerate-native-image-config.sh` exercise `/api/ocr/extract` with both PNG and JPEG during tracing, then regenerating and re-checking-in the metadata. Reproduced and confirmed fixed the same way as Update 1 (isolated executable + sibling libraries, real JPEG file, outside Docker).

**Update 3:** a third error surfaced the same way: `java.io.IOException: Native library (linux-x86-64/libleptonica.so) not found in resource path ()`, thrown from JNA when `net.sourceforge.lept4j.Leptonica1`'s static initializer calls `Native.register("leptonica")` — a Tess4J transitive dependency (`lept4j`) used for box/region iteration, unrelated to the plain-text OCR path our own code calls, but apparently still reachable/initialized under native-image. Root cause, confirmed against Debian's own package file listings and reproduced in an isolated namespace (bubblewrap) that hides the host's real Leptonica library and exposes only Debian-style file names: Leptonica's Debian packaging has always used the `lept` library basename (`liblept.so.5`, from package `liblept5`), never `leptonica` — a longstanding upstream/Debian naming quirk, independent of native-image. JNA's friendly-name loading (`LIB_NAME_NON_WIN = "leptonica"` in `lept4j`'s `LoadLibs`) resolves to `libleptonica.so`, which does not exist in any form (versioned or not) on Debian — only the `libleptonica-dev` package provides that name, as a symlink to `liblept.so.5`, for compilation convenience. This is exactly why the earlier switch away from `-dev` packages (see the Dockerfile section above) exposed it: the original JVM-based Dockerfile happened to use `-dev` packages and never hit this. Fixed with one added line in the runtime stage: `ln -s liblept.so.5 /usr/lib/x86_64-linux-gnu/libleptonica.so`, rather than reinstating the `-dev` package. Verified by reproducing the exact reported error (byte-for-byte) in a bubblewrap sandbox with the host's real Leptonica hidden and only a `liblept.so.5`-named file present, then confirming the symlink resolves it.

A real `docker build`/`run` and a real end-to-end Telegram photo message still haven't confirmed any of these three fixes in the actual container/production path — all remain open per the "Not yet verified" list above. Given three distinct native-image/Debian-packaging gaps have now surfaced from real-world exercise of this deployment, that verification is not just a formality — treat it as required, not optional, before the next production deploy.

## Documentation

- This spec.
- `CLAUDE.md`'s "Deploying to Google Cloud" section updated to describe the new Dockerfile (GraalVM build stage → `debian:bookworm-slim` runtime, native executable entrypoint) and to point at `scripts/regenerate-native-image-config.sh` and when to re-run it.
- `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md` gets a short pointer note at the top: its Dockerfile and Testing sections are superseded by this spec.

## Open questions / follow-ups (explicitly out of scope here)

- **Cloud Build resource adequacy**: native-image compilation is memory-hungry (GraalVM recommends 8GB+; the local spike peaked around 2.3–4GB with 20 threads available). Cloud Build's default machine for `--source .` deploys may not be sufficient, which would show up as a failed or OOM-killed build. By explicit choice, this spec does not preemptively request a bigger Cloud Build machine (e.g. via `--machine-type`/`options.machineType`) — this is discovered empirically at the next real `gcloud run deploy --source .`. If it fails on resources, that's the fix to apply then.
- Tuning `--memory` down for the Cloud Run service now that there's no JVM overhead.
- The pre-existing Tess4J thread-safety/concurrency caveat noted in the deployment spec is unchanged by this switch and still not addressed here.
