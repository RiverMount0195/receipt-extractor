package com.tung.receipt_extractor.ocr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AmountDetector {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d+(?:[.,]\\d+)+");

    private AmountDetector() {
    }

    public static Long detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Matcher matcher = AMOUNT_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        String digitsOnly = matcher.group().replaceAll("[.,]", "");
        return Long.parseLong(digitsOnly);
    }
}
