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
