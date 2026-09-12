package com.tung.receipt_extractor.ocr;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BankSourceDetector {

    private static final String DEFAULT_BANK_SOURCE = "Zalopay";
    private static final Pattern WORD_PATTERN = Pattern.compile("\\p{L}+");

    private static final Map<String, String> KEYWORD_TO_BANK = Map.of(
            "vcbdigibank", "Vietcombank",
            "vietcombank", "Vietcombank",
            "techcombank", "Techcombank",
            "zalopay", "Zalopay"
    );

    private BankSourceDetector() {
    }

    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT_BANK_SOURCE;
        }

        Map<String, Integer> wordPositions = firstPositionsByWord(text);

        String bestBank = null;
        int bestPosition = Integer.MAX_VALUE;
        for (Map.Entry<String, String> keywordEntry : KEYWORD_TO_BANK.entrySet()) {
            Integer position = wordPositions.get(keywordEntry.getKey());
            if (position != null && position < bestPosition) {
                bestPosition = position;
                bestBank = keywordEntry.getValue();
            }
        }

        return bestBank != null ? bestBank : DEFAULT_BANK_SOURCE;
    }

    /**
     * Splits text into words (letters only, digits/punctuation/symbols dropped) and
     * records, for each distinct lowercased word, the index at which it first appears.
     */
    private static Map<String, Integer> firstPositionsByWord(String text) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        Matcher matcher = WORD_PATTERN.matcher(text);
        int position = 0;
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            positions.putIfAbsent(word, position);
            position++;
        }
        return positions;
    }
}
