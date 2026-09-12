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
- Configuration lives in `src/main/resources/application.properties`.

## OCR text extraction

- Endpoint: `POST /api/ocr/extract` — multipart file upload, field name `file`, accepts `image/jpeg` or `image/png`. Returns `{"text": "..."}` on success.
- Config properties (in `application.properties`): `ocr.tessdata-path` (default `src/main/resources/tessdata`) and `ocr.languages` (default `vie+eng`).
- Native library prerequisite: this uses Tess4J, which wraps system-installed Tesseract/Leptonica shared libraries via JNA — it does not bundle them. Install the dev packages before building/running/testing:
  - Fedora: `sudo dnf install tesseract-devel leptonica-devel`
  - Debian/Ubuntu: `sudo apt-get install libtesseract-dev libleptonica-dev`
  - If tests fail with `UnsatisfiedLinkError: Unable to load library 'tesseract'`, this is the fix.

## Google Sheets export

- After each successful OCR extraction, the app appends a row (`Bank source, Amount, Message, Timestamp`) to a configured Google Sheet, using the user's personal Google OAuth2 credentials. This is best-effort — a Sheets failure never affects the OCR API's response.
- Requires a gitignored credentials file at `config/sheets-credentials.properties` (path configurable via `sheets.credentials-path` in `application.properties`), holding six keys: `client-id`, `client-secret`, `refresh-token`, `access-token`, `spreadsheet-id`, `sheet-name`. See `config/sheets-credentials.properties.example` for the required format, and `docs/superpowers/specs/2026-09-13-google-sheets-export-design.md` ("Obtaining the refresh token") for how to obtain these values via Google's OAuth Playground.
- If this file is missing, the whole app fails to start with `NoSuchFileException` at `config/sheets-credentials.properties` — this is expected until the file is created; it is not required for running the test suite (tests use a checked-in dummy fixture).
- Do not enable `com.google.api.client.http` logging at `CONFIG` level or above in production — it logs full HTTP request bodies, which for the token-refresh endpoint includes the client secret and refresh token in plaintext.
