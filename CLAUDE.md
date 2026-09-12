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
