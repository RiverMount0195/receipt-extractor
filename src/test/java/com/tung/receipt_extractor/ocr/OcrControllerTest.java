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

        verify(sheetRowAppender).insertRow(50000L, "NGUYEN VAN A chuyen tien");
    }

    @Test
    void returns200EvenWhenSheetRowAppenderThrows() throws Exception {
        when(receiptExtractionService.extract(any())).thenReturn(
                new OcrResponse("Giao dịch thành công!\nVND 2,000", "Zalopay", 2000L, ""));
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
        when(receiptExtractionService.extract(any())).thenThrow(new TesseractException("boom"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/ocr/extract").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("failed to extract text from image"));
    }
}
