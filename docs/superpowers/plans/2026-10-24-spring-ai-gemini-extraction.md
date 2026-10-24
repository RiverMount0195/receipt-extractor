# Spring AI + Gemini Receipt Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Tesseract OCR + regex pipeline with a Gemini vision model called through Spring AI, returning structured JSON `{bankSource, amount, message}`, and drop GraalVM native-image support.

**Architecture:** `ReceiptExtractionService` sends the image bytes to a Spring AI `ChatClient` (Google GenAI / Gemini) and maps the reply to the `ReceiptData` record via `.entity(ReceiptData.class)`. The Telegram webhook and `POST /api/ocr/extract` both call it; Sheets export and Telegram reply logic are unchanged. The build and Docker image go back to a plain Spring Boot jar on a JVM.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.x (`spring-ai-starter-model-google-genai`), Gradle (Groovy), JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-24-spring-ai-gemini-extraction-design.md`

## Global Constraints

- Provider is Google Gemini through Spring AI's Google GenAI starter, authenticated with the `GOOGLE_API_KEY` environment variable (AI Studio API key).
- JSON fields are exactly `bankSource` (String), `amount` (Long, VND integer, `null` if not found), `message` (String).
- Tesseract is removed entirely, with no fallback; Gemini failure means the request fails.
- `POST /api/ocr/extract` path is unchanged; its response drops the `text` field and becomes `ReceiptData`.
- Classes move from package `com.tung.receipt_extractor.ocr` to `com.tung.receipt_extractor.receipt`.
- No GraalVM native image: remove the plugin, metadata, regenerate script and `@RegisterReflectionForBinding` annotations; keep `options.encoding = 'UTF-8'` in `build.gradle`.
- Tests never call the real Gemini API; `@SpringBootTest` classes set `spring.ai.google.genai.api-key=test-api-key`.
- Work on branch `feature/spring-ai-receipt-extraction`. Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

---

### Task 1: Add Spring AI dependency and configuration

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/tung/receipt_extractor/ReceiptExtractorApplicationTests.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ServerPortConfigurationTest.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ServerPortEnvVarOverrideTest.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java`

**Interfaces:**
- Produces: a `ChatClient.Builder` bean available for injection (auto-configured by the starter) and the property `spring.ai.google.genai.api-key`.

This task also verifies that Spring AI 2.0 resolves and starts against Spring Boot 4.1.1, which is the main uncertainty of the whole change. If it does not, stop and report before continuing.

- [ ] **Step 1: Add the Spring AI BOM and starter to `build.gradle`**

In `build.gradle`, add after the `repositories { ... }` block:

```groovy
dependencyManagement {
	imports {
		mavenBom 'org.springframework.ai:spring-ai-bom:2.0.0'
	}
}
```

and in `dependencies { ... }` add this line after the `google-auth-library-oauth2-http` line:

```groovy
	implementation 'org.springframework.ai:spring-ai-starter-model-google-genai'
```

If Gradle cannot resolve `2.0.0`, check the exact GA version in https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/ and use that.

- [ ] **Step 2: Add the Spring AI properties to `application.yml`**

Under the existing top-level `spring:` key, next to `application:` and `servlet:`, add an `ai:` block so the file reads:

```yaml
spring:
  application:
    name: receipt-extractor
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
  ai:
    google:
      genai:
        api-key: ${GOOGLE_API_KEY}
        chat:
          options:
            model: ${GEMINI_MODEL:gemini-2.5-flash}
            temperature: 0.1
```

Leave the rest of the file (`server`, `ocr`, `sheets`, `telegram`) as it is for now.

- [ ] **Step 3: Add the dummy API key to every `@SpringBootTest` class**

In each of `ReceiptExtractorApplicationTests`, `ServerPortConfigurationTest`, `ServerPortEnvVarOverrideTest` and `ocr/OcrEndToEndTest`, add this entry to the `@TestPropertySource(properties = { ... })` array (mind the trailing comma on the previous last entry):

```java
"spring.ai.google.genai.api-key=test-api-key"
```

- [ ] **Step 4: Run the context test**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ReceiptExtractorApplicationTests" --tests "com.tung.receipt_extractor.ServerPortConfigurationTest" --tests "com.tung.receipt_extractor.ServerPortEnvVarOverrideTest"`
Expected: PASS (the context starts with the Google GenAI auto-configuration and the dummy key). If it fails with a Spring Boot / Spring AI incompatibility error, stop and report.

- [ ] **Step 5: Commit**

```bash
git add build.gradle src/main/resources/application.yml src/test
git commit -m "feat: add Spring AI Google GenAI dependency and config

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: ReceiptData and the Gemini-backed ReceiptExtractionService

**Files:**
- Create: `src/main/java/com/tung/receipt_extractor/receipt/ReceiptData.java`
- Create: `src/main/java/com/tung/receipt_extractor/receipt/ReceiptAiConfig.java`
- Create: `src/main/java/com/tung/receipt_extractor/receipt/ReceiptExtractionService.java`
- Test: `src/test/java/com/tung/receipt_extractor/receipt/ReceiptExtractionServiceTest.java`

**Interfaces:**
- Consumes: the auto-configured `ChatClient.Builder` bean from Task 1.
- Produces:
  - `public record ReceiptData(String bankSource, Long amount, String message)` in `com.tung.receipt_extractor.receipt`
  - `public class ReceiptExtractionService` with constructor `ReceiptExtractionService(ChatClient chatClient)` and method `ReceiptData extract(byte[] imageBytes, String mimeType)` (no checked exceptions; runtime failures propagate)
  - a `ChatClient` bean with the receipt system prompt (from `ReceiptAiConfig`)

The old `com.tung.receipt_extractor.ocr.ReceiptExtractionService` still exists until Task 3; the two do not clash because they are in different packages.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/tung/receipt_extractor/receipt/ReceiptExtractionServiceTest.java`:

```java
package com.tung.receipt_extractor.receipt;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiptExtractionServiceTest {

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final ReceiptExtractionService service = new ReceiptExtractionService(chatClient);

    @Test
    void returnsReceiptDataParsedByTheModel() {
        ReceiptData expected = new ReceiptData("Techcombank", 50000L, "NGUYEN VAN A chuyen tien");
        when(chatClient.prompt().user(anyUserSpecConsumer()).call().entity(ReceiptData.class))
                .thenReturn(expected);

        ReceiptData actual = service.extract("fake-image-bytes".getBytes(), "image/jpeg");

        assertEquals(expected, actual);
    }

    @Test
    void attachesTheImageWithItsMimeTypeToTheUserMessage() {
        when(chatClient.prompt().user(anyUserSpecConsumer()).call().entity(ReceiptData.class))
                .thenReturn(new ReceiptData(null, null, null));

        service.extract("fake-image-bytes".getBytes(), "image/png");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(chatClient.prompt()).user(captor.capture());
        ChatClient.PromptUserSpec userSpec = mock(ChatClient.PromptUserSpec.class, RETURNS_SELF);
        captor.getValue().accept(userSpec);
        verify(userSpec).media(eq(MimeTypeUtils.IMAGE_PNG), any(ByteArrayResource.class));
    }

    @Test
    void propagatesFailuresFromTheModelCall() {
        when(chatClient.prompt().user(anyUserSpecConsumer()).call().entity(ReceiptData.class))
                .thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class,
                () -> service.extract("fake-image-bytes".getBytes(), "image/jpeg"));
    }

    @SuppressWarnings("unchecked")
    private static Consumer<ChatClient.PromptUserSpec> anyUserSpecConsumer() {
        return any(Consumer.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.tung.receipt_extractor.receipt.ReceiptExtractionServiceTest"`
Expected: FAIL to compile — `ReceiptData` and `ReceiptExtractionService` (in `receipt`) do not exist.

- [ ] **Step 3: Create `ReceiptData`**

`src/main/java/com/tung/receipt_extractor/receipt/ReceiptData.java`:

```java
package com.tung.receipt_extractor.receipt;

public record ReceiptData(String bankSource, Long amount, String message) {
}
```

- [ ] **Step 4: Create `ReceiptAiConfig` with the system prompt**

`src/main/java/com/tung/receipt_extractor/receipt/ReceiptAiConfig.java`:

```java
package com.tung.receipt_extractor.receipt;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReceiptAiConfig {

    static final String SYSTEM_PROMPT = """
            You read photos and screenshots of Vietnamese bank-transfer receipts and extract data from them.
            Return only these fields:
            - bankSource: the name of the bank or e-wallet the money was sent from (for example Techcombank, Vietcombank, Zalopay). Null if unknown.
            - amount: the transferred amount as a positive integer in VND, with no thousands separators and no currency symbol. Null if unknown.
            - message: the transfer description or note, copied exactly as written on the receipt. Null if there is none.
            Never guess. If a field is not clearly visible, return null for it.
            """;

    @Bean
    ChatClient receiptChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM_PROMPT).build();
    }
}
```

- [ ] **Step 5: Create `ReceiptExtractionService`**

`src/main/java/com/tung/receipt_extractor/receipt/ReceiptExtractionService.java`:

```java
package com.tung.receipt_extractor.receipt;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

@Service("geminiReceiptExtractionService") // temporary name: the old ocr.ReceiptExtractionService bean still exists until Task 3
public class ReceiptExtractionService {

    private static final String USER_PROMPT = "Extract the data from this receipt image.";

    private final ChatClient chatClient;

    public ReceiptExtractionService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ReceiptData extract(byte[] imageBytes, String mimeType) {
        return chatClient.prompt()
                .user(user -> user
                        .text(USER_PROMPT)
                        .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes)))
                .call()
                .entity(ReceiptData.class);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests "com.tung.receipt_extractor.receipt.ReceiptExtractionServiceTest"`
Expected: PASS (3 tests). If a `ChatClient` method signature differs in Spring AI 2.0 (for example `media(...)`), adjust the service and the test together to the real API, keeping the same behavior.

- [ ] **Step 7: Verify the whole context still starts**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ReceiptExtractorApplicationTests"`
Expected: PASS. (The explicit bean name in Step 5 avoids a clash with the old `ocr.ReceiptExtractionService` bean; Task 3 removes the old class and restores the plain `@Service`.)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/receipt src/test/java/com/tung/receipt_extractor/receipt
git commit -m "feat: add Gemini-backed ReceiptExtractionService returning ReceiptData

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Switch the callers to the new service and delete the OCR code

**Files:**
- Delete: `src/main/java/com/tung/receipt_extractor/ocr/` (all files)
- Delete: `src/test/java/com/tung/receipt_extractor/ocr/` (all files)
- Delete: `src/main/resources/tessdata/`
- Create: `src/main/java/com/tung/receipt_extractor/receipt/ReceiptController.java`
- Create: `src/test/java/com/tung/receipt_extractor/receipt/ReceiptControllerTest.java`
- Modify: `src/main/java/com/tung/receipt_extractor/telegram/TelegramWebhookController.java`
- Modify: `src/test/java/com/tung/receipt_extractor/telegram/TelegramWebhookControllerTest.java`
- Modify: `build.gradle` (remove tess4j)
- Modify: `src/main/resources/application.yml` (remove `ocr:` block)

**Interfaces:**
- Consumes: `ReceiptExtractionService.extract(byte[] imageBytes, String mimeType)` and `ReceiptData` from Task 2; `SheetRowAppender.insertRow(Long amount, String message)` (unchanged).
- Produces: `POST /api/ocr/extract` returning `ReceiptData` JSON; webhook flow calling the new service with `"image/jpeg"`.

The sample images in `src/test/resources/ocr/` stay; Task 5 uses them for the manual check.

- [ ] **Step 1: Delete the OCR code, its tests and the tessdata**

```bash
git rm -r src/main/java/com/tung/receipt_extractor/ocr src/test/java/com/tung/receipt_extractor/ocr/*.java src/main/resources/tessdata
```

- [ ] **Step 2: Write the new controller test**

Create `src/test/java/com/tung/receipt_extractor/receipt/ReceiptControllerTest.java`:

```java
package com.tung.receipt_extractor.receipt;

import com.tung.receipt_extractor.sheets.SheetRowAppender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReceiptController.class)
class ReceiptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReceiptExtractionService receiptExtractionService;

    @MockitoBean
    private SheetRowAppender sheetRowAppender;

    @Test
    void returnsExtractedDataForValidImageUpload() throws Exception {
        when(receiptExtractionService.extract(any(), eq("image/png")))
                .thenReturn(new ReceiptData("Zalopay", 125000L, "chuyen tien"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.bankSource").value("Zalopay"))
                .andExpect(jsonPath("$.amount").value(125000))
                .andExpect(jsonPath("$.message").value("chuyen tien"));
    }

    @Test
    void invokesSheetRowAppenderWithExtractedFields() throws Exception {
        when(receiptExtractionService.extract(any(), any()))
                .thenReturn(new ReceiptData("Techcombank", 50000L, "NGUYEN VAN A chuyen tien"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk());

        verify(sheetRowAppender).insertRow(50000L, "NGUYEN VAN A chuyen tien");
    }

    @Test
    void returns200EvenWhenSheetRowAppenderThrows() throws Exception {
        when(receiptExtractionService.extract(any(), any()))
                .thenReturn(new ReceiptData("Zalopay", 2000L, ""));
        doThrow(new RuntimeException("boom")).when(sheetRowAppender).insertRow(any(), any());

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
        when(receiptExtractionService.extract(any(), any())).thenThrow(new IllegalStateException("boom"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("failed to extract data from image"));
    }
}
```

- [ ] **Step 3: Update `TelegramWebhookControllerTest`**

In `src/test/java/com/tung/receipt_extractor/telegram/TelegramWebhookControllerTest.java` make these edits:

1. Replace the imports `com.tung.receipt_extractor.ocr.OcrResponse` and `com.tung.receipt_extractor.ocr.ReceiptExtractionService` with:
   ```java
   import com.tung.receipt_extractor.receipt.ReceiptData;
   import com.tung.receipt_extractor.receipt.ReceiptExtractionService;
   ```
   and delete `import net.sourceforge.tess4j.TesseractException;`.
2. Replace every `receiptExtractionService.extract(any())` with `receiptExtractionService.extract(any(), any())` (also inside `verify(receiptExtractionService, never()).extract(any())` → `.extract(any(), any())`).
3. Replace `new OcrResponse("raw text", "Techcombank", 50000L, "extracted message")` with `new ReceiptData("Techcombank", 50000L, "extracted message")`, and `new OcrResponse("raw text", null, null, null)` with `new ReceiptData(null, null, null)`.
4. Rename the test `tesseractExceptionDuringProcessingRepliesWithGenericErrorAndStillReturns200` to `extractionFailureDuringProcessingRepliesWithGenericErrorAndStillReturns200` and change `new TesseractException("boom")` to `new IllegalStateException("boom")`.
5. In the first successful-photo test, add a check that the webhook passes JPEG: after the existing `verify`s, add
   ```java
   verify(receiptExtractionService).extract(any(), eq("image/jpeg"));
   ```
   (`eq` is already imported).

- [ ] **Step 4: Run the new tests to verify they fail**

Run: `./gradlew test --tests "com.tung.receipt_extractor.receipt.ReceiptControllerTest" --tests "com.tung.receipt_extractor.telegram.TelegramWebhookControllerTest"`
Expected: FAIL to compile — `ReceiptController` does not exist and the webhook controller still uses the old service.

- [ ] **Step 5: Create `ReceiptController`**

`src/main/java/com/tung/receipt_extractor/receipt/ReceiptController.java`:

```java
package com.tung.receipt_extractor.receipt;

import com.tung.receipt_extractor.sheets.SheetRowAppender;
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
public class ReceiptController {

    private static final Logger log = LoggerFactory.getLogger(ReceiptController.class);
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final ReceiptExtractionService receiptExtractionService;
    private final SheetRowAppender sheetRowAppender;

    public ReceiptController(ReceiptExtractionService receiptExtractionService, SheetRowAppender sheetRowAppender) {
        this.receiptExtractionService = receiptExtractionService;
        this.sheetRowAppender = sheetRowAppender;
    }

    @PostMapping(value = "/api/ocr/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> extract(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        if (file.getContentType() == null || !SUPPORTED_CONTENT_TYPES.contains(file.getContentType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported file type"));
        }
        try {
            ReceiptData data = receiptExtractionService.extract(file.getBytes(), file.getContentType());
            try {
                sheetRowAppender.insertRow(data.amount(), data.message());
            } catch (Exception e) {
                log.error("Sheets append failed; returning extraction response anyway", e);
            }
            return ResponseEntity.ok(data);
        } catch (IOException | RuntimeException e) {
            log.error("Receipt extraction failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to extract data from image"));
        }
    }
}
```

- [ ] **Step 6: Update `TelegramWebhookController`**

In `src/main/java/com/tung/receipt_extractor/telegram/TelegramWebhookController.java`:

1. Replace the imports `com.tung.receipt_extractor.ocr.OcrResponse` / `com.tung.receipt_extractor.ocr.ReceiptExtractionService` with `com.tung.receipt_extractor.receipt.ReceiptData` / `com.tung.receipt_extractor.receipt.ReceiptExtractionService`.
2. Delete the import `org.springframework.aot.hint.annotation.RegisterReflectionForBinding` and the line `@RegisterReflectionForBinding({TelegramUpdate.class, ...})` above the class.
3. Replace
   ```java
   OcrResponse extracted = receiptExtractionService.extract(imageBytes);
   ```
   with
   ```java
   ReceiptData extracted = receiptExtractionService.extract(imageBytes, "image/jpeg");
   ```

- [ ] **Step 7: Remove Tess4J and the `ocr` config**

In `build.gradle` delete the line `implementation 'net.sourceforge.tess4j:tess4j:5.20.0'`. In `src/main/resources/application.yml` delete the whole `ocr:` block (`tessdata-path` and `languages`).

- [ ] **Step 8: Remove the temporary bean name**

In `src/main/java/com/tung/receipt_extractor/receipt/ReceiptExtractionService.java`, change `@Service("geminiReceiptExtractionService") // temporary name: ...` back to plain `@Service`.

- [ ] **Step 9: Run the full test suite**

Run: `./gradlew test`
Expected: PASS for all tests. `grep -rn "tess4j\|Tesseract\|ocr\." src` should find no code references (only the `/api/ocr/extract` path string and the `src/test/resources/ocr` folder).

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor: replace Tesseract OCR with Gemini extraction in controllers

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Remove native-image support and update the Dockerfile and docs

**Files:**
- Modify: `build.gradle`
- Delete: `src/main/resources/META-INF/native-image/`
- Delete: `scripts/regenerate-native-image-config.sh`
- Modify: `Dockerfile`
- Modify: `deploy/sheets-env.yaml.example`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md`

**Interfaces:**
- Consumes: the Spring Boot `bootJar` task.
- Produces: `build/libs/app.jar`, run by the Docker image with `java -jar`.

- [ ] **Step 1: Clean up `build.gradle`**

Remove the line `id 'org.graalvm.buildtools.native' version '1.1.11'` from `plugins { ... }`. Then add at the end of the file:

```groovy
tasks.named('bootJar') {
	archiveFileName = 'app.jar'
}
```

Keep the `JavaCompile` `options.encoding = 'UTF-8'` block.

- [ ] **Step 2: Delete the native-image files**

```bash
git rm -r src/main/resources/META-INF/native-image scripts/regenerate-native-image-config.sh
```

If `scripts/` is now empty, `git` drops it automatically.

- [ ] **Step 3: Rewrite the `Dockerfile`**

Replace its entire contents with:

```dockerfile
FROM eclipse-temurin:25-jdk AS build
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
WORKDIR /app
COPY --from=build /app/build/libs/app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 4: Add the new env var to the deploy example**

Append to `deploy/sheets-env.yaml.example`:

```yaml
GOOGLE_API_KEY: "your-google-ai-studio-api-key"
```

- [ ] **Step 5: Verify the jar builds and runs its tests**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL and `build/libs/app.jar` exists.

If Docker is available locally, also run `docker build -t receipt-extractor .` and expect it to succeed. If Docker is not available, say so in the report instead of skipping silently.

- [ ] **Step 6: Update `CLAUDE.md`**

- In "Project status", say the app extracts receipt data (bank source, amount, message) from images using Gemini through Spring AI.
- Replace the "OCR text extraction" section with a "Receipt extraction (Spring AI + Gemini)" section: `POST /api/ocr/extract` still takes multipart `file` (`image/jpeg` or `image/png`) and now returns `{"bankSource": ..., "amount": ..., "message": ...}`; extraction is done by `ReceiptExtractionService` in package `com.tung.receipt_extractor.receipt` using a Spring AI `ChatClient` with the Google GenAI starter; config is `spring.ai.google.genai.api-key` bound from `GOOGLE_API_KEY` (the app fails to start if unset; tests use a dummy value) and `GEMINI_MODEL` (default `gemini-2.5-flash`); receipt images are sent to Google's Gemini API. Delete the Tess4J/Tesseract/Leptonica prerequisite text and the `ocr.*` property description.
- In "Google Sheets export" and "Telegram webhook integration", change "OCR'd with the same pipeline" wording to "extracted with the same `ReceiptExtractionService`", and add `GOOGLE_API_KEY` to the list of required environment variables where `deploy/sheets-env.yaml` is mentioned.
- In "Deploying to Google Cloud", replace the GraalVM native-image paragraphs (native build stage, memory/timeout advice, reachability metadata, Sheets request-class reflection) with: the multi-stage `Dockerfile` builds `bootJar` on `eclipse-temurin:25-jdk` and runs `java -jar` on `eclipse-temurin:25-jre`; no native libraries are needed. Keep the paragraph about forcing UTF-8 (`options.encoding = 'UTF-8'` and `LANG`/`LC_ALL`), rewording it so it no longer says "native image". Keep the `--no-allow-unauthenticated`, secrets, and Telegram deployment-gap notes.
- In "Stack and architecture", remove nothing else; the Spring Boot / Java versions are unchanged.

- [ ] **Step 7: Update the deployment spec**

In `docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md`, run `grep -n -i "native\|graal\|machine-type\|timeout\|tesseract\|tessdata" docs/superpowers/specs/2026-09-13-google-cloud-deployment-design.md` and update each match: native-image build and larger-machine/timeout advice are removed; Tesseract/tessdata references are removed; `GOOGLE_API_KEY` is added to the list of environment variables passed through `--env-vars-file`. Leave the older GraalVM spec and plan files untouched as history.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "build: drop GraalVM native image, use plain JVM jar Docker build

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Manual verification against the real Gemini API

**Files:** none changed (unless the check reveals a prompt problem).

This task needs the user's real `GOOGLE_API_KEY` and Google Sheets credentials, so the user runs the commands. Ask the user to run them with the `!` prefix, or to paste the output.

- [ ] **Step 1: Start the app with a real key**

The app also needs the Sheets and Telegram variables; reuse the user's existing values. Run:

```bash
set -a; source <(sed 's/: */=/' deploy/sheets-env.yaml | tr -d '"'); set +a
GOOGLE_API_KEY=<real key> ./gradlew bootRun
```

Expected: the app starts on port 8080 with no Spring AI errors.

- [ ] **Step 2: Send the sample receipts**

```bash
curl -s -F "file=@src/test/resources/ocr/sample-receipt.jpg;type=image/jpeg" localhost:8080/api/ocr/extract
curl -s -F "file=@src/test/resources/ocr/sample-receipt.png;type=image/png" localhost:8080/api/ocr/extract
```

Expected: JSON `{"bankSource": "...", "amount": <integer>, "message": "..."}` with the amount and message matching the receipt, Vietnamese text intact, and a new row in the Google Sheet. If a field is wrong or missing, adjust `SYSTEM_PROMPT` in `ReceiptAiConfig` and rerun.

- [ ] **Step 3: Test the Telegram flow**

With the webhook reachable, send a receipt photo to the bot. Expected: a "Recorded: <bank>, <amount>, <message>" reply and a Sheet row. Then send a photo with a caption and confirm the caption becomes the row's description.

- [ ] **Step 4: Report and open the PR**

Report the results honestly (including any wrong extractions). Per the project's workflow, commit any prompt fixes, push the branch, and open a PR against `main` whose description ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
