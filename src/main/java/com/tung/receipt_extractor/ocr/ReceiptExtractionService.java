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
