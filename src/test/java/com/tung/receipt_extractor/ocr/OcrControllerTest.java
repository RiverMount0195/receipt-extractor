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
