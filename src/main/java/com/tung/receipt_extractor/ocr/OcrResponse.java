package com.tung.receipt_extractor.ocr;

public record OcrResponse(String text, String bankSource, Long amount) {
}
