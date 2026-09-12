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
