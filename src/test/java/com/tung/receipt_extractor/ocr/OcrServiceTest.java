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
