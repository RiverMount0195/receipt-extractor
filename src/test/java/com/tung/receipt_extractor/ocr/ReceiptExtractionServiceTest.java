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
