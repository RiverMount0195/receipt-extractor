package com.tung.receipt_extractor.ocr;

public final class MessageDetector {

    private static final String EMPTY = "";

    private MessageDetector() {
    }

    public static String detect(String text, String bankSource) {
        if (text == null || text.isBlank() || bankSource == null) {
            return EMPTY;
        }

        return switch (bankSource) {
            case BankSourceDetector.TECHCOMBANK -> extractBetween(text, "Lời nhắn", "Ngày thực hiện");
            case BankSourceDetector.VIETCOMBANK -> extractBetween(text, "Nội dung", "Mã tham chiếu");
            case BankSourceDetector.ZALOPAY -> extractToLineBreak(text, "Ghi chú");
            default -> EMPTY;
        };
    }

    private static String extractBetween(String text, String startLabel, String endLabel) {
        int startIndex = text.indexOf(startLabel);
        if (startIndex < 0) {
            return EMPTY;
        }
        int contentStart = startIndex + startLabel.length();

        int endIndex = text.indexOf(endLabel, contentStart);
        if (endIndex < 0) {
            return EMPTY;
        }

        return clean(text.substring(contentStart, endIndex));
    }

    private static String extractToLineBreak(String text, String label) {
        int startIndex = text.indexOf(label);
        if (startIndex < 0) {
            return EMPTY;
        }
        int contentStart = startIndex + label.length();

        int lineBreakIndex = text.indexOf('\n', contentStart);
        String raw = lineBreakIndex < 0
                ? text.substring(contentStart)
                : text.substring(contentStart, lineBreakIndex);

        return clean(raw);
    }

    private static String clean(String raw) {
        String withoutLeadingSeparator = raw.stripLeading();
        if (withoutLeadingSeparator.startsWith(":")) {
            withoutLeadingSeparator = withoutLeadingSeparator.substring(1);
        }
        return withoutLeadingSeparator.replace('\n', ' ').trim().replaceAll(" +", " ");
    }
}
