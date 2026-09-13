# GraalVM native image design

## Status

Approved (in chat), pending spec review.

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

1. **Build stage** — `FROM ghcr.io/graalvm/native-image-community:25i3` (GraalVM CE with `native-image` preinstalled). Runs `./gradlew nativeCompile`, producing `build/native/nativeCompile/receipt-extractor`.
2. **Runtime stage** — `FROM debian:bookworm-slim`. Installs `libtesseract5 liblept5` via `apt-get` (runtime-only shared libraries — no `-dev`/header packages needed, since nothing compiles against them; Tess4J's JNA layer only needs the `.so` files present for `dlopen`). Copies the `tessdata` directory (same as today, since Tesseract's native layer still can't read a path inside a jar/executable) to `/app/tessdata` and sets `OCR_TESSDATA_PATH=/app/tessdata`. Copies the native executable from the build stage. `ENTRYPOINT` runs the executable directly — no JVM, no `java -jar`, in the runtime image at all.

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

**Not yet verified** (same gap as the existing deployment spec, which also never completed its Docker verification step): an actual `docker build .` of the new Dockerfile, and the real `gcloud run deploy --source .` run. Both require a Docker-capable / GCP-authenticated machine, which this sandbox is not. Both must be run before relying on this in production.

## Documentation

- This spec.
- `CLAUDE.md`'s "Deploying to Google Cloud" section updated to describe the new Dockerfile (GraalVM build stage → `debian:bookworm-slim` runtime, native executable entrypoint) and to point at `scripts/regenerate-native-image-config.sh` and when to re-run it.
- `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md` gets a short pointer note at the top: its Dockerfile and Testing sections are superseded by this spec.

## Open questions / follow-ups (explicitly out of scope here)

- **Cloud Build resource adequacy**: native-image compilation is memory-hungry (GraalVM recommends 8GB+; the local spike peaked around 2.3–4GB with 20 threads available). Cloud Build's default machine for `--source .` deploys may not be sufficient, which would show up as a failed or OOM-killed build. By explicit choice, this spec does not preemptively request a bigger Cloud Build machine (e.g. via `--machine-type`/`options.machineType`) — this is discovered empirically at the next real `gcloud run deploy --source .`. If it fails on resources, that's the fix to apply then.
- Tuning `--memory` down for the Cloud Run service now that there's no JVM overhead.
- The pre-existing Tess4J thread-safety/concurrency caveat noted in the deployment spec is unchanged by this switch and still not addressed here.
