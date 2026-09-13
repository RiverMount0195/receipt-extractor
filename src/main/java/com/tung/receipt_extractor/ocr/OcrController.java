package com.tung.receipt_extractor.ocr;

import com.tung.receipt_extractor.sheets.SheetRowAppender;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
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
@RegisterReflectionForBinding(OcrResponse.class)
public class OcrController {

    private static final Logger log = LoggerFactory.getLogger(OcrController.class);
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final ReceiptExtractionService receiptExtractionService;
    private final SheetRowAppender sheetRowAppender;

    public OcrController(ReceiptExtractionService receiptExtractionService, SheetRowAppender sheetRowAppender) {
        this.receiptExtractionService = receiptExtractionService;
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
            OcrResponse response = receiptExtractionService.extract(file.getBytes());
            try {
                sheetRowAppender.appendRow(response.bankSource(), response.amount(), response.message());
            } catch (Exception e) {
                log.error("Sheets append failed; returning OCR response anyway", e);
            }
            return ResponseEntity.ok(response);
        } catch (TesseractException | IOException e) {
            log.error("OCR extraction failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to extract text from image"));
        }
    }
}
