package com.tung.receipt_extractor.receipt;

import com.tung.receipt_extractor.sheets.SheetRowAppender;
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
public class ReceiptController {

    private static final Logger log = LoggerFactory.getLogger(ReceiptController.class);
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final ReceiptExtractionService receiptExtractionService;
    private final SheetRowAppender sheetRowAppender;

    public ReceiptController(ReceiptExtractionService receiptExtractionService, SheetRowAppender sheetRowAppender) {
        this.receiptExtractionService = receiptExtractionService;
        this.sheetRowAppender = sheetRowAppender;
    }

    @PostMapping(value = "/api/ocr/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> extract(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        if (file.getContentType() == null || !SUPPORTED_CONTENT_TYPES.contains(file.getContentType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported file type"));
        }
        try {
            ReceiptData data = receiptExtractionService.extract(file.getBytes(), file.getContentType());
            try {
                sheetRowAppender.insertRow(data.amount(), data.message());
            } catch (Exception e) {
                log.error("Sheets append failed; returning extraction response anyway", e);
            }
            return ResponseEntity.ok(data);
        } catch (IOException | RuntimeException e) {
            log.error("Receipt extraction failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to extract data from image"));
        }
    }
}
