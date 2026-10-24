# Google Cloud deployment design

## Status

Approved (in chat), pending spec review.

**Note:** the image is a plain JVM image (a `bootJar` run with `java -jar`); the earlier GraalVM native-image variant and the Tesseract OCR dependency have been removed in favor of Gemini via Spring AI (see `docs/superpowers/specs/2026-10-24-spring-ai-gemini-extraction-design.md`).

## Context

The receipt-extractor app currently only runs locally via `./gradlew bootRun`. There is no Dockerfile, container image, or cloud deployment configuration anywhere in the repo. This spec covers deploying it to Google Cloud for the first time.

Two prerequisites already in place:
- Receipt extraction calls Google's Gemini API (Spring AI), so the container needs no native libraries or bundled model files — only the `GOOGLE_API_KEY` environment variable.
- Google Sheets credentials are read from 4 environment variables (`SHEETS_CLIENT_ID`, `SHEETS_CLIENT_SECRET`, `SHEETS_REFRESH_TOKEN`, `SHEETS_SPREADSHEET_ID`) rather than a file, as of the preceding change in this repo — this determines how secrets are wired into the cloud environment (see "Secrets" below).

## Goals

- Deploy the app as a container to Cloud Run, reachable over HTTPS.
- Support redeploying updated code with a single repeatable command.
- Keep the extraction endpoint restricted to authenticated callers only (this handles personal financial data — receipt images and bank transfer details — and the app has no endpoint-level auth of its own).

## Non-goals

- CI/CD automation (auto-deploy on git push). This is a manual, human-run deployment for now; automating it is a separate future task.
- Secret Manager integration. Secrets are passed as plain Cloud Run environment variables per an explicit choice made during design — see "Secrets" below for the trade-off.
- Any change to extraction/Sheets application logic.

## Architecture

Single Cloud Run service running a container built from a Dockerfile checked into the repo. `gcloud run deploy --source .` builds the image via Cloud Build and pushes it to an Artifact Registry repository that Cloud Build creates automatically on first use — no manual registry setup is needed.

The service is deployed with `--no-allow-unauthenticated`, so Cloud Run's IAM layer rejects any request without a valid Google-issued identity token for a principal granted the `roles/run.invoker` role on the service. The deploying user is granted this automatically as the service creator.

## Dockerfile

Multi-stage build:

1. **Build stage** — a JDK 25 image (e.g. `eclipse-temurin:25-jdk`; confirm the exact published tag at implementation time, since Java 25 is newly released) runs `./gradlew bootJar` to produce the executable jar. Gradle's dependency cache is not persisted between builds (Cloud Build gives each build a clean environment); this is acceptable for a manually-triggered deploy and not worth optimizing for now. `bootJar` does not depend on `test`, so the build stage does not run the test suite — no tests gate a Cloud Run deploy. This is a deliberate choice for fast builds (this is a manual, human-run deploy per the Goals/Non-goals above, not a CI pipeline), not an oversight; `./gradlew test` is still run separately as part of normal development.
2. **Runtime stage** — a matching JRE 25 image (`eclipse-temurin:25-jre`). No native libraries are needed. Copies the jar from the build stage and runs it with `java -jar`.

A `.dockerignore` file excludes `build/`, `.gradle/`, `.git/`, and `config/` from the build context sent to Cloud Build.

## Required code change: Cloud Run port binding

Cloud Run injects a `PORT` environment variable (default `8080`) that the container must listen on. Spring Boot's `server.port` property is not populated from a `PORT` env var automatically — only from `SERVER_PORT`, which Cloud Run does not set. Without an explicit binding, the deployed container would not accept traffic on the port Cloud Run routes to it.

`application.yml` gets one added line:

```yaml
server:
  port: ${PORT:8080}
```

This defaults to `8080` for local runs (where `PORT` is normally unset) and picks up Cloud Run's injected value in the deployed environment. This is the only application code/config change item 3 requires.

## Secrets

The Sheets, Telegram, and `GOOGLE_API_KEY` secrets are passed to Cloud Run as plain environment variables via `--env-vars-file <local-file>` at deploy time, rather than being stored in Secret Manager. This was an explicit choice during design: Secret Manager is the more secure option (values never appear in plaintext in the Cloud Run revision config), but plain env vars are simpler to set up and were preferred for this personal-use deployment. The trade-off: anyone with read access to the Cloud Run service's configuration (e.g. via `gcloud run services describe` or the console) can see the secret values in plaintext. Revisiting this later to move to Secret Manager is a small, self-contained follow-up if the access-control needs of the project change.

The env-vars file itself is local and gitignored — it is never committed, and its values are never passed as literal `--set-env-vars` command-line arguments (which would leak into shell history).

## Step-by-step deployment procedure

**One-time setup** (run once per GCP project):

```bash
gcloud auth login
gcloud config set project <PROJECT_ID>
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com
```

**Deploy** (run every time you want to ship the current code):

1. Create a local, gitignored env-vars file (format: one `KEY: value` pair per line, YAML), e.g. `deploy/sheets-env.yaml`:
   ```yaml
   SHEETS_CLIENT_ID: "..."
   SHEETS_CLIENT_SECRET: "..."
   SHEETS_REFRESH_TOKEN: "..."
   SHEETS_SPREADSHEET_ID: "..."
   TELEGRAM_BOT_TOKEN: "..."
   TELEGRAM_ALLOWED_CHAT_ID: "..."
   TELEGRAM_WEBHOOK_SECRET_TOKEN: "..."
   GOOGLE_API_KEY: "..."
   ```
2. Deploy:
   ```bash
   gcloud run deploy receipt-extractor \
     --source . \
     --region <REGION> \
     --no-allow-unauthenticated \
     --memory=1Gi \
     --env-vars-file deploy/sheets-env.yaml
   ```
   This builds the image from the Dockerfile via Cloud Build, pushes it to Artifact Registry, and creates a new Cloud Run revision, printing the service URL on success. `--memory=1Gi` raises the container's memory above Cloud Run's 512 MiB default, which is a safer margin for a JVM handling receipt image uploads.

   Note: `--source .` uses `.gcloudignore`, not `.dockerignore`, to decide what's sent to Cloud Build. If no `.gcloudignore` exists yet, `gcloud` auto-generates one on this first deploy (which will show up as a new untracked file in the repo) — its default correctly excludes secrets via `#!include:.gitignore`, but don't be surprised to see it appear.

**Verify:**

```bash
curl -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  <SERVICE_URL>/api/ocr/extract -F file=@receipt.jpg
```

A request without the `Authorization` header should receive an HTTP 403 from Cloud Run's IAM layer (proving the service is not publicly reachable).

**Redeploy:** rerun the same `gcloud run deploy` command from step 2 any time the code changes. Cloud Run creates a new revision and shifts traffic to it.

**Grant access to another caller**, if ever needed:

```bash
gcloud run services add-iam-policy-binding receipt-extractor \
  --region <REGION> \
  --member="user:someone@example.com" \
  --role="roles/run.invoker"
```

## Testing

Deployment is infrastructure, not application logic, so no new automated tests are added. Verification instead happens by:
1. Building the Dockerfile locally (`docker build .`) and running the resulting image (`docker run`), confirming the app starts and the extraction endpoint responds to a local `curl`, before ever pushing to Cloud Run.
2. The manual `curl` verification step in the deployment procedure above, once actually deployed.

**Status: step 1 has NOT been performed.** Docker is not installed in the sandbox this branch was developed in, so no `docker build`/`docker run`/`curl` cycle has actually been run against the built image — the Dockerfile was verified only by structural review (see the Dockerfile section above) and by `./gradlew bootJar`/`./gradlew test` succeeding. This step MUST be run on a Docker-capable machine before the first real `gcloud run deploy`. A `docker run` + `curl` against `/api/ocr/extract` that returns extracted receipt data (not a 500) is the real signal that the container works.

## Documentation

`CLAUDE.md` gets a new "Deploying to Google Cloud" section summarizing the one-time setup and the deploy/redeploy command, cross-referencing this spec for the full procedure.

## Open questions / follow-ups (explicitly out of scope here)

- CI/CD automation on push.
- Moving secrets to Secret Manager.
