# Google Sheets Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After each OCR extraction, append a row (`Bank source, Amount, Message, Timestamp`) summarizing the result to a configured Google Sheet, using the user's personal Google OAuth2 credentials — as a best-effort side effect that never affects the OCR API's response.

**Architecture:** A new `com.tung.receipt_extractor.sheets` package, parallel to `ocr`. `SheetsConfig` loads six secret values (client ID/secret, refresh/access tokens, spreadsheet ID, sheet name) from an external, gitignored properties file and builds a `Sheets` API client bean plus a `SheetsProperties` bean. `SheetRowAppender` takes the detected `bankSource`/`amount`/`message`, builds a row ending in a generated timestamp, and appends it via the Sheets API — swallowing any failure internally so a Sheets outage never turns a successful OCR extraction into a failed HTTP response. `OcrController` calls it synchronously right before returning.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Gradle (Groovy DSL), JUnit 5 + Mockito, `com.google.apis:google-api-services-sheets:v4-rev20250603-2.0.0`, `com.google.auth:google-auth-library-oauth2-http:1.48.0`.

**Spec:** [docs/superpowers/specs/2026-09-13-google-sheets-export-design.md](../specs/2026-09-13-google-sheets-export-design.md)

## Global Constraints

- New package: `com.tung.receipt_extractor.sheets`, parallel to the existing `ocr` package.
- Row appended per extraction, in this exact order: `[bankSource, amount, message, timestamp]` — matching the target sheet's actual columns (Bank source, Amount, Message, Timestamp). `amount` is written as `""` when `null` (no amount detected). `timestamp` is `Instant.now().toString()` (ISO-8601), generated at append time.
- Secrets file: `config/sheets-credentials.properties` (gitignored), holding exactly six keys: `client-id`, `client-secret`, `refresh-token`, `access-token`, `spreadsheet-id`, `sheet-name`. No default/fallback value for any of these six keys may appear in any committed file (`application.properties`, Java source, etc.).
- One new non-secret property in `application.properties`: `sheets.credentials-path`, defaulting to `config/sheets-credentials.properties` (same externalization pattern as `ocr.tessdata-path`).
- A checked-in `config/sheets-credentials.properties.example` template documents the six keys with placeholder values (including placeholder `spreadsheet-id`/`sheet-name` — never the real ones).
- Sheets append is synchronous and best-effort: `SheetRowAppender.appendRow` catches any exception from the Sheets API call, logs it at `error` level including the spreadsheet ID and sheet name as context, but never logs `client-id`, `client-secret`, `refresh-token`, or `access-token`. Nothing propagates to `OcrController` — the OCR endpoint always returns its normal response regardless of Sheets outcome.
- New dependencies: `com.google.apis:google-api-services-sheets:v4-rev20250603-2.0.0`, `com.google.auth:google-auth-library-oauth2-http:1.48.0`.
- Base package: `com.tung.receipt_extractor`. Use `./gradlew`, never a system-installed Gradle.
- Out of scope (do not build): any endpoint/trigger to export outside the automatic post-extraction hook, multiple target spreadsheets per request, reading/updating existing rows, retrying failed appends, refresh-token rotation/expiry handling beyond what the Google auth library does automatically.

## Prerequisites (manual, one-time, not a git-tracked task)

Before `./gradlew bootRun` can actually append rows to a real spreadsheet, create `config/sheets-credentials.properties` (gitignored — this file is never committed) with real values, following the spec's "Obtaining the refresh token" steps:

```properties
client-id=<your OAuth client ID>
client-secret=<your OAuth client secret>
refresh-token=<your OAuth refresh token>
access-token=<your OAuth access token>
spreadsheet-id=1_d5IRYWyIN08vtk7F8uMe3iqKW5ExLotvylNM3I4mP4
sheet-name=Test
```

This is not required for any task's tests to pass — all tests in this plan use either mocked Sheets clients or a checked-in dummy fixture file, never the real credentials file.

## File Structure

- `build.gradle` — modify: add the two Google Sheets/auth dependencies.
- `src/main/java/com/tung/receipt_extractor/sheets/SheetsProperties.java` — create: `record(spreadsheetId, sheetName)`.
- `src/main/java/com/tung/receipt_extractor/sheets/SheetsConfig.java` — create: loads the credentials file, builds the `Sheets` client bean and `SheetsProperties` bean.
- `src/main/java/com/tung/receipt_extractor/sheets/SheetRowAppender.java` — create: appends one row per call, swallowing failures.
- `src/main/resources/application.properties` — modify: add `sheets.credentials-path`.
- `.gitignore` — modify: add `config/`.
- `config/sheets-credentials.properties.example` — create: placeholder template.
- `src/test/resources/sheets/test-sheets-credentials.properties` — create: dummy fixture, safe to commit (no real secrets).
- `src/test/java/com/tung/receipt_extractor/sheets/SheetsConfigTest.java` — create: verifies credential/bean loading against the fixture.
- `src/test/java/com/tung/receipt_extractor/sheets/SheetRowAppenderTest.java` — create: verifies row content/order and exception-swallowing against a mocked `Sheets` client.
- `src/test/java/com/tung/receipt_extractor/ReceiptExtractorApplicationTests.java` — modify: point `sheets.credentials-path` at the test fixture so the full context still loads without real secrets.
- `src/main/java/com/tung/receipt_extractor/ocr/OcrController.java` — modify: inject `SheetRowAppender`, call it before returning.
- `src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java` — modify: mock `SheetRowAppender`, add a test verifying it's invoked with the detected fields.
- `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java` — modify: point `sheets.credentials-path` at the test fixture, and mock `SheetRowAppender` so the real endpoint hit never makes a real Sheets API call.

---

### Task 1: Sheets credentials loading (`SheetsConfig` + `SheetsProperties`)

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/com/tung/receipt_extractor/sheets/SheetsProperties.java`
- Create: `src/main/java/com/tung/receipt_extractor/sheets/SheetsConfig.java`
- Modify: `src/main/resources/application.properties`
- Modify: `.gitignore`
- Create: `config/sheets-credentials.properties.example`
- Create: `src/test/resources/sheets/test-sheets-credentials.properties`
- Test: `src/test/java/com/tung/receipt_extractor/sheets/SheetsConfigTest.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ReceiptExtractorApplicationTests.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java`

**Interfaces:**
- Consumes: `sheets.credentials-path` property (new, this task).
- Produces: `SheetsProperties(String spreadsheetId, String sheetName)` (record) and a `Sheets` bean, both consumed by Task 2's `SheetRowAppender` constructor and Task 3's Spring wiring. `SheetsConfig.sheetsCredentials(String path)`, `.sheetsClient(Properties)`, `.sheetsProperties(Properties)` are called directly (no Spring context) by this task's own test.

Since `SheetsConfig` is a `@Configuration` class under the scanned base package, introducing it makes every `@SpringBootTest` in the app try to build its beans — including the two existing full-context tests (`ReceiptExtractorApplicationTests`, `OcrEndToEndTest`), which don't know about `sheets.credentials-path` yet. This task fixes both immediately so the full suite stays green.

- [ ] **Step 1: Add the Gradle dependencies**

In `build.gradle`, add to the `dependencies` block:

```groovy
implementation 'com.google.apis:google-api-services-sheets:v4-rev20250603-2.0.0'
implementation 'com.google.auth:google-auth-library-oauth2-http:1.48.0'
```

- [ ] **Step 2: Verify the dependencies resolve**

Run: `./gradlew build -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Create the test credentials fixture**

```bash
mkdir -p src/test/resources/sheets
```

Create `src/test/resources/sheets/test-sheets-credentials.properties`:

```properties
client-id=test-client-id
client-secret=test-client-secret
refresh-token=test-refresh-token
access-token=test-access-token
spreadsheet-id=test-spreadsheet-id
sheet-name=Test
```

- [ ] **Step 4: Write the failing test**

Create `src/test/java/com/tung/receipt_extractor/sheets/SheetsConfigTest.java`:

```java
package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SheetsConfigTest {

    private static final String TEST_CREDENTIALS_PATH = "src/test/resources/sheets/test-sheets-credentials.properties";

    @Test
    void loadsCredentialsFromPropertiesFile() throws Exception {
        SheetsConfig config = new SheetsConfig();

        Properties credentials = config.sheetsCredentials(TEST_CREDENTIALS_PATH);

        assertEquals("test-client-id", credentials.getProperty("client-id"));
        assertEquals("test-client-secret", credentials.getProperty("client-secret"));
        assertEquals("test-refresh-token", credentials.getProperty("refresh-token"));
        assertEquals("test-access-token", credentials.getProperty("access-token"));
        assertEquals("test-spreadsheet-id", credentials.getProperty("spreadsheet-id"));
        assertEquals("Test", credentials.getProperty("sheet-name"));
    }

    @Test
    void buildsSheetsClientFromCredentials() throws Exception {
        SheetsConfig config = new SheetsConfig();
        Properties credentials = config.sheetsCredentials(TEST_CREDENTIALS_PATH);

        Sheets sheetsClient = config.sheetsClient(credentials);

        assertNotNull(sheetsClient);
    }

    @Test
    void buildsSheetsPropertiesFromCredentials() throws Exception {
        SheetsConfig config = new SheetsConfig();
        Properties credentials = config.sheetsCredentials(TEST_CREDENTIALS_PATH);

        SheetsProperties sheetsProperties = config.sheetsProperties(credentials);

        assertEquals("test-spreadsheet-id", sheetsProperties.spreadsheetId());
        assertEquals("Test", sheetsProperties.sheetName());
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `./gradlew test --tests "com.tung.receipt_extractor.sheets.SheetsConfigTest"`
Expected: FAIL with a compile error (`SheetsConfig`/`SheetsProperties` don't exist yet).

- [ ] **Step 6: Write `SheetsProperties`**

Create `src/main/java/com/tung/receipt_extractor/sheets/SheetsProperties.java`:

```java
package com.tung.receipt_extractor.sheets;

public record SheetsProperties(String spreadsheetId, String sheetName) {
}
```

- [ ] **Step 7: Write `SheetsConfig`**

Create `src/main/java/com/tung/receipt_extractor/sheets/SheetsConfig.java`:

```java
package com.tung.receipt_extractor.sheets;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.UserCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Properties;

@Configuration
public class SheetsConfig {

    @Bean
    public Properties sheetsCredentials(@Value("${sheets.credentials-path}") String credentialsPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(credentialsPath))) {
            properties.load(in);
        }
        return properties;
    }

    @Bean
    public Sheets sheetsClient(Properties sheetsCredentials) throws GeneralSecurityException, IOException {
        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(sheetsCredentials.getProperty("client-id"))
                .setClientSecret(sheetsCredentials.getProperty("client-secret"))
                .setRefreshToken(sheetsCredentials.getProperty("refresh-token"))
                .setAccessToken(new AccessToken(sheetsCredentials.getProperty("access-token"), null))
                .build();

        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("receipt-extractor")
                .build();
    }

    @Bean
    public SheetsProperties sheetsProperties(Properties sheetsCredentials) {
        return new SheetsProperties(
                sheetsCredentials.getProperty("spreadsheet-id"),
                sheetsCredentials.getProperty("sheet-name"));
    }
}
```

- [ ] **Step 8: Add the `sheets.credentials-path` property**

In `src/main/resources/application.properties`, add:

```properties
sheets.credentials-path=config/sheets-credentials.properties
```

- [ ] **Step 9: Run the new test to verify it passes**

Run: `./gradlew test --tests "com.tung.receipt_extractor.sheets.SheetsConfigTest"`
Expected: PASS (all 3 tests).

- [ ] **Step 10: Fix the full-context tests before they break**

`SheetsConfig` is now on the classpath and will be picked up by component scanning in every `@SpringBootTest`. Both existing full-context tests need to point `sheets.credentials-path` at the test fixture instead of the (nonexistent, gitignored) real file.

In `src/test/java/com/tung/receipt_extractor/ReceiptExtractorApplicationTests.java`, replace the file with:

```java
package com.tung.receipt_extractor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "sheets.credentials-path=src/test/resources/sheets/test-sheets-credentials.properties")
class ReceiptExtractorApplicationTests {

	@Test
	void contextLoads() {
	}

}
```

Replace `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java` with:

```java
package com.tung.receipt_extractor.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sheets.credentials-path=src/test/resources/sheets/test-sheets-credentials.properties")
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

This task doesn't yet mock `SheetRowAppender` — nothing calls it yet, so the real `Sheets`/`SheetsProperties` beans are built (from dummy fixture values) but never invoked. Task 3 adds the mock once `OcrController` actually calls it.

- [ ] **Step 11: Run the full test suite to verify nothing broke**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (including `ReceiptExtractorApplicationTests` and `OcrEndToEndTest`, which now build the real `Sheets`/`SheetsProperties` beans from dummy fixture values but never call the API since nothing in `OcrController` invokes them yet).

- [ ] **Step 12: Add `.gitignore` entry and the example credentials template**

In `.gitignore`, add:

```
### Secrets ###
config/
```

Create `config/sheets-credentials.properties.example`:

```properties
client-id=your-oauth-client-id
client-secret=your-oauth-client-secret
refresh-token=your-oauth-refresh-token
access-token=your-oauth-access-token
spreadsheet-id=your-google-sheet-id
sheet-name=your-google-sheet-tab-name
```

- [ ] **Step 13: Commit**

```bash
git add build.gradle \
        src/main/resources/application.properties \
        src/main/java/com/tung/receipt_extractor/sheets/SheetsProperties.java \
        src/main/java/com/tung/receipt_extractor/sheets/SheetsConfig.java \
        src/test/resources/sheets/test-sheets-credentials.properties \
        src/test/java/com/tung/receipt_extractor/sheets/SheetsConfigTest.java \
        src/test/java/com/tung/receipt_extractor/ReceiptExtractorApplicationTests.java \
        src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java \
        .gitignore \
        config/sheets-credentials.properties.example
git commit -m "Add Sheets API credentials loading (SheetsConfig, SheetsProperties)"
```

---

### Task 2: `SheetRowAppender` (mocked-Sheets-client unit tests)

**Files:**
- Create: `src/main/java/com/tung/receipt_extractor/sheets/SheetRowAppender.java`
- Test: `src/test/java/com/tung/receipt_extractor/sheets/SheetRowAppenderTest.java`

**Interfaces:**
- Consumes: `Sheets` client and `SheetsProperties` (Task 1), both passed into the constructor.
- Produces: `SheetRowAppender.appendRow(String bankSource, Long amount, String message)` (void, never throws) — consumed by Task 3's `OcrController`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/tung/receipt_extractor/sheets/SheetRowAppenderTest.java`:

```java
package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SheetRowAppenderTest {

    private static final String SPREADSHEET_ID = "sheet-id-123";
    private static final String SHEET_NAME = "Test";

    @Test
    void appendsRowWithBankSourceAmountMessageAndTimestampInOrder() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Append append = mock(Sheets.Spreadsheets.Values.Append.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.values()).thenReturn(values);
        when(values.append(eq(SPREADSHEET_ID), eq(SHEET_NAME), any(ValueRange.class))).thenReturn(append);
        when(append.setValueInputOption("USER_ENTERED")).thenReturn(append);
        when(append.execute()).thenReturn(new AppendValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME));

        appender.appendRow("Vietcombank", 125000L, "chuyen tien");

        ArgumentCaptor<ValueRange> captor = ArgumentCaptor.forClass(ValueRange.class);
        verify(values).append(eq(SPREADSHEET_ID), eq(SHEET_NAME), captor.capture());

        List<Object> row = captor.getValue().getValues().get(0);
        assertEquals("Vietcombank", row.get(0));
        assertEquals(125000L, row.get(1));
        assertEquals("chuyen tien", row.get(2));
        assertDoesNotThrow(() -> Instant.parse((String) row.get(3)));
    }

    @Test
    void convertsNullAmountToEmptyStringInRow() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Append append = mock(Sheets.Spreadsheets.Values.Append.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.values()).thenReturn(values);
        when(values.append(eq(SPREADSHEET_ID), eq(SHEET_NAME), any(ValueRange.class))).thenReturn(append);
        when(append.setValueInputOption("USER_ENTERED")).thenReturn(append);
        when(append.execute()).thenReturn(new AppendValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME));

        appender.appendRow("Zalopay", null, "");

        ArgumentCaptor<ValueRange> captor = ArgumentCaptor.forClass(ValueRange.class);
        verify(values).append(eq(SPREADSHEET_ID), eq(SHEET_NAME), captor.capture());

        List<Object> row = captor.getValue().getValues().get(0);
        assertEquals("", row.get(1));
    }

    @Test
    void swallowsExceptionFromSheetsApiCall() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.values()).thenReturn(values);
        when(values.append(eq(SPREADSHEET_ID), eq(SHEET_NAME), any(ValueRange.class)))
                .thenThrow(new IOException("boom"));

        SheetRowAppender appender = new SheetRowAppender(sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME));

        assertDoesNotThrow(() -> appender.appendRow("Zalopay", null, ""));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.tung.receipt_extractor.sheets.SheetRowAppenderTest"`
Expected: FAIL with a compile error (`SheetRowAppender` doesn't exist yet).

- [ ] **Step 3: Write `SheetRowAppender`**

Create `src/main/java/com/tung/receipt_extractor/sheets/SheetRowAppender.java`:

```java
package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class SheetRowAppender {

    private static final Logger log = LoggerFactory.getLogger(SheetRowAppender.class);
    private static final String VALUE_INPUT_OPTION = "USER_ENTERED";

    private final Sheets sheetsClient;
    private final SheetsProperties sheetsProperties;

    public SheetRowAppender(Sheets sheetsClient, SheetsProperties sheetsProperties) {
        this.sheetsClient = sheetsClient;
        this.sheetsProperties = sheetsProperties;
    }

    public void appendRow(String bankSource, Long amount, String message) {
        try {
            Object amountValue = amount == null ? "" : amount;
            List<Object> row = List.of(bankSource, amountValue, message, Instant.now().toString());
            ValueRange body = new ValueRange().setValues(List.of(row));

            sheetsClient.spreadsheets().values()
                    .append(sheetsProperties.spreadsheetId(), sheetsProperties.sheetName(), body)
                    .setValueInputOption(VALUE_INPUT_OPTION)
                    .execute();
        } catch (Exception e) {
            log.error("Failed to append row to Google Sheet [spreadsheetId={}, sheetName={}]",
                    sheetsProperties.spreadsheetId(), sheetsProperties.sheetName(), e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.tung.receipt_extractor.sheets.SheetRowAppenderTest"`
Expected: PASS (all 3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/sheets/SheetRowAppender.java \
        src/test/java/com/tung/receipt_extractor/sheets/SheetRowAppenderTest.java
git commit -m "Add SheetRowAppender with best-effort row append"
```

---

### Task 3: Wire `SheetRowAppender` into `OcrController`

**Files:**
- Modify: `src/main/java/com/tung/receipt_extractor/ocr/OcrController.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java`
- Modify: `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java`

**Interfaces:**
- Consumes: `SheetRowAppender.appendRow(String, Long, String)` (Task 2).
- Produces: nothing consumed by later tasks — this is the final integration point.

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java`, add the import:

```java
import com.tung.receipt_extractor.sheets.SheetRowAppender;
```

and the mocked bean field plus a new test. The file should now read:

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
    private OcrService ocrService;

    @MockitoBean
    private SheetRowAppender sheetRowAppender;

    @Test
    void returnsExtractedTextForValidImageUpload() throws Exception {
        when(ocrService.extractText(any())).thenReturn("Tổng cộng: 125.000 VND");

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Tổng cộng: 125.000 VND"))
                .andExpect(jsonPath("$.bankSource").value("Zalopay"))
                .andExpect(jsonPath("$.amount").value(125000));
    }

    @Test
    void returnsNullAmountWhenTextHasNoAmount() throws Exception {
        when(ocrService.extractText(any())).thenReturn("Giao dịch thành công!");

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").doesNotExist());
    }

    @Test
    void returnsVietcombankBankSourceWhenTextContainsVcbMarker() throws Exception {
        when(ocrService.extractText(any())).thenReturn("VCBDigibank\nGiao dịch thành công!\nVND 2,000");

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankSource").value("Vietcombank"));
    }

    @Test
    void returnsMessageExtractedForDetectedBankSource() throws Exception {
        when(ocrService.extractText(any())).thenReturn(
                "TECHCOMBANK\nChuyển thành công\nLời nhắn\nNGUYEN SON TUNG chuyen tien\nNgày thực hiện\n12 thg 9, 2026");

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankSource").value("Techcombank"))
                .andExpect(jsonPath("$.message").value("NGUYEN SON TUNG chuyen tien"));
    }

    @Test
    void invokesSheetRowAppenderWithDetectedFields() throws Exception {
        when(ocrService.extractText(any())).thenReturn(
                "TECHCOMBANK\nChuyển thành công\nSố tiền: 50.000\nLời nhắn\nNGUYEN VAN A chuyen tien\nNgày thực hiện\n12 thg 9, 2026");

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isOk());

        verify(sheetRowAppender).appendRow("Techcombank", 50000L, "NGUYEN VAN A chuyen tien");
    }

    @Test
    void returns200EvenWhenSheetRowAppenderThrows() throws Exception {
        when(ocrService.extractText(any())).thenReturn("Giao dịch thành công!\nVND 2,000");
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
    void returns500WhenOcrServiceThrows() throws Exception {
        when(ocrService.extractText(any())).thenThrow(new TesseractException("boom"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("failed to extract text from image"));
    }
}
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.OcrControllerTest"`
Expected: FAIL — `invokesSheetRowAppenderWithDetectedFields` and `returns200EvenWhenSheetRowAppenderThrows` fail (`OcrController` has no `SheetRowAppender` dependency yet, so there's nothing for `verify`/`doThrow` to attach to). The other pre-existing tests should still pass.

- [ ] **Step 3: Wire `SheetRowAppender` into `OcrController`**

Replace `src/main/java/com/tung/receipt_extractor/ocr/OcrController.java` with:

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

    private final OcrService ocrService;
    private final SheetRowAppender sheetRowAppender;

    public OcrController(OcrService ocrService, SheetRowAppender sheetRowAppender) {
        this.ocrService = ocrService;
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
            String text = ocrService.extractText(file.getBytes());
            String bankSource = BankSourceDetector.detect(text);
            Long amount = AmountDetector.detect(text);
            String message = MessageDetector.detect(text, bankSource);
            try {
                sheetRowAppender.appendRow(bankSource, amount, message);
            } catch (Exception e) {
                log.error("Sheets append failed; returning OCR response anyway", e);
            }
            return ResponseEntity.ok(new OcrResponse(text, bankSource, amount, message));
        } catch (TesseractException | IOException e) {
            log.error("OCR extraction failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to extract text from image"));
        }
    }
}
```

`SheetRowAppender.appendRow` already never throws by design (Task 2), but the controller wraps the call in its own `try`/`catch` too — defense in depth, so the "OCR response unaffected by a Sheets failure" guarantee holds even if that internal contract were ever broken by a future change.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.tung.receipt_extractor.ocr.OcrControllerTest"`
Expected: PASS (all 10 tests).

- [ ] **Step 5: Prevent the end-to-end test from making a real Sheets API call**

`OcrEndToEndTest` boots the full application context and hits the real `/api/ocr/extract` endpoint, which now calls the real `SheetRowAppender` — that would attempt a genuine network call to Google's API using the dummy fixture credentials. Mock it out.

Replace `src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java` with:

```java
package com.tung.receipt_extractor.ocr;

import com.tung.receipt_extractor.sheets.SheetRowAppender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sheets.credentials-path=src/test/resources/sheets/test-sheets-credentials.properties")
class OcrEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SheetRowAppender sheetRowAppender;

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

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, every test passes, including `ReceiptExtractorApplicationTests`, all `ocr` tests, and all `sheets` tests. No test makes a real network call.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tung/receipt_extractor/ocr/OcrController.java \
        src/test/java/com/tung/receipt_extractor/ocr/OcrControllerTest.java \
        src/test/java/com/tung/receipt_extractor/ocr/OcrEndToEndTest.java
git commit -m "Append OCR results to Google Sheets after each extraction"
```

## Manual Verification (optional, after all tasks, requires real credentials)

After completing the Prerequisites step (a real `config/sheets-credentials.properties`):

```bash
./gradlew bootRun
# in another terminal:
curl -F "file=@src/test/resources/ocr/sample-receipt.png;type=image/png" http://localhost:8080/api/ocr/extract
```

Expected: the OCR JSON response as before, and a new row appended to the "Test" tab of the configured spreadsheet with columns `Bank source | Amount | Message | Timestamp` populated (Bank source will read `Zalopay` for this particular fixture image, since it contains no bank marker keywords).
