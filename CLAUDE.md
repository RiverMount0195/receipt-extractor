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
- Config properties (in `application.yml`): `ocr.tessdata-path` (default `src/main/resources/tessdata`) and `ocr.languages` (default `vie+eng`).
- Native library prerequisite: this uses Tess4J, which wraps system-installed Tesseract/Leptonica shared libraries via JNA — it does not bundle them. Install the dev packages before building/running/testing:
  - Fedora: `sudo dnf install tesseract-devel leptonica-devel`
  - Debian/Ubuntu: `sudo apt-get install libtesseract-dev libleptonica-dev`
  - If tests fail with `UnsatisfiedLinkError: Unable to load library 'tesseract'`, this is the fix.

## Google Sheets export

- After each successful OCR extraction, the app appends a row (`Bank source, Amount, Message, Timestamp`) to a configured Google Sheet, using the user's personal Google OAuth2 credentials. This is best-effort — a Sheets failure never affects the OCR API's response.
- Requires 5 environment variables holding the OAuth2 credentials and target sheet: `SHEETS_CLIENT_ID`, `SHEETS_CLIENT_SECRET`, `SHEETS_REFRESH_TOKEN`, `SHEETS_SPREADSHEET_ID`, `SHEETS_SHEET_NAME`. These bind to `sheets.client-id`, `sheets.client-secret`, `sheets.refresh-token`, `sheets.spreadsheet-id`, `sheets.sheet-name` via Spring's relaxed environment-variable binding — no properties file is read. See `docs/superpowers/specs/2026-09-13-google-sheets-export-design.md` ("Obtaining the refresh token") for how to obtain the OAuth values via Google's OAuth Playground.
- If any of these is unset, the whole app fails to start with a placeholder-resolution error — this is expected until they're set; it is not required for running the test suite (tests set dummy values via `@TestPropertySource`).
- Do not enable `com.google.api.client.http` logging at `CONFIG` level or above in production — it logs full HTTP request bodies, which for the token-refresh endpoint includes the client secret and refresh token in plaintext.
