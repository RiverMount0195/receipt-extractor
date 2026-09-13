# Telegram Webhook Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `POST /api/telegram-webhook` endpoint that receives a Telegram bot update, OCRs a receipt photo (optionally overridden by the message's caption), appends the result to the existing Google Sheet, and replies to the user in the chat.

**Architecture:** Extract the OCR-to-detections logic that today lives inline in `OcrController` into a reusable `ReceiptExtractionService`. Add a new `com.tung.receipt_extractor.telegram` package with JSON DTOs for Telegram's `Update` payload, a `TelegramClient` wrapping Telegram's Bot API (fetch photo, send message) via Spring's `RestClient`, and a `TelegramWebhookController` that orchestrates: secret-token check → parse → guard clauses → download photo → `ReceiptExtractionService.extract` → caption override → `SheetRowAppender.appendRow` → reply.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC via `spring-boot-starter-webmvc`), Jackson 3 (`tools.jackson.core:jackson-databind:3.1.5`) for JSON, Spring's `RestClient` for outbound HTTP, JUnit 5 + Mockito + `MockRestServiceServer` for tests. No new Gradle dependencies.

**Spec:** `docs/superpowers/specs/2026-09-13-telegram-webhook-design.md`

## Global Constraints

- Java 25 toolchain, Gradle wrapper only (`./gradlew`) — never a system Gradle.
- **Jackson 3, not Jackson 2**: import `tools.jackson.databind.ObjectMapper` and `tools.jackson.core.JacksonException` (unchecked). Do NOT import `com.fasterxml.jackson.databind.ObjectMapper` or `com.fasterxml.jackson.core.JsonProcessingException` — those are the wrong major version and won't resolve correctly against this project's classpath (`tools.jackson.databind.ObjectMapper` is the actual bean type Spring autoconfigures). Annotations (`@JsonProperty`, `@JsonIgnoreProperties`) DO still come from `com.fasterxml.jackson.annotation` — that module kept its original coordinates.
- No `RestClientAutoConfiguration` is present on this classpath — a `RestClient.Builder` bean must be defined explicitly (done in Task 3); do not assume Spring Boot provides one for free.
- Base package `com.tung.receipt_extractor`; new code goes in `com.tung.receipt_extractor.telegram`, parallel to the existing `ocr` and `sheets` packages.
- No inline comments unless documenting a non-obvious constraint (matches existing files like `OcrService.java`).
- The webhook endpoint always returns `200 OK` except for an invalid/missing secret-token header, which returns `401`.
- `telegram.bot-token`, `telegram.allowed-chat-id`, `telegram.webhook-secret-token` are required properties (no defaults) — the app fails to start if any is unset, exactly like the existing `sheets.*` properties. Tests must supply dummy values via `@TestPropertySource`.
- Run `./gradlew test` to verify each task; this requires Tesseract/Leptonica dev packages installed (pre-existing project prerequisite, see `CLAUDE.md`), unrelated to this feature.

---

## Task 1: Extract `ReceiptExtractionService` from `OcrController`

**Files:**
- Create: `src/main/java/com/tung/receipt_extractor/ocr/ReceiptExtractionService.java`
- Create: `src/test/java/com/tung/receipt_extractor/ocr/ReceiptExtractionServiceTest.java`
- Modify: `src/main/java/com/tung/receipt_extractor/ocr/OcrController.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java`

**Interfaces:**
- Produces: `ReceiptExtractionService.extract(byte[] imageBytes)` → `OcrResponse`, throws `TesseractException`, `IOException`. This is the shared entry point `TelegramWebhookController` will call in Task 5.
- Consumes: existing `OcrService.extractText(byte[])`, `BankSourceDetector.detect(String)`, `AmountDetector.detect(String)`, `MessageDetector.detect(String, String)`, `OcrResponse` record — all unchanged.

- [ ] **Step 1: Write the failing test for `ReceiptExtractionService`**

Create `src/test/java/com/tung/receipt_extractor/ocr/ReceiptExtractionServiceTest.java`:

```java
package com.tung.receipt_extractor.ocr;

import net.sourceforge.tess4j.TesseractException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReceiptExtractionServiceTest {

    @Test
    void extractsTextBankSourceAndAmountFromOcrText() throws Exception {
        OcrService ocrService = mock(OcrService.class);
        when(ocrService.extractText(any())).thenReturn("Tổng cộng: 125.000 VND");
        ReceiptExtractionService service = new ReceiptExtractionService(ocrService);

        OcrResponse response = service.extract("fake-image-bytes".getBytes());

        assertEquals("Tổng cộng: 125.000 VND", response.text());
        assertEquals("Zalopay", response.bankSource());
        assertEquals(125000L, response.amount());
    }

    @Test
    void returnsNullAmountWhenTextHasNoAmount() throws Exception {
        OcrService ocrService = mock(OcrService.class);
        when(ocrService.extractText(any())).thenReturn("Giao dịch thành công!");
        ReceiptExtractionService service = new ReceiptExtractionService(ocrService);

        OcrResponse response = service.extract("fake-image-bytes".getBytes());

        assertNull(response.amount());
    }

    @Test
    void returnsVietcombankBankSourceWhenTextContainsVcbMarker() throws Exception {
        OcrService ocrService = mock(OcrService.class);
        when(ocrService.extractText(any())).thenReturn("VCBDigibank\nGiao dịch thành công!\nVND 2,000");
        ReceiptExtractionService service = new ReceiptExtractionService(ocrService);

        OcrResponse response = service.extract("fake-image-bytes".getBytes());

        assertEquals("Vietcombank", response.bankSource());
    }

    @Test
    void returnsMessageExtractedForDetectedBankSource() throws Exception {
        OcrService ocrService = mock(OcrService.class);
        when(ocrService.extractText(any())).thenReturn(
                "TECHCOMBANK\nChuyển thành công\nLời nhắn\nNGUYEN SON TUNG chuyen tien\nNgày thực hiện\n12 thg 9, 2026");
        ReceiptExtractionService service = new ReceiptExtractionService(ocrService);

        OcrResponse response = service.extract("fake-image-bytes".getBytes());

        assertEquals("Techcombank", response.bankSource());
        assertEquals("NGUYEN SON TUNG chuyen tien", response.message());
    }

    @Test
    void propagatesTesseractExceptionFromOcrService() throws Exception {
        OcrService ocrService = mock(OcrService.class);
        when(ocrService.extractText(any())).thenThrow(new TesseractException("boom"));
        ReceiptExtractionService service = new ReceiptExtractionService(ocrService);

        assertThrows(TesseractException.class, () -> service.extract("fake-image-bytes".getBytes()));
    }

    @Test
    void propagatesIOExceptionFromOcrService() throws Exception {
        OcrService ocrService = mock(OcrService.class);
        when(ocrService.extractText(any())).thenThrow(new IOException("boom"));
        ReceiptExtractionService service = new ReceiptExtractionService(ocrService);

        assertThrows(IOException.class, () -> service.extract("fake-image-bytes".getBytes()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.ReceiptExtractionServiceTest"`
Expected: FAIL — compile error, `ReceiptExtractionService` does not exist.

- [ ] **Step 3: Create `ReceiptExtractionService`**

Create `src/main/java/com/tung/receipt_extractor/ocr/ReceiptExtractionService.java`:

```java
package com.tung.receipt_extractor.ocr;

import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class ReceiptExtractionService {

    private final OcrService ocrService;

    public ReceiptExtractionService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public OcrResponse extract(byte[] imageBytes) throws TesseractException, IOException {
        String text = ocrService.extractText(imageBytes);
        String bankSource = BankSourceDetector.detect(text);
        Long amount = AmountDetector.detect(text);
        String message = MessageDetector.detect(text, bankSource);
        return new OcrResponse(text, bankSource, amount, message);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.ReceiptExtractionServiceTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Update `OcrController` to delegate to `ReceiptExtractionService`**

Replace the full contents of `src/main/java/com/tung/receipt_extractor/ocr/OcrController.java` with:

```java
package com.tung.receipt_extractor.ocr;

import com.tung.receipt_extractor.sheets.SheetRowAppender;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@RestController
public class OcrController {

    private static final Logger log = LoggerFactory.getLogger(OcrController.class);
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final ReceiptExtractionService receiptExtractionService;
    private final SheetRowAppender sheetRowAppender;

    public OcrController(ReceiptExtractionService receiptExtractionService, SheetRowAppender sheetRowAppender) {
        this.receiptExtractionService = receiptExtractionService;
        this.sheetRowAppender = sheetRowAppender;
    }

    @PostMapping(value = "/api/ocr/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> extractText(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        if (file.getContentType() == null || !SUPPORTED_CONTENT_TYPES.contains(file.getContentType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported file type"));
        }
        try {
            OcrResponse response = receiptExtractionService.extract(file.getBytes());
            try {
                sheetRowAppender.appendRow(response.bankSource(), response.amount(), response.message());
            } catch (Exception e) {
                log.error("Sheets append failed; returning OCR response anyway", e);
            }
            return ResponseEntity.ok(response);
        } catch (TesseractException | IOException e) {
            log.error("OCR extraction failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to extract text from image"));
        }
    }
}
```

- [ ] **Step 6: Update `OcrControllerTest` to mock `ReceiptExtractionService` instead of `OcrService`**

Replace the full contents of `src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java` with:

```java
package com.tung.receipt_extractor.ocr;

import com.tung.receipt_extractor.sheets.SheetRowAppender;
import net.sourceforge.tess4j.TesseractException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OcrController.class)
class OcrControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReceiptExtractionService receiptExtractionService;

    @MockitoBean
    private SheetRowAppender sheetRowAppender;

    @Test
    void returnsExtractedResponseForValidImageUpload() throws Exception {
        when(receiptExtractionService.extract(any())).thenReturn(
                new OcrResponse("Tổng cộng: 125.000 VND", "Zalopay", 125000L, "chuyen tien"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Tổng cộng: 125.000 VND"))
                .andExpect(jsonPath("$.bankSource").value("Zalopay"))
                .andExpect(jsonPath("$.amount").value(125000))
                .andExpect(jsonPath("$.message").value("chuyen tien"));
    }

    @Test
    void invokesSheetRowAppenderWithExtractedFields() throws Exception {
        when(receiptExtractionService.extract(any())).thenReturn(
                new OcrResponse("raw text", "Techcombank", 50000L, "NGUYEN VAN A chuyen tien"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk());

        verify(sheetRowAppender).appendRow("Techcombank", 50000L, "NGUYEN VAN A chuyen tien");
    }

    @Test
    void returns200EvenWhenSheetRowAppenderThrows() throws Exception {
        when(receiptExtractionService.extract(any())).thenReturn(
                new OcrResponse("Giao dịch thành công!\nVND 2,000", "Zalopay", 2000L, ""));
        doThrow(new RuntimeException("boom")).when(sheetRowAppender).appendRow(any(), any(), any());

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(2000));
    }

    @Test
    void returns400ForUnsupportedContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.txt", "text/plain", "not an image".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported file type"));
    }

    @Test
    void returns400ForEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", new byte[0]);

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file is required"));
    }

    @Test
    void returns400ForNullContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", null, "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported file type"));
    }

    @Test
    void returns400WhenFilePartIsMissing() throws Exception {
        mockMvc.perform(multipart("/api/ocr/extract"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file is required"));
    }

    @Test
    void returns500WhenReceiptExtractionServiceThrows() throws Exception {
        when(receiptExtractionService.extract(any())).thenThrow(new TesseractException("boom"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("failed to extract text from image"));
    }
}
```

- [ ] **Step 7: Run the full test suite to verify nothing broke**

Run: `./gradlew test`
Expected: PASS. `OcrEndToEndTest` and `OcrServiceTest` need no changes — `ReceiptExtractionService` is a real Spring `@Service` bean picked up automatically, and `OcrEndToEndTest` only mocks `SheetRowAppender`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/ocr/ReceiptExtractionService.java \
        src/test/java/com/tung/receipt_extractor/ocr/ReceiptExtractionServiceTest.java \
        src/main/java/com/tung/receipt_extractor/ocr/OcrController.java \
        src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java
git commit -m "$(cat <<'EOF'
Extract ReceiptExtractionService from OcrController

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Telegram update JSON DTOs

**Files:**
- Create: `src/main/java/com/tung/receipt_extractor/telegram/TelegramPhotoSize.java`
- Create: `src/main/java/com/tung/receipt_extractor/telegram/TelegramChat.java`
- Create: `src/main/java/com/tung/receipt_extractor/telegram/TelegramMessage.java`
- Create: `src/main/java/com/tung/receipt_extractor/telegram/TelegramUpdate.java`
- Create: `src/test/java/com/tung/receipt_extractor/telegram/TelegramUpdateTest.java`

**Interfaces:**
- Produces: `TelegramUpdate(long updateId, TelegramMessage message)`, `TelegramMessage(TelegramChat chat, List<TelegramPhotoSize> photo, String caption, String text)` with `TelegramMessage.largestPhoto()` → `TelegramPhotoSize` or `null`, `TelegramChat(long id)`, `TelegramPhotoSize(String fileId, int width, int height)`. These are consumed by `TelegramWebhookController` in Task 5.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing test for JSON parsing**

Create `src/test/java/com/tung/receipt_extractor/telegram/TelegramUpdateTest.java`:

```java
package com.tung.receipt_extractor.telegram;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TelegramUpdateTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    void parsesPhotoMessageWithCaption() {
        String json = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "small-id", "width": 90, "height": 90 },
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ],
                    "caption": "override message"
                  }
                }
                """;

        TelegramUpdate update = objectMapper.readValue(json, TelegramUpdate.class);

        assertEquals(1L, update.updateId());
        assertEquals(123456789L, update.message().chat().id());
        assertEquals("override message", update.message().caption());
        assertEquals("large-id", update.message().largestPhoto().fileId());
    }

    @Test
    void parsesTextOnlyMessageWithNoPhoto() {
        String json = """
                {
                  "update_id": 2,
                  "message": {
                    "chat": { "id": 123456789 },
                    "text": "hello"
                  }
                }
                """;

        TelegramUpdate update = objectMapper.readValue(json, TelegramUpdate.class);

        assertEquals("hello", update.message().text());
        assertNull(update.message().largestPhoto());
    }

    @Test
    void ignoresUnknownFieldsAndUnrelatedUpdateTypes() {
        String json = """
                {
                  "update_id": 3,
                  "edited_message": {
                    "chat": { "id": 123456789 },
                    "text": "edited"
                  }
                }
                """;

        TelegramUpdate update = objectMapper.readValue(json, TelegramUpdate.class);

        assertNull(update.message());
    }

    @Test
    void picksLargestPhotoByWidthRegardlessOfArrayOrder() {
        String json = """
                {
                  "update_id": 4,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "medium-id", "width": 320, "height": 320 },
                      { "file_id": "large-id", "width": 800, "height": 800 },
                      { "file_id": "small-id", "width": 90, "height": 90 }
                    ]
                  }
                }
                """;

        TelegramUpdate update = objectMapper.readValue(json, TelegramUpdate.class);

        assertEquals("large-id", update.message().largestPhoto().fileId());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.tung.receipt_extractor.telegram.TelegramUpdateTest"`
Expected: FAIL — compile error, none of the DTO classes exist yet.

- [ ] **Step 3: Create the DTO classes**

Create `src/main/java/com/tung/receipt_extractor/telegram/TelegramPhotoSize.java`:

```java
package com.tung.receipt_extractor.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramPhotoSize(@JsonProperty("file_id") String fileId, int width, int height) {
}
```

Create `src/main/java/com/tung/receipt_extractor/telegram/TelegramChat.java`:

```java
package com.tung.receipt_extractor.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramChat(long id) {
}
```

Create `src/main/java/com/tung/receipt_extractor/telegram/TelegramMessage.java`:

```java
package com.tung.receipt_extractor.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Comparator;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(TelegramChat chat, List<TelegramPhotoSize> photo, String caption, String text) {

    public TelegramPhotoSize largestPhoto() {
        if (photo == null || photo.isEmpty()) {
            return null;
        }
        return photo.stream().max(Comparator.comparingInt(TelegramPhotoSize::width)).orElse(null);
    }
}
```

Create `src/main/java/com/tung/receipt_extractor/telegram/TelegramUpdate.java`:

```java
package com.tung.receipt_extractor.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(@JsonProperty("update_id") long updateId, TelegramMessage message) {
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.tung.receipt_extractor.telegram.TelegramUpdateTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/telegram/TelegramPhotoSize.java \
        src/main/java/com/tung/receipt_extractor/telegram/TelegramChat.java \
        src/main/java/com/tung/receipt_extractor/telegram/TelegramMessage.java \
        src/main/java/com/tung/receipt_extractor/telegram/TelegramUpdate.java \
        src/test/java/com/tung/receipt_extractor/telegram/TelegramUpdateTest.java
git commit -m "$(cat <<'EOF'
Add Telegram update JSON DTOs

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `TelegramProperties` + `TelegramConfig` + config wiring

**Files:**
- Create: `src/main/java/com/tung/receipt_extractor/telegram/TelegramProperties.java`
- Create: `src/main/java/com/tung/receipt_extractor/telegram/TelegramConfig.java`
- Create: `src/test/java/com/tung/receipt_extractor/telegram/TelegramConfigTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/tung/receipt_extractor/ReceiptExtractorApplicationTests.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ServerPortConfigurationTest.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ServerPortEnvVarOverrideTest.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java`

**Interfaces:**
- Produces: `TelegramProperties(String botToken, long allowedChatId, String webhookSecretToken)` bean, and a `RestClient.Builder` bean. Both are consumed by `TelegramClient` in Task 4 and `TelegramWebhookController` in Task 5.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing test for `TelegramConfig`**

Create `src/test/java/com/tung/receipt_extractor/telegram/TelegramConfigTest.java`:

```java
package com.tung.receipt_extractor.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TelegramConfigTest {

    @Test
    void buildsTelegramPropertiesFromConfiguredValues() {
        TelegramConfig config = new TelegramConfig();

        TelegramProperties properties = config.telegramProperties("test-bot-token", 123456789L, "test-secret");

        assertEquals("test-bot-token", properties.botToken());
        assertEquals(123456789L, properties.allowedChatId());
        assertEquals("test-secret", properties.webhookSecretToken());
    }

    @Test
    void buildsRestClientBuilder() {
        TelegramConfig config = new TelegramConfig();

        assertNotNull(config.restClientBuilder());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.tung.receipt_extractor.telegram.TelegramConfigTest"`
Expected: FAIL — compile error, `TelegramConfig`/`TelegramProperties` do not exist.

- [ ] **Step 3: Create `TelegramProperties` and `TelegramConfig`**

Create `src/main/java/com/tung/receipt_extractor/telegram/TelegramProperties.java`:

```java
package com.tung.receipt_extractor.telegram;

public record TelegramProperties(String botToken, long allowedChatId, String webhookSecretToken) {
}
```

Create `src/main/java/com/tung/receipt_extractor/telegram/TelegramConfig.java`:

```java
package com.tung.receipt_extractor.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class TelegramConfig {

    @Bean
    public TelegramProperties telegramProperties(
            @Value("${telegram.bot-token}") String botToken,
            @Value("${telegram.allowed-chat-id}") long allowedChatId,
            @Value("${telegram.webhook-secret-token}") String webhookSecretToken) {
        return new TelegramProperties(botToken, allowedChatId, webhookSecretToken);
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.tung.receipt_extractor.telegram.TelegramConfigTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the `telegram` section to `application.yml`**

In `src/main/resources/application.yml`, after the existing `sheets:` block, add:

```yaml

telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
  allowed-chat-id: ${TELEGRAM_ALLOWED_CHAT_ID}
  webhook-secret-token: ${TELEGRAM_WEBHOOK_SECRET_TOKEN}
```

The full file should read:

```yaml
spring:
  application:
    name: receipt-extractor
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB

server:
  port: ${PORT:8080}

ocr:
  tessdata-path: src/main/resources/tessdata
  languages: vie+eng

sheets:
  client-id: ${SHEETS_CLIENT_ID}
  client-secret: ${SHEETS_CLIENT_SECRET}
  refresh-token: ${SHEETS_REFRESH_TOKEN}
  spreadsheet-id: ${SHEETS_SPREADSHEET_ID}
  sheet-name: ${SHEETS_SHEET_NAME}

telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
  allowed-chat-id: ${TELEGRAM_ALLOWED_CHAT_ID}
  webhook-secret-token: ${TELEGRAM_WEBHOOK_SECRET_TOKEN}
```

- [ ] **Step 6: Run the full test suite to see it now fails on missing Telegram properties**

Run: `./gradlew test`
Expected: FAIL — every `@SpringBootTest` test (`ReceiptExtractorApplicationTests`, `ServerPortConfigurationTest`, `ServerPortEnvVarOverrideTest`, `OcrEndToEndTest`) now fails to start the application context, because `telegram.bot-token`/`telegram.allowed-chat-id`/`telegram.webhook-secret-token` have no value and no default. This is expected — fixed in the next step.

- [ ] **Step 7: Add dummy Telegram properties to every `@SpringBootTest`**

In `src/test/java/com/tung/receipt_extractor/ReceiptExtractorApplicationTests.java`, replace:

```java
@TestPropertySource(properties = {
		"sheets.client-id=test-client-id",
		"sheets.client-secret=test-client-secret",
		"sheets.refresh-token=test-refresh-token",
		"sheets.spreadsheet-id=test-spreadsheet-id",
		"sheets.sheet-name=Test"
})
```

with:

```java
@TestPropertySource(properties = {
		"sheets.client-id=test-client-id",
		"sheets.client-secret=test-client-secret",
		"sheets.refresh-token=test-refresh-token",
		"sheets.spreadsheet-id=test-spreadsheet-id",
		"sheets.sheet-name=Test",
		"telegram.bot-token=test-bot-token",
		"telegram.allowed-chat-id=123456789",
		"telegram.webhook-secret-token=test-secret"
})
```

In `src/test/java/com/tung/receipt_extractor/ServerPortConfigurationTest.java`, replace:

```java
@TestPropertySource(properties = {
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test"
})
```

with:

```java
@TestPropertySource(properties = {
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test",
        "telegram.bot-token=test-bot-token",
        "telegram.allowed-chat-id=123456789",
        "telegram.webhook-secret-token=test-secret"
})
```

In `src/test/java/com/tung/receipt_extractor/ServerPortEnvVarOverrideTest.java`, replace:

```java
@TestPropertySource(properties = {
        "PORT=9090",
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test"
})
```

with:

```java
@TestPropertySource(properties = {
        "PORT=9090",
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test",
        "telegram.bot-token=test-bot-token",
        "telegram.allowed-chat-id=123456789",
        "telegram.webhook-secret-token=test-secret"
})
```

In `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java`, replace:

```java
@TestPropertySource(properties = {
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test"
})
```

with:

```java
@TestPropertySource(properties = {
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test",
        "telegram.bot-token=test-bot-token",
        "telegram.allowed-chat-id=123456789",
        "telegram.webhook-secret-token=test-secret"
})
```

- [ ] **Step 8: Run the full test suite to verify it passes again**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/telegram/TelegramProperties.java \
        src/main/java/com/tung/receipt_extractor/telegram/TelegramConfig.java \
        src/test/java/com/tung/receipt_extractor/telegram/TelegramConfigTest.java \
        src/main/resources/application.yml \
        src/test/java/com/tung/receipt_extractor/ReceiptExtractorApplicationTests.java \
        src/test/java/com/tung/receipt_extractor/ServerPortConfigurationTest.java \
        src/test/java/com/tung/receipt_extractor/ServerPortEnvVarOverrideTest.java \
        src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java
git commit -m "$(cat <<'EOF'
Add Telegram configuration properties

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `TelegramClient`

**Files:**
- Create: `src/main/java/com/tung/receipt_extractor/telegram/TelegramClient.java`
- Create: `src/test/java/com/tung/receipt_extractor/telegram/TelegramClientTest.java`

**Interfaces:**
- Produces: `TelegramClient.getFilePath(String fileId)` → `String`, `TelegramClient.downloadFile(String filePath)` → `byte[]`, `TelegramClient.sendMessage(long chatId, String text)` → `void`. Consumed by `TelegramWebhookController` in Task 5.
- Consumes: `TelegramProperties` (Task 3) via constructor injection, and Spring's `RestClient.Builder` bean (Task 3).

- [ ] **Step 1: Write the failing test for `TelegramClient`**

Create `src/test/java/com/tung/receipt_extractor/telegram/TelegramClientTest.java`:

```java
package com.tung.receipt_extractor.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramClientTest {

    private static final String BOT_TOKEN = "test-bot-token";
    private static final TelegramProperties PROPERTIES =
            new TelegramProperties(BOT_TOKEN, 123456789L, "test-secret");

    @Test
    void getFilePathReturnsFilePathFromTelegramResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/getFile?file_id=file-123"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"ok\":true,\"result\":{\"file_id\":\"file-123\",\"file_path\":\"photos/file_1.jpg\"}}",
                        MediaType.APPLICATION_JSON));
        TelegramClient client = new TelegramClient(builder, PROPERTIES);

        String filePath = client.getFilePath("file-123");

        assertEquals("photos/file_1.jpg", filePath);
        server.verify();
    }

    @Test
    void downloadFileReturnsRawBytesFromTelegramFileEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] imageBytes = {1, 2, 3, 4};
        server.expect(requestTo("https://api.telegram.org/file/bot" + BOT_TOKEN + "/photos/file_1.jpg"))
                .andExpect(method(GET))
                .andRespond(withSuccess(imageBytes, MediaType.APPLICATION_OCTET_STREAM));
        TelegramClient client = new TelegramClient(builder, PROPERTIES);

        byte[] result = client.downloadFile("photos/file_1.jpg");

        assertArrayEquals(imageBytes, result);
        server.verify();
    }

    @Test
    void sendMessagePostsChatIdAndTextToTelegram() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"chat_id\":123456789,\"text\":\"hello\"}"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        TelegramClient client = new TelegramClient(builder, PROPERTIES);

        client.sendMessage(123456789L, "hello");

        server.verify();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.tung.receipt_extractor.telegram.TelegramClientTest"`
Expected: FAIL — compile error, `TelegramClient` does not exist.

- [ ] **Step 3: Create `TelegramClient`**

Create `src/main/java/com/tung/receipt_extractor/telegram/TelegramClient.java`:

```java
package com.tung.receipt_extractor.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

@Service
public class TelegramClient {

    private static final String TELEGRAM_API_BASE_URL = "https://api.telegram.org";

    private final RestClient restClient;
    private final String botToken;

    public TelegramClient(RestClient.Builder restClientBuilder, TelegramProperties telegramProperties) {
        this.restClient = restClientBuilder.build();
        this.botToken = telegramProperties.botToken();
    }

    public String getFilePath(String fileId) {
        URI uri = URI.create(TELEGRAM_API_BASE_URL + "/bot" + botToken + "/getFile?file_id=" + fileId);
        TelegramFileResponse response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(TelegramFileResponse.class);
        return response.result().filePath();
    }

    public byte[] downloadFile(String filePath) {
        URI uri = URI.create(TELEGRAM_API_BASE_URL + "/file/bot" + botToken + "/" + filePath);
        return restClient.get()
                .uri(uri)
                .retrieve()
                .body(byte[].class);
    }

    public void sendMessage(long chatId, String text) {
        URI uri = URI.create(TELEGRAM_API_BASE_URL + "/bot" + botToken + "/sendMessage");
        restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chat_id", chatId, "text", text))
                .retrieve()
                .toBodilessEntity();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramFileResponse(boolean ok, TelegramFile result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramFile(@JsonProperty("file_id") String fileId, @JsonProperty("file_path") String filePath) {
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.tung.receipt_extractor.telegram.TelegramClientTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/telegram/TelegramClient.java \
        src/test/java/com/tung/receipt_extractor/telegram/TelegramClientTest.java
git commit -m "$(cat <<'EOF'
Add TelegramClient for Telegram Bot API calls

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `TelegramWebhookController`

**Files:**
- Create: `src/main/java/com/tung/receipt_extractor/telegram/TelegramWebhookController.java`
- Create: `src/test/java/com/tung/receipt_extractor/telegram/TelegramWebhookControllerTest.java`

**Interfaces:**
- Produces: `POST /api/telegram-webhook` endpoint.
- Consumes: `ObjectMapper` (Spring-provided bean, `tools.jackson.databind.ObjectMapper`), `TelegramProperties` (Task 3), `TelegramClient` (Task 4), `ReceiptExtractionService.extract(byte[])` → `OcrResponse` (Task 1), `SheetRowAppender.appendRow(String, Long, String)` (existing).

- [ ] **Step 1: Write the failing tests for `TelegramWebhookController`**

Create `src/test/java/com/tung/receipt_extractor/telegram/TelegramWebhookControllerTest.java`:

```java
package com.tung.receipt_extractor.telegram;

import com.tung.receipt_extractor.ocr.OcrResponse;
import com.tung.receipt_extractor.ocr.ReceiptExtractionService;
import com.tung.receipt_extractor.sheets.SheetRowAppender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TelegramWebhookController.class)
class TelegramWebhookControllerTest {

    private static final String SECRET_TOKEN = "test-secret";
    private static final long ALLOWED_CHAT_ID = 123456789L;
    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    @TestConfiguration
    static class Config {
        @Bean
        TelegramProperties telegramProperties() {
            return new TelegramProperties("test-bot-token", ALLOWED_CHAT_ID, SECRET_TOKEN);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramClient telegramClient;

    @MockitoBean
    private ReceiptExtractionService receiptExtractionService;

    @MockitoBean
    private SheetRowAppender sheetRowAppender;

    @Test
    void missingOrWrongSecretTokenHeaderReturns401AndDoesNothingElse() throws Exception {
        String payload = """
                { "update_id": 1, "message": { "chat": { "id": 123456789 }, "text": "hi" } }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(telegramClient, receiptExtractionService, sheetRowAppender);
    }

    @Test
    void photoWithCaptionUsesCaptionAsMessageOverride() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenReturn("photos/file.jpg");
        when(telegramClient.downloadFile("photos/file.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(receiptExtractionService.extract(any()))
                .thenReturn(new OcrResponse("raw text", "Techcombank", 50000L, "extracted message"));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "small-id", "width": 90, "height": 90 },
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ],
                    "caption": "override message"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).getFilePath("large-id");
        verify(sheetRowAppender).appendRow("Techcombank", 50000L, "override message");
        verify(telegramClient).sendMessage(eq(ALLOWED_CHAT_ID), contains("override message"));
    }

    @Test
    void photoWithoutCaptionUsesExtractedMessage() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenReturn("photos/file.jpg");
        when(telegramClient.downloadFile("photos/file.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(receiptExtractionService.extract(any()))
                .thenReturn(new OcrResponse("raw text", "Techcombank", 50000L, "extracted message"));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(sheetRowAppender).appendRow("Techcombank", 50000L, "extracted message");
    }

    @Test
    void noPhotoRepliesWithErrorAndDoesNotAppendToSheet() throws Exception {
        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "text": "hello"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, "Please send a photo of your receipt.");
        verify(receiptExtractionService, never()).extract(any());
        verify(sheetRowAppender, never()).appendRow(any(), any(), any());
    }

    @Test
    void wrongChatIdIsIgnoredWithNoReplyOrProcessing() throws Exception {
        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 999 },
                    "text": "hello"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(telegramClient, receiptExtractionService, sheetRowAppender);
    }

    @Test
    void updateWithNoMessageIsIgnored() throws Exception {
        String payload = """
                {
                  "update_id": 1,
                  "edited_message": {
                    "chat": { "id": 123456789 },
                    "text": "edited"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(telegramClient, receiptExtractionService, sheetRowAppender);
    }

    @Test
    void exceptionDuringProcessingRepliesWithGenericErrorAndStillReturns200() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenThrow(new RuntimeException("boom"));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, "Sorry, couldn't process that image. Please try again.");
        verify(sheetRowAppender, never()).appendRow(any(), any(), any());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.tung.receipt_extractor.telegram.TelegramWebhookControllerTest"`
Expected: FAIL — compile error, `TelegramWebhookController` does not exist.

- [ ] **Step 3: Create `TelegramWebhookController`**

Create `src/main/java/com/tung/receipt_extractor/telegram/TelegramWebhookController.java`:

```java
package com.tung.receipt_extractor.telegram;

import com.tung.receipt_extractor.ocr.OcrResponse;
import com.tung.receipt_extractor.ocr.ReceiptExtractionService;
import com.tung.receipt_extractor.sheets.SheetRowAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);
    private static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final String NO_PHOTO_REPLY = "Please send a photo of your receipt.";
    private static final String PROCESSING_FAILED_REPLY = "Sorry, couldn't process that image. Please try again.";

    private final ObjectMapper objectMapper;
    private final TelegramProperties telegramProperties;
    private final TelegramClient telegramClient;
    private final ReceiptExtractionService receiptExtractionService;
    private final SheetRowAppender sheetRowAppender;

    public TelegramWebhookController(
            ObjectMapper objectMapper,
            TelegramProperties telegramProperties,
            TelegramClient telegramClient,
            ReceiptExtractionService receiptExtractionService,
            SheetRowAppender sheetRowAppender) {
        this.objectMapper = objectMapper;
        this.telegramProperties = telegramProperties;
        this.telegramClient = telegramClient;
        this.receiptExtractionService = receiptExtractionService;
        this.sheetRowAppender = sheetRowAppender;
    }

    @PostMapping("/api/telegram-webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = SECRET_TOKEN_HEADER, required = false) String secretTokenHeader,
            @RequestBody String rawBody) {
        if (!telegramProperties.webhookSecretToken().equals(secretTokenHeader)) {
            log.warn("Rejected Telegram webhook call with invalid secret token header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Telegram webhook payload: {}", rawBody);

        TelegramUpdate update;
        try {
            update = objectMapper.readValue(rawBody, TelegramUpdate.class);
        } catch (JacksonException e) {
            log.error("Failed to parse Telegram webhook payload", e);
            return ResponseEntity.ok().build();
        }

        TelegramMessage message = update.message();
        if (message == null || message.chat() == null) {
            return ResponseEntity.ok().build();
        }

        long chatId = message.chat().id();
        if (chatId != telegramProperties.allowedChatId()) {
            log.warn("Ignoring Telegram message from non-allow-listed chat id {}", chatId);
            return ResponseEntity.ok().build();
        }

        TelegramPhotoSize largestPhoto = message.largestPhoto();
        if (largestPhoto == null) {
            replySafely(chatId, NO_PHOTO_REPLY);
            return ResponseEntity.ok().build();
        }

        try {
            String filePath = telegramClient.getFilePath(largestPhoto.fileId());
            byte[] imageBytes = telegramClient.downloadFile(filePath);

            OcrResponse extracted = receiptExtractionService.extract(imageBytes);
            String finalMessage = message.caption() != null && !message.caption().isBlank()
                    ? message.caption()
                    : extracted.message();

            sheetRowAppender.appendRow(extracted.bankSource(), extracted.amount(), finalMessage);

            replySafely(chatId, buildConfirmationReply(extracted.bankSource(), extracted.amount(), finalMessage));
        } catch (Exception e) {
            log.error("Failed to process Telegram update {} for chat id {}", update.updateId(), chatId, e);
            replySafely(chatId, PROCESSING_FAILED_REPLY);
        }

        return ResponseEntity.ok().build();
    }

    private void replySafely(long chatId, String text) {
        try {
            telegramClient.sendMessage(chatId, text);
        } catch (RuntimeException e) {
            log.error("Failed to send Telegram reply to chat id {}", chatId, e);
        }
    }

    private String buildConfirmationReply(String bankSource, Long amount, String message) {
        String bankSourceText = (bankSource == null || bankSource.isBlank()) ? "unknown" : bankSource;
        String amountText = amount == null ? "unknown" : String.valueOf(amount);
        String messageText = (message == null || message.isBlank()) ? "unknown" : message;
        return "Recorded: " + bankSourceText + ", " + amountText + ", " + messageText;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.tung.receipt_extractor.telegram.TelegramWebhookControllerTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/telegram/TelegramWebhookController.java \
        src/test/java/com/tung/receipt_extractor/telegram/TelegramWebhookControllerTest.java
git commit -m "$(cat <<'EOF'
Add Telegram webhook endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Documentation and deploy template

**Files:**
- Modify: `deploy/sheets-env.yaml.example`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: nothing (documentation only). Produces nothing consumed by other tasks — this is the final task.

- [ ] **Step 1: Add Telegram placeholders to the deploy env template**

Replace the full contents of `deploy/sheets-env.yaml.example` with:

```yaml
SHEETS_CLIENT_ID: "your-oauth-client-id"
SHEETS_CLIENT_SECRET: "your-oauth-client-secret"
SHEETS_REFRESH_TOKEN: "your-oauth-refresh-token"
SHEETS_SPREADSHEET_ID: "your-google-sheet-id"
SHEETS_SHEET_NAME: "your-google-sheet-tab-name"
TELEGRAM_BOT_TOKEN: "your-telegram-bot-token"
TELEGRAM_ALLOWED_CHAT_ID: "your-telegram-chat-id"
TELEGRAM_WEBHOOK_SECRET_TOKEN: "your-telegram-webhook-secret-token"
```

- [ ] **Step 2: Add a Telegram webhook section to `CLAUDE.md`**

In `CLAUDE.md`, after the `## Google Sheets export` section and before `## Deploying to Google Cloud`, insert:

```markdown
## Telegram webhook integration

- Endpoint: `POST /api/telegram-webhook` — receives a Telegram Bot API `Update` JSON payload. The Telegram bot itself (webhook registration, bot token issuance) is configured outside this repo.
- Every request must carry the header `X-Telegram-Bot-Api-Secret-Token` matching the configured `telegram.webhook-secret-token`, or the endpoint returns `401` without parsing or logging the body. This is the value passed as `secret_token` when the webhook was registered via Telegram's `setWebhook` call.
- The raw JSON payload is logged at `info` level for every request that passes the secret-token check — useful for debugging webhook behavior.
- Only messages from the chat id configured in `telegram.allowed-chat-id` are processed; messages from any other chat are silently ignored (`200 OK`, no reply).
- A message must include a photo. The largest resolution is downloaded via Telegram's `getFile`/file-download APIs, OCR'd with the same pipeline as `/api/ocr/extract` (`ReceiptExtractionService`), and appended to the same Google Sheet as `/api/ocr/extract`. If the message has a non-blank caption, the caption overrides the OCR-derived message for that row.
- The bot replies in the chat with a confirmation summary on success, or a short error message if there's no photo or processing fails. The webhook always returns `200 OK` (except the secret-token check, which returns `401`) so Telegram never retry-storms it.
- Config properties (in `application.yml`): `telegram.bot-token`, `telegram.allowed-chat-id`, `telegram.webhook-secret-token`, bound from `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_CHAT_ID`, `TELEGRAM_WEBHOOK_SECRET_TOKEN`. Same as the Sheets properties, the app fails to start if any is unset; not required for running the test suite (tests set dummy values via `@TestPropertySource`).
```

Also update the Cloud Run deploy bullet in `## Deploying to Google Cloud` — replace:

```markdown
- Secrets are passed as plain Cloud Run environment variables (not Secret Manager — see the design spec for the trade-off) via `--env-vars-file`. Copy `deploy/sheets-env.yaml.example` to `deploy/sheets-env.yaml` (gitignored) and fill in real values before deploying.
```

with:

```markdown
- Secrets are passed as plain Cloud Run environment variables (not Secret Manager — see the design spec for the trade-off) via `--env-vars-file`. Copy `deploy/sheets-env.yaml.example` to `deploy/sheets-env.yaml` (gitignored) and fill in real values before deploying — this covers both the Sheets and Telegram integration variables.
```

- [ ] **Step 3: Add the same three keys to your local `deploy/sheets-env.yaml`**

This file is gitignored and holds real secrets, so it is a manual step, not a code change: open `deploy/sheets-env.yaml` and add `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_CHAT_ID`, and `TELEGRAM_WEBHOOK_SECRET_TOKEN` with your real bot token, allow-listed chat id, and webhook secret token, matching the format of the existing `SHEETS_*` keys.

- [ ] **Step 4: Run the full test suite one more time**

Run: `./gradlew test`
Expected: PASS. (Documentation-only changes; this just confirms nothing else regressed.)

- [ ] **Step 5: Commit**

```bash
git add deploy/sheets-env.yaml.example CLAUDE.md
git commit -m "$(cat <<'EOF'
Document Telegram webhook integration

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
