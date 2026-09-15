# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This Spring Boot application (from Spring Initializr) now has one real feature implemented: OCR text extraction from receipt images. The project is named "receipt-extractor" (group `com.tung`); further receipt-data-extraction functionality beyond raw OCR text is not yet implemented.

## Commands

Use the Gradle wrapper (`./gradlew`), not a system-installed Gradle.

- Build: `./gradlew build`
- Run the app: `./gradlew bootRun`
- Run all tests: `./gradlew test`
- Run a single test class: `./gradlew test --tests "com.tung.receipt_extractor.ReceiptExtractorApplicationTests"`
- Run a single test method: `./gradlew test --tests "com.tung.receipt_extractor.ReceiptExtractorApplicationTests.contextLoads"`

## Stack and architecture

- Java 25 (via Gradle toolchain), Spring Boot 4.1.1, built with Gradle (Groovy DSL, `build.gradle`).
- Web dependency is `spring-boot-starter-webmvc` (Spring MVC), not the older `spring-boot-starter-web` artifact — keep this naming when adding related starters (e.g. test dependency is `spring-boot-starter-webmvc-test`).
- Tests use JUnit 5 (`useJUnitPlatform()` is configured in `build.gradle`).
- Base package: `com.tung.receipt_extractor`. Main class: `ReceiptExtractorApplication` (`src/main/java/com/tung/receipt_extractor/ReceiptExtractorApplication.java`).
- Configuration lives in `src/main/resources/application.yml`.

## OCR text extraction

- Endpoint: `POST /api/ocr/extract` — multipart file upload, field name `file`, accepts `image/jpeg` or `image/png`. Returns `{"text": "..."}` on success.
- Config properties (in `application.yml`): `ocr.tessdata-path` (default `src/main/resources/tessdata`, a local-dev filesystem path — the deployed container overrides this via the `OCR_TESSDATA_PATH` env var, since Tesseract can't read the `.traineddata` files from inside the jar) and `ocr.languages` (default `vie+eng`).
- Native library prerequisite: this uses Tess4J, which wraps system-installed Tesseract/Leptonica shared libraries via JNA — it does not bundle them. Install the dev packages before building/running/testing:
  - Fedora: `sudo dnf install tesseract-devel leptonica-devel`
  - Debian/Ubuntu: `sudo apt-get install libtesseract-dev libleptonica-dev`
  - If tests fail with `UnsatisfiedLinkError: Unable to load library 'tesseract'`, this is the fix.

## Google Sheets export

- After each successful OCR extraction, `SheetRowAppender.insertRow(amount, message)` writes a row to a Google Sheet, using the user's personal Google OAuth2 credentials. This is best-effort — a Sheets failure never affects the OCR API's response.
- The target sheet (tab) name is computed automatically as `"Tháng " + currentMonthValue` (e.g. `"Tháng 9"` in September) from the same injected `Clock` bean used for date matching — there is no sheet-name config; the spreadsheet must already have a tab named this way for the current month.
- Sheet layout: column A holds pre-populated dates for the month as `d/M` text (e.g. `"1/12"` .. `"31/12"`, no leading zero), one row per date, packed with no blank row in between — this app writes columns D–G (`Description, Payment Method, Income / Outcome, Amount`) and never touches columns B/C (the user may keep other data there, e.g. a category column, unrelated to this app).
- On each write, the app looks up today's date (Asia/Ho_Chi_Minh, via an injected `Clock` bean) in column A, then looks at the row directly below it. A date that hasn't been used yet has no row of its own below it — the very next row is the *next* date's header row — so that case (and the case where there's no row below at all) inserts a fresh row directly under today's date. Once today has at least one entry, the row directly below the date holds it: if that row is blank (a genuine empty spacer, e.g. after the sheet was manually edited), the app writes into it in place; otherwise it inserts a new row directly below the existing entry, so repeated same-day entries stack chronologically under the date. A row is only ever treated as "reserved for another date" when its own column A is non-blank — this is what distinguishes a neighboring date's header from a blank spacer. It always writes `Payment Method = "Chuyển khoản"`, `Income / Outcome = "Chi"`, `Description` = the OCR-derived/caption message, and `Amount` = the detected amount negated (blank if undetected). Bank source is not written to the sheet. If today's date has no row in column A at all, the app first creates one: it finds the closest existing earlier date row (or the first date row, if today is earlier than every existing date) and copies its entire row — format and values, via the Sheets API's copy-paste request — to a newly inserted row in chronological order among the existing dates, then overwrites only that new row's column A with today's date (same `d/M` text). The entry is then inserted as a fresh row directly below this new date row. If the sheet has no date row anywhere to copy as a template, it falls back to appending a plain row (no date) at the end of the sheet instead.
- Requires 4 environment variables holding the OAuth2 credentials and target spreadsheet: `SHEETS_CLIENT_ID`, `SHEETS_CLIENT_SECRET`, `SHEETS_REFRESH_TOKEN`, `SHEETS_SPREADSHEET_ID`. These bind to `sheets.client-id`, `sheets.client-secret`, `sheets.refresh-token`, `sheets.spreadsheet-id` via Spring's relaxed environment-variable binding — no properties file is read. See `docs/superpowers/specs/2026-09-13-google-sheets-export-design.md` ("Obtaining the refresh token") for how to obtain the OAuth values via Google's OAuth Playground.
- If any of these is unset, the whole app fails to start with a placeholder-resolution error — this is expected until they're set; it is not required for running the test suite (tests set dummy values via `@TestPropertySource`).
- Do not enable `com.google.api.client.http` logging at `CONFIG` level or above in production — it logs full HTTP request bodies, which for the token-refresh endpoint includes the client secret and refresh token in plaintext.

## Telegram webhook integration

- Endpoint: `POST /api/telegram-webhook` — receives a Telegram Bot API `Update` JSON payload. The Telegram bot itself (webhook registration, bot token issuance) is configured outside this repo.
- Every request must carry the header `X-Telegram-Bot-Api-Secret-Token` matching the configured `telegram.webhook-secret-token`, or the endpoint returns `401` without parsing or logging the body. This is the value passed as `secret_token` when the webhook was registered via Telegram's `setWebhook` call.
- The raw JSON payload is logged at `info` level for every request that passes the secret-token check — useful for debugging webhook behavior.
- Only messages from the chat id configured in `telegram.allowed-chat-id` are processed; messages from any other chat are silently ignored (`200 OK`, no reply).
- A message must include a photo. The largest resolution is downloaded via Telegram's `getFile`/file-download APIs, OCR'd with the same pipeline as `/api/ocr/extract` (`ReceiptExtractionService`), and appended to the same Google Sheet as `/api/ocr/extract`. If the message has a non-blank caption, the caption overrides the OCR-derived message for that row.
- The bot replies in the chat with a confirmation summary on success, or a short error message if there's no photo or processing fails. The webhook always returns `200 OK` (except the secret-token check, which returns `401`) so Telegram never retry-storms it.
- Config properties (in `application.yml`): `telegram.bot-token`, `telegram.allowed-chat-id`, `telegram.webhook-secret-token`, bound from `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_CHAT_ID`, `TELEGRAM_WEBHOOK_SECRET_TOKEN`. Same as the Sheets properties, the app fails to start if any is unset; not required for running the test suite (tests set dummy values via `@TestPropertySource`).
- Deployment note: Telegram's servers call this webhook directly and cannot present a Google identity token, so they are incompatible with the `--no-allow-unauthenticated` Cloud Run setting described below as currently deployed — reaching this endpoint from Telegram requires that route (or the whole service) be exposed without Google IAM auth. The `X-Telegram-Bot-Api-Secret-Token` check is the compensating access control that stands in for IAM on this path; the current deployment procedure does not yet configure this exposure, so this is a known gap to resolve before relying on the webhook in production.

## Deploying to Google Cloud

- Deploys as a container to Cloud Run via a multi-stage `Dockerfile` checked into the repo root: a GraalVM native-image build stage (`ghcr.io/graalvm/native-image-community`) produces a native executable, which runs directly (no JVM) on an `eclipse-temurin:25-jre` runtime stage — the bundled JRE is unused, but this base was chosen (over a slimmer Debian image) because it's the same runtime base the original JVM-based Dockerfile used successfully with Tesseract/Leptonica, avoiding a repeat of the Debian-packaging-naming issues below. `gcloud run deploy --source .` builds the image with Cloud Build and pushes it to an auto-created Artifact Registry repo — no manual registry setup needed. See `docs/superpowers/specs/2026-09-13-graalvm-native-image-design.md` for the full design.
- native-image compilation in the build stage is memory- and CPU-hungry (8GB+ RAM recommended) and can take much longer than a plain jar build, so Cloud Build's default machine type and 10-minute timeout may not be sufficient. If a deploy fails or times out during the build, the remedy is `--machine-type` (a bigger Cloud Build machine) and/or a longer build timeout on the `gcloud run deploy`/`gcloud builds submit` call.
- Tess4J's JNA-based native bindings need project-specific GraalVM reachability metadata, checked into `src/main/resources/META-INF/native-image/com.tung/receipt-extractor-tess4j/reachability-metadata.json`. This deliberately uses a directory name (`receipt-extractor-tess4j`) distinct from the app's own group:artifact (`com.tung/receipt-extractor`), because Spring's AOT processing (wired into `bootJar` by the native-image plugin) generates its own file at that exact `com.tung/receipt-extractor/reachability-metadata.json` path at build time — using the same path for the checked-in file would make `bootJar`/`build` fail with a Gradle "duplicate entry" error. native-image merges metadata from every `META-INF/native-image/**/reachability-metadata.json` on the classpath regardless of directory name, so this has no effect on native compilation. Re-generate it with `scripts/regenerate-native-image-config.sh` after upgrading the `tess4j`/JNA dependency or changing how `OcrService`/`TesseractConfig` calls into `ITesseract` — see the design spec above for details and the local GraalVM setup this requires.
- Non-ASCII string literals (e.g. the Vietnamese text in `SheetRowAppender`) get corrupted in the native image unless UTF-8 is forced explicitly: `build.gradle` sets `options.encoding = 'UTF-8'` on all `JavaCompile` tasks, and the Dockerfile sets `ENV LANG=C.UTF-8 LC_ALL=C.UTF-8` in both stages. Without this, the build container's default locale can cause `javac`/`native-image` to mis-decode source files, silently baking mojibake (e.g. `"Tháng "` becoming `"ThÃ¡ng "`) into the compiled binary — this surfaces as a Google Sheets API 400 error (an unrecognized/garbled sheet name in the `ranges` parameter), not a build failure, so it's easy to miss until a real request fails in production.
- The service is deployed with `--no-allow-unauthenticated`: only callers with a valid Google identity token and the `roles/run.invoker` role can reach it. This app handles personal financial data (receipt images, bank transfer details) and has no endpoint-level auth of its own.
- Secrets are passed as plain Cloud Run environment variables (not Secret Manager — see the design spec for the trade-off) via `--env-vars-file`. Copy `deploy/sheets-env.yaml.example` to `deploy/sheets-env.yaml` (gitignored) and fill in real values before deploying — this covers both the Sheets and Telegram integration variables.
- One-time setup and the full step-by-step deploy/verify/redeploy procedure: see `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md`.
