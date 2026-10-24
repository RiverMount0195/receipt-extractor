# Spring AI + Gemini receipt extraction — design

## Goal

Replace the Tesseract OCR + regex-detector pipeline with a Gemini vision model called through Spring AI. The model reads the receipt image directly and returns structured JSON, which feeds the existing Google Sheets export and Telegram reply. Drop GraalVM native-image support and return to a plain JVM deployment.

## Decisions

- **Provider:** Google Gemini via Spring AI's Google GenAI starter (`spring-ai-starter-model-google-genai`), authenticated with an AI Studio API key (`GOOGLE_API_KEY`).
- **Spring AI version:** 2.0.x (GA 2026-06-12), which targets Spring Boot 4.0/4.1. The implementation plan must verify it resolves and starts against Boot 4.1.1 before anything else.
- **Tesseract is removed entirely**, with no fallback. If Gemini fails, the request fails.
- **JSON fields:** the three the app already uses — `bankSource`, `amount`, `message`.
- **No native image.** The build and deployment go back to a normal Spring Boot jar on a JVM.

## Architecture

```
Telegram photo ─► TelegramWebhookController ─► ReceiptExtractionService ─► ChatClient (Gemini)
                                                        │
                                                        ▼
                                                  ReceiptData
                                                        │
                                   ┌────────────────────┴───────────────┐
                                   ▼                                    ▼
                          SheetRowAppender.insertRow           Telegram confirmation reply
```

`POST /api/ocr/extract` uses the same `ReceiptExtractionService`.

### Components

- **`ReceiptData`** — `record ReceiptData(String bankSource, Long amount, String message)`. Replaces `OcrResponse`. `amount` is a positive integer in VND, `null` when not found. `bankSource` and `message` are `null` or blank when not found.
- **`ReceiptExtractionService`** — `ReceiptData extract(byte[] imageBytes, String mimeType)`. Builds a `ChatClient` call with a system prompt and the image as a `Media` attachment, then `.entity(ReceiptData.class)`. Spring AI generates the JSON-schema instruction from the record and parses the reply.
- **System prompt** — tells the model it reads Vietnamese bank-transfer receipts and returns only the three fields. `amount` is an integer in VND with no separators or currency symbol. `bankSource` is the sending bank's name. `message` is the transfer description or note, copied as written. Unknown fields are `null`, never guessed.
- **`ChatClient` bean** — built from `ChatClient.Builder` in a small config class. Model and temperature are set in `application.yml`. Temperature is low (0–0.2) for stable extraction.
- **Package:** the classes move from `com.tung.receipt_extractor.ocr` to `com.tung.receipt_extractor.receipt`, since there is no OCR any more. The endpoint path `/api/ocr/extract` stays unchanged for existing callers.

### Callers

- **`TelegramWebhookController`** — logic unchanged. It passes `image/jpeg` as the mime type (Telegram re-encodes photos as JPEG), and the caption still overrides `message`.
- **`OcrController`** — passes `file.getContentType()` (jpeg/png as today). The response body becomes `ReceiptData`, so the `text` field is dropped because there is no raw OCR text any more. Sheets failure remains best-effort. The `TesseractException` catch becomes a catch of Spring AI/runtime failures that returns the same 500 `{"error": ...}` shape.

## Configuration

```yaml
spring:
  ai:
    google:
      genai:
        api-key: ${GOOGLE_API_KEY}
        chat:
          options:
            model: <current Gemini Flash model id, confirmed at plan time>
            temperature: 0.1
```

`GOOGLE_API_KEY` is added to `deploy/sheets-env.yaml.example` and to the deployment docs. As with the other secrets, the app fails to start when it is unset; tests set a dummy value via `@TestPropertySource`. The `ocr.*` properties are removed.

## Error handling

- Gemini error, timeout, quota, or an unparseable JSON reply: `ReceiptExtractionService` lets the exception propagate.
- Telegram path: the existing catch-all replies "Sorry, couldn't process that image" and still returns `200 OK`.
- REST path: returns 500 with `{"error": "failed to extract data from image"}`.
- A reply that parses but has all-null fields is not an error; the row is written with a blank amount and message, as it is today when detection finds nothing.

## Removals

- Dependency `net.sourceforge.tess4j:tess4j`; `OcrService`, `TesseractConfig`, `BankSourceDetector`, `AmountDetector`, `MessageDetector` and their tests; `src/main/resources/tessdata`; `OcrEndToEndTest` (Tesseract-based).
- Native image: the `org.graalvm.buildtools.native` plugin, `src/main/resources/META-INF/native-image/`, `scripts/regenerate-native-image-config.sh`, and the `@RegisterReflectionForBinding` annotations on `OcrController`/`TelegramWebhookController`. `options.encoding = 'UTF-8'` in `build.gradle` stays.
- Older specs and plans under `docs/superpowers/` stay as history. The CLAUDE.md sections on OCR, native image, and the Tesseract prerequisites are rewritten.

## Dockerfile

Two-stage JVM build:

1. Build stage on `eclipse-temurin:25-jdk` with `ENV LANG=C.UTF-8 LC_ALL=C.UTF-8`, running `./gradlew bootJar --no-daemon`.
2. Runtime stage on `eclipse-temurin:25-jre` with the same `LANG`/`LC_ALL`, with the jar copied in and `ENTRYPOINT ["java","-jar","/app/app.jar"]`. No `apt-get` packages are needed.

The Cloud Run deployment procedure no longer needs a larger Cloud Build machine or a longer timeout for native compilation. The deployment spec is updated to say so and to add `GOOGLE_API_KEY`.

## Testing

- **Unit:** `ReceiptExtractionService` with a mocked `ChatClient` — maps a fixed JSON reply to `ReceiptData`, passes the image and mime type, and propagates failures.
- **Controller/webhook:** `OcrControllerTest` and `TelegramWebhookControllerTest` are updated to the new service signature and `ReceiptData`.
- **Context:** `ReceiptExtractorApplicationTests` and the config tests get a dummy `spring.ai.google.genai.api-key`.
- **Not in CI:** no real Gemini calls. The plan includes one manual check with a real key and a sample receipt image, to confirm that Vietnamese text and the amount parse correctly.

## Out of scope

- Extra fields (transaction date, recipient, direction), a Tesseract fallback, Vertex AI/ADC authentication, and caching or retry logic.

## Privacy note

Receipt images contain personal financial data and are now sent to Google's Gemini API. This is a deliberate change from fully local OCR. The Cloud Run `--no-allow-unauthenticated` posture is unaffected.
