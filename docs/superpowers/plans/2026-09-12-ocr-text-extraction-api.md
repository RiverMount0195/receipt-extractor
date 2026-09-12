# OCR Text Extraction API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `POST /api/ocr/extract` endpoint that accepts an uploaded image (JPEG/PNG), runs it through Tesseract OCR configured for Vietnamese + English, and returns the extracted text as JSON.

**Architecture:** A `TesseractConfig` bean builds a single Tess4J `ITesseract` at startup from externalized properties. `OcrService` wraps it with a simple byte-array-in/`String`-out method. `OcrController` handles multipart upload validation and HTTP status/error mapping, delegating OCR work to `OcrService`. No persistence, no async processing — one synchronous request/response cycle.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC via `spring-boot-starter-webmvc`), Gradle (Groovy DSL), JUnit 5, Tess4J 5.20.0 (`net.sourceforge.tess4j:tess4j`) wrapping native Tesseract/Leptonica.

**Spec:** [docs/superpowers/specs/2026-09-12-ocr-text-extraction-api-design.md](../specs/2026-09-12-ocr-text-extraction-api-design.md)

## Global Constraints

- Endpoint: `POST /api/ocr/extract`, multipart/form-data, file field name `file`.
- Accepted content types: `image/jpeg`, `image/png`. Anything else → `400`.
- Empty/missing file → `400`.
- Success response: `200 OK`, body `{"text": "<extracted text>"}`.
- Error response body shape: `{"error": "<message>"}`. `400` bodies: `"file is required"` or `"unsupported file type"`. OCR/internal failures → `500` with a generic message; full exception detail is logged server-side only, never returned to the client.
- OCR languages: `vie+eng`, loaded together in one pass.
- Config keys (externalized, not hardcoded): `ocr.tessdata-path` (default `src/main/resources/tessdata`), `ocr.languages` (default `vie+eng`).
- Dependency: `net.sourceforge.tess4j:tess4j:5.20.0`.
- Base package: `com.tung.receipt_extractor`; this feature lives under `com.tung.receipt_extractor.ocr`.
- Use `./gradlew`, never a system-installed Gradle.
- Out of scope (do not build): structured receipt field parsing, async/queued processing, authentication, non-image (e.g. PDF) input.

## Prerequisites (manual, one-time, not a git-tracked task)

Tess4J loads Tesseract via JNA against native shared libraries — on Linux these must already be resolvable on the system, they are not bundled in the Tess4J jar. This dev machine already has `tesseract-libs` installed (`libtesseract.so.5.5`, `libleptonica.so.6` are present per `ldconfig -p`), which may be sufficient. If Task 2's or Task 4's tests fail with `UnsatisfiedLinkError: Unable to load library 'tesseract'`, install the `-devel` packages, which add the unversioned `.so` symlinks JNA looks up by default:

```bash
# Fedora
sudo dnf install tesseract-devel leptonica-devel
# Debian/Ubuntu
sudo apt-get install libtesseract-dev libleptonica-dev
```

## File Structure

- `build.gradle` — modify: add the Tess4J dependency.
- `src/main/resources/tessdata/eng.traineddata`, `src/main/resources/tessdata/vie.traineddata` — create: downloaded Tesseract language model binaries.
- `src/main/resources/application.properties` — modify: add `ocr.tessdata-path`, `ocr.languages`.
- `src/main/java/com/tung/receipt_extractor/ocr/TesseractConfig.java` — create: builds the `ITesseract` bean.
- `src/main/java/com/tung/receipt_extractor/ocr/OcrService.java` — create: byte[] → String OCR wrapper.
- `src/main/java/com/tung/receipt_extractor/ocr/OcrResponse.java` — create: success response DTO.
- `src/main/java/com/tung/receipt_extractor/ocr/OcrController.java` — create: HTTP endpoint, validation, error mapping.
- `src/test/resources/ocr/sample-receipt.png` — create: synthetic Vietnamese+English test fixture image.
- `src/test/java/com/tung/receipt_extractor/ocr/OcrServiceTest.java` — create: real-Tesseract unit test against the fixture.
- `src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java` — create: HTTP-contract test with a mocked `OcrService`.
- `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java` — create: full-stack smoke test.

---

### Task 1: Add Tess4J dependency and bundle tessdata language files

**Files:**
- Modify: `build.gradle`
- Create: `src/main/resources/tessdata/eng.traineddata`
- Create: `src/main/resources/tessdata/vie.traineddata`

**Interfaces:**
- Produces: the `net.sourceforge.tess4j:tess4j:5.20.0` dependency on the compile/runtime classpath (consumed by Task 2's `TesseractConfig`), and two language model files at `src/main/resources/tessdata/{eng,vie}.traineddata` (consumed by Task 2's `OcrService`/`TesseractConfig` at the `ocr.tessdata-path` default).

- [ ] **Step 1: Add the Tess4J dependency**

In `build.gradle`, add to the `dependencies` block:

```groovy
implementation 'net.sourceforge.tess4j:tess4j:5.20.0'
```

- [ ] **Step 2: Verify the dependency resolves**

Run: `./gradlew build -x test`
Expected: `BUILD SUCCESSFUL` (this confirms Gradle can fetch `tess4j` and its transitive dependencies from Maven Central).

- [ ] **Step 3: Download the Tesseract language model files**

```bash
mkdir -p src/main/resources/tessdata
curl -sSL -o src/main/resources/tessdata/eng.traineddata https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata
curl -sSL -o src/main/resources/tessdata/vie.traineddata https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/vie.traineddata
```

These come from the official `tessdata_fast` model repository (smaller/faster models, the recommended default for Tess4J).

- [ ] **Step 4: Verify the downloads are complete**

```bash
stat -c%s src/main/resources/tessdata/eng.traineddata
stat -c%s src/main/resources/tessdata/vie.traineddata
```

Expected: `4113088` for `eng.traineddata` and `531275` for `vie.traineddata`. If either size doesn't match, the download was truncated or failed silently — re-run Step 3 for that file.

- [ ] **Step 5: Commit**

```bash
git add build.gradle src/main/resources/tessdata/eng.traineddata src/main/resources/tessdata/vie.traineddata
git commit -m "Add Tess4J dependency and bundle eng/vie tessdata models"
```

---

### Task 2: TesseractConfig + OcrService (real-OCR unit test)

**Files:**
- Create: `src/test/resources/ocr/sample-receipt.png`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/java/com/tung/receipt_extractor/ocr/TesseractConfig.java`
- Create: `src/main/java/com/tung/receipt_extractor/ocr/OcrService.java`
- Test: `src/test/java/com/tung/receipt_extractor/ocr/OcrServiceTest.java`

**Interfaces:**
- Consumes: `ocr.tessdata-path` / `ocr.languages` properties (Task 1's bundled tessdata files); `net.sourceforge.tess4j.ITesseract`/`Tesseract`/`TesseractException` (Task 1's dependency).
- Produces: `OcrService.extractText(byte[] imageBytes) throws TesseractException, IOException` — returns the OCR'd text. Consumed by Task 3's `OcrController`. `TesseractConfig` produces an `ITesseract` Spring bean, consumed by `OcrService`'s constructor and by Task 4's full Spring context.

- [ ] **Step 1: Generate the test fixture image**

```bash
mkdir -p src/test/resources/ocr
FONT=$(fc-match --format=%{file} "Noto Sans")
magick -size 700x300 xc:white -font "$FONT" -pointsize 30 -fill black \
  -gravity NorthWest \
  -annotate +30+30  'CỬA HÀNG TẠP HÓA SỐ 1' \
  -annotate +30+90  'Hóa đơn bán lẻ' \
  -annotate +30+150 'Tổng cộng: 125.000 VND' \
  -annotate +30+210 'Cảm ơn quý khách - Thank you' \
  src/test/resources/ocr/sample-receipt.png
```

This renders a clean synthetic receipt with both Vietnamese diacritics and English text, verified legible by visual inspection during planning.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/tung/receipt_extractor/ocr/OcrServiceTest.java`:

```java
package com.tung.receipt_extractor.ocr;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrServiceTest {

    @Test
    void extractsVietnameseAndEnglishTextFromImage() throws Exception {
        ITesseract tesseract = new Tesseract();
        tesseract.setDatapath("src/main/resources/tessdata");
        tesseract.setLanguage("vie+eng");
        OcrService ocrService = new OcrService(tesseract);

        byte[] imageBytes = readFixture();

        String extractedText = ocrService.extractText(imageBytes);

        assertTrue(extractedText.contains("VND"),
                "expected extracted text to contain VND, got: " + extractedText);
        assertTrue(extractedText.contains("Thank you"),
                "expected extracted text to contain Thank you, got: " + extractedText);
    }

    private byte[] readFixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/ocr/sample-receipt.png")) {
            return in.readAllBytes();
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.OcrServiceTest"`
Expected: FAIL with a compile error (`OcrService` doesn't exist yet).

- [ ] **Step 4: Add the config properties**

In `src/main/resources/application.properties`, add:

```properties
ocr.tessdata-path=src/main/resources/tessdata
ocr.languages=vie+eng
```

- [ ] **Step 5: Write `TesseractConfig`**

Create `src/main/java/com/tung/receipt_extractor/ocr/TesseractConfig.java`:

```java
package com.tung.receipt_extractor.ocr;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TesseractConfig {

    @Bean
    public ITesseract tesseract(
            @Value("${ocr.tessdata-path}") String tessdataPath,
            @Value("${ocr.languages}") String languages) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage(languages);
        return tesseract;
    }
}
```

- [ ] **Step 6: Write the minimal `OcrService` implementation**

Create `src/main/java/com/tung/receipt_extractor/ocr/OcrService.java`:

```java
package com.tung.receipt_extractor.ocr;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@Service
public class OcrService {

    private final ITesseract tesseract;

    public OcrService(ITesseract tesseract) {
        this.tesseract = tesseract;
    }

    public String extractText(byte[] imageBytes) throws TesseractException, IOException {
        File tempFile = Files.createTempFile("ocr-", ".tmp").toFile();
        try {
            Files.write(tempFile.toPath(), imageBytes);
            return tesseract.doOCR(tempFile);
        } finally {
            tempFile.delete();
        }
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.OcrServiceTest"`
Expected: PASS.

If it fails with `UnsatisfiedLinkError`, see the Prerequisites section above. If it fails because the extracted text doesn't contain the expected substrings, print `extractedText` (the assertion message already includes it in the failure output), inspect what Tesseract actually read, and adjust the asserted substrings to match real, stable OCR output — OCR text matching should target substrings that are actually present, not force the fixture to match a guess made before running the model.

- [ ] **Step 8: Commit**

```bash
git add src/test/resources/ocr/sample-receipt.png \
        src/main/resources/application.properties \
        src/main/java/com/tung/receipt_extractor/ocr/TesseractConfig.java \
        src/main/java/com/tung/receipt_extractor/ocr/OcrService.java \
        src/test/java/com/tung/receipt_extractor/ocr/OcrServiceTest.java
git commit -m "Add OcrService and TesseractConfig with real-OCR unit test"
```

---

### Task 3: OcrController (HTTP contract, mocked service)

**Files:**
- Create: `src/main/java/com/tung/receipt_extractor/ocr/OcrResponse.java`
- Create: `src/main/java/com/tung/receipt_extractor/ocr/OcrController.java`
- Test: `src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java`

**Interfaces:**
- Consumes: `OcrService.extractText(byte[]) throws TesseractException, IOException` (Task 2).
- Produces: the `POST /api/ocr/extract` HTTP contract (200/400/500 as defined in Global Constraints) — this is the feature's public surface, exercised end-to-end in Task 4.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java`:

```java
package com.tung.receipt_extractor.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OcrController.class)
class OcrControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OcrService ocrService;

    @Test
    void returnsExtractedTextForValidImageUpload() throws Exception {
        when(ocrService.extractText(any())).thenReturn("Tổng cộng: 125.000 VND");

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Tổng cộng: 125.000 VND"));
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.OcrControllerTest"`
Expected: FAIL with a compile error (`OcrController`/`OcrResponse` don't exist yet).

- [ ] **Step 3: Write `OcrResponse`**

Create `src/main/java/com/tung/receipt_extractor/ocr/OcrResponse.java`:

```java
package com.tung.receipt_extractor.ocr;

public record OcrResponse(String text) {
}
```

- [ ] **Step 4: Write `OcrController`**

Create `src/main/java/com/tung/receipt_extractor/ocr/OcrController.java`:

```java
package com.tung.receipt_extractor.ocr;

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

    private final OcrService ocrService;

    public OcrController(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @PostMapping(value = "/api/ocr/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> extractText(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        if (!SUPPORTED_CONTENT_TYPES.contains(file.getContentType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported file type"));
        }
        try {
            String text = ocrService.extractText(file.getBytes());
            return ResponseEntity.ok(new OcrResponse(text));
        } catch (TesseractException | IOException e) {
            log.error("OCR extraction failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to extract text from image"));
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.OcrControllerTest"`
Expected: PASS (all 3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/ocr/OcrResponse.java \
        src/main/java/com/tung/receipt_extractor/ocr/OcrController.java \
        src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java
git commit -m "Add OcrController with request validation and error mapping"
```

---

### Task 4: End-to-end smoke test

**Files:**
- Test: `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java`

**Interfaces:**
- Consumes: the full Spring context wired in Tasks 1-3 (`TesseractConfig` → `OcrService` → `OcrController`) and the fixture image from Task 2 (`src/test/resources/ocr/sample-receipt.png`).
- Produces: nothing consumed by later tasks — this is the terminal verification that the whole slice works together over real HTTP with real OCR.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java`:

```java
package com.tung.receipt_extractor.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OcrEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void extractsTextFromRealReceiptImageEndToEnd() throws Exception {
        byte[] imageBytes;
        try (InputStream in = getClass().getResourceAsStream("/ocr/sample-receipt.png")) {
            imageBytes = in.readAllBytes();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample-receipt.png", "image/png", imageBytes);

        MvcResult result = mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("VND"),
                "expected response body to contain VND, got: " + responseBody);
    }
}
```

- [ ] **Step 2: Run test to verify it fails or passes for the wrong reason first**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.OcrEndToEndTest"`
Expected: since `OcrController`/`OcrService`/`TesseractConfig` already exist from Tasks 2-3, this test should compile immediately — run it now to confirm the full Spring context loads and the real endpoint responds correctly. There is no separate "minimal implementation" step here: this task validates existing wiring rather than introducing new production code.
Expected result: PASS. If it fails, the failure points to a wiring gap between Tasks 1-3 (e.g. a bean not found, property not bound) that needs fixing before moving on.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests (including `ReceiptExtractorApplicationTests`, `OcrServiceTest`, `OcrControllerTest`, `OcrEndToEndTest`) pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java
git commit -m "Add end-to-end smoke test for OCR extraction endpoint"
```

## Manual Verification (optional, after all tasks)

```bash
./gradlew bootRun
# in another terminal:
curl -F "file=@src/test/resources/ocr/sample-receipt.png;type=image/png" http://localhost:8080/api/ocr/extract
```
Expected: a JSON response like `{"text":"CỬA HÀNG TẠP HÓA SỐ 1\nHóa đơn bán lẻ\n\nTổng cộng: 125.000 VND\nCảm ơn quý khách - Thank you\n"}`.
