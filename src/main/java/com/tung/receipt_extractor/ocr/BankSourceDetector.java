package com.tung.receipt_extractor.ocr;

import java.util.Locale;

public final class BankSourceDetector {

    private static final String DEFAULT_BANK_SOURCE = "Zalopay";

    private BankSourceDetector() {
    }

    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT_BANK_SOURCE;
        }

        String normalized = text.toLowerCase(Locale.ROOT);

        if (normalized.contains("vcb") || normalized.contains("vietcombank")) {
            return "Vietcombank";
        }
        if (normalized.contains("techcombank")) {
            return "Techcombank";
        }

        return DEFAULT_BANK_SOURCE;
    }
}
