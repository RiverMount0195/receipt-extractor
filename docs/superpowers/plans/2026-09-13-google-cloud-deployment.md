# Google Cloud Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Dockerfile, port-binding config, and documentation needed to deploy receipt-extractor to Cloud Run, so a human operator can run the deploy procedure themselves.

**Architecture:** A multi-stage Dockerfile (JDK 25 build stage → JRE 25 + Tesseract/Leptonica runtime stage) builds the existing Spring Boot fat jar into a container image. Spring Boot's `server.port` is bound to Cloud Run's injected `PORT` env var via a one-line `application.yml` change. `CLAUDE.md` documents the one-time GCP setup and the `gcloud run deploy --source .` command that builds, pushes, and deploys the image in one step. No application logic changes.

**Tech Stack:** Spring Boot 4.1.1 / Gradle (existing), Docker (new: multi-stage build), `eclipse-temurin:25-jdk` / `eclipse-temurin:25-jre` base images (confirmed published on Docker Hub).

**Spec:** `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md`

## Global Constraints

- No application/business logic changes — this plan only adds deployment infrastructure and one config line.
- The actual `gcloud run deploy` execution against a real GCP project is a manual, human-run action — it is documented, not scripted or executed by any task in this plan.
- Base images: `eclipse-temurin:25-jdk` (build stage) and `eclipse-temurin:25-jre` (runtime stage) — both confirmed available on Docker Hub as of this plan's writing.
- Native library packages in the runtime image: `libtesseract-dev` and `libleptonica-dev` (same packages CLAUDE.md already documents for local dev — reused for consistency, guarantees the `.so` files Tess4J's JNA binding needs at runtime).
- The jar produced by `./gradlew bootJar` is named `receipt-extractor-0.0.1-SNAPSHOT.jar` (from `rootProject.name = 'receipt-extractor'` + `version = '0.0.1-SNAPSHOT'` in `build.gradle`) — Dockerfile COPY commands must not hardcode this since the version will change; use a glob.
- Docker is not installed in this dev sandbox — Task 2's Dockerfile is verified by structural review and `./gradlew bootJar` (already proven to work), not by an actual `docker build`/`docker run`. Note this explicitly when reporting Task 2 complete; do not claim the image was built and run if it wasn't.

---

### Task 1: Bind Spring Boot's server.port to Cloud Run's PORT env var

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/tung/receipt_extractor/ServerPortConfigurationTest.java`
- Create: `src/test/java/com/tung/receipt_extractor/ServerPortEnvVarOverrideTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing other tasks depend on directly — `application.yml` gets one new top-level `server:` block that Task 2's container image relies on being present (Cloud Run injects `PORT`; without this the deployed container won't accept traffic), but no task in this plan asserts against it directly.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/tung/receipt_extractor/ServerPortConfigurationTest.java`:

```java
package com.tung.receipt_extractor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test"
})
class ServerPortConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    void defaultsToPort8080WhenPortEnvVarUnset() {
        assertEquals("8080", environment.getProperty("server.port"));
    }
}
```

Create `src/test/java/com/tung/receipt_extractor/ServerPortEnvVarOverrideTest.java`:

```java
package com.tung.receipt_extractor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "PORT=9090",
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test"
})
class ServerPortEnvVarOverrideTest {

    @Autowired
    private Environment environment;

    @Test
    void usesPortEnvVarWhenSet() {
        assertEquals("9090", environment.getProperty("server.port"));
    }
}
```

Both tests set the 5 `sheets.*` properties (same dummy values used in `ReceiptExtractorApplicationTests`) because `@SpringBootTest` always builds the full application context — including `SheetsConfig`'s beans — regardless of `webEnvironment`, and those beans fail fast if the Sheets properties are unresolved.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ServerPortConfigurationTest" --tests "com.tung.receipt_extractor.ServerPortEnvVarOverrideTest"`

Expected: both FAIL with `AssertionFailedError: expected: <8080> but was: <null>` and `expected: <9090> but was: <null>` respectively — there is currently no `server.port` property at all, so `environment.getProperty("server.port")` returns `null`.

- [ ] **Step 3: Add the server.port binding**

Edit `src/main/resources/application.yml`, adding a `server:` block (placement relative to the other top-level keys doesn't matter — add it after `spring:`):

```yaml
server:
  port: ${PORT:8080}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ServerPortConfigurationTest" --tests "com.tung.receipt_extractor.ServerPortEnvVarOverrideTest"`

Expected: PASS. Then run the full suite to confirm no regression: `./gradlew test`. Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml \
        src/test/java/com/tung/receipt_extractor/ServerPortConfigurationTest.java \
        src/test/java/com/tung/receipt_extractor/ServerPortEnvVarOverrideTest.java
git commit -m "Bind server.port to Cloud Run's PORT env var"
```

---

### Task 2: Add Dockerfile and .dockerignore for the Cloud Run container image

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

**Interfaces:**
- Consumes: `application.yml`'s `server.port` binding from Task 1 (the built image is only correct for Cloud Run once that change exists, though this task doesn't test that interaction directly — end-to-end port behavior is verified manually during actual deployment, per the spec's non-goals).
- Produces: nothing other tasks in this plan depend on.

- [ ] **Step 1: Create the Dockerfile**

Create `Dockerfile` at the repo root:

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends libtesseract-dev libleptonica-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Create .dockerignore**

Create `.dockerignore` at the repo root:

```
build/
.gradle/
.git/
.claude/
config/
```

- [ ] **Step 3: Verify what can be verified in this environment**

Docker is not installed in this dev sandbox (confirmed: `which docker` finds nothing), so `docker build`/`docker run` cannot be exercised here. Verify instead:

Run: `./gradlew bootJar` (mirrors the Dockerfile's build-stage command)
Expected: `BUILD SUCCESSFUL`, and `build/libs/receipt-extractor-0.0.1-SNAPSHOT.jar` exists — this confirms the command the Dockerfile's build stage runs actually produces the jar the runtime stage's `COPY --from=build /app/build/libs/*.jar app.jar` expects.

Then manually review the Dockerfile against this checklist (all should be true):
- Base image tags `eclipse-temurin:25-jdk` and `eclipse-temurin:25-jre` are spelled exactly as confirmed available on Docker Hub.
- The build stage copies exactly the files `./gradlew bootJar` needs: `gradlew`, `gradle/` (wrapper jar + properties), `build.gradle`, `settings.gradle`, `src/` — nothing else, since `.dockerignore` will exclude `build/`, `.gradle/`, `.git/`, `.claude/`, `config/` from the build context regardless.
- The runtime stage's `COPY --from=build` glob (`*.jar`) doesn't hardcode the version string `0.0.1-SNAPSHOT`.
- `ENTRYPOINT` uses exec form (`["java", "-jar", "app.jar"]`), not shell form, so the JVM receives `SIGTERM` directly on container stop.

Do not claim in any report that the image was built or run — only that `./gradlew bootJar` succeeded and the manual review checklist passed. The first real build/run happens either on a machine with Docker installed, or automatically inside Cloud Build when the user runs the actual `gcloud run deploy --source .` command from the spec — both outside this plan's scope.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "Add Dockerfile and .dockerignore for Cloud Run deployment"
```

---

### Task 3: Add a template for the local deploy secrets file

**Files:**
- Create: `deploy/sheets-env.yaml.example`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: the 5 env var names from Task 1's constraints and the existing `SheetsConfig` (`SHEETS_CLIENT_ID`, `SHEETS_CLIENT_SECRET`, `SHEETS_REFRESH_TOKEN`, `SHEETS_SPREADSHEET_ID`, `SHEETS_SHEET_NAME` — already defined and in use on `main`, not introduced by this plan).
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Create the example template**

Create `deploy/sheets-env.yaml.example`:

```yaml
SHEETS_CLIENT_ID: "your-oauth-client-id"
SHEETS_CLIENT_SECRET: "your-oauth-client-secret"
SHEETS_REFRESH_TOKEN: "your-oauth-refresh-token"
SHEETS_SPREADSHEET_ID: "your-google-sheet-id"
SHEETS_SHEET_NAME: "your-google-sheet-tab-name"
```

This mirrors the format `gcloud run deploy --env-vars-file` expects (one `KEY: value` pair per line, YAML) and the naming/style of the now-removed `config/sheets-credentials.properties.example`.

- [ ] **Step 2: Gitignore the real file**

Edit `.gitignore`, adding a new section after the existing `### Secrets ###` block. Leave that block's `config/*` rule untouched — the Sheets credentials now come from env vars rather than a file, but a developer may still have a local, untracked `config/sheets-credentials.properties` left over from before that change, and removing the ignore rule would make it eligible for accidental commit:

```
### Deploy secrets ###
deploy/sheets-env.yaml
```

- [ ] **Step 3: Verify**

Run: `git check-ignore -v deploy/sheets-env.yaml`
Expected: prints a match against the `.gitignore` line just added, confirming a real file at that path would not be trackable.

Run: `git status --porcelain deploy/`
Expected: shows only `deploy/sheets-env.yaml.example` as untracked (to be added in the next step) — no other files.

- [ ] **Step 4: Commit**

```bash
git add deploy/sheets-env.yaml.example .gitignore
git commit -m "Add deploy secrets template for Cloud Run env vars"
```

---

### Task 4: Document the Google Cloud deployment procedure in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: the Dockerfile from Task 2, the `deploy/sheets-env.yaml.example` template from Task 3, and the spec's step-by-step procedure.
- Produces: nothing other tasks depend on — this is the last task in the plan.

- [ ] **Step 1: Add the deployment section**

Edit `CLAUDE.md`, adding a new section after "Google Sheets export" (at the end of the file):

```markdown

## Deploying to Google Cloud

- Deploys as a container to Cloud Run via a multi-stage `Dockerfile` (JDK 25 build stage, JRE 25 + Tesseract/Leptonica runtime stage) checked into the repo root. `gcloud run deploy --source .` builds the image with Cloud Build and pushes it to an auto-created Artifact Registry repo — no manual registry setup needed.
- The service is deployed with `--no-allow-unauthenticated`: only callers with a valid Google identity token and the `roles/run.invoker` role can reach it. This app handles personal financial data (receipt images, bank transfer details) and has no endpoint-level auth of its own.
- Secrets are passed as plain Cloud Run environment variables (not Secret Manager — see the design spec for the trade-off) via `--env-vars-file`. Copy `deploy/sheets-env.yaml.example` to `deploy/sheets-env.yaml` (gitignored) and fill in real values before deploying.
- One-time setup and the full step-by-step deploy/verify/redeploy procedure: see `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md`.
```

- [ ] **Step 2: Verify**

Run: `grep -n "Deploying to Google Cloud" CLAUDE.md`
Expected: one match, confirming the section was added.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "Document Google Cloud deployment procedure"
```
