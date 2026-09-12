# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This is a freshly scaffolded Spring Boot application (from Spring Initializr) with no business logic yet — just the default application entry point and a context-loads test. The project is named "receipt-extractor" (group `com.tung`), implying its purpose will be extracting data from receipts, but that functionality has not been implemented.

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
