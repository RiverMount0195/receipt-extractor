package com.tung.receipt_extractor.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AmountDetectorTest {

    @Test
    void detectsDotSeparatedThousands() {
        assertEquals(125000L, AmountDetector.detect("Tổng cộng: 125.000 VND"));
    }

    @Test
    void detectsCommaSeparatedThousands() {
        assertEquals(2000L, AmountDetector.detect("VND 2,000"));
    }

    @Test
    void picksFirstMatchWhenMultipleCandidatesPresent() {
        assertEquals(2000L, AmountDetector.detect("VND 2,000 chuyển đến VND 10,000"));
    }

    @Test
    void parsesMultipleSeparatorGroups() {
        assertEquals(1234567L, AmountDetector.detect("Số dư: 1.234.567 VND"));
    }

    @Test
    void extractsCleanValueFromWordWithTrailingJunk() {
        // Real Zalopay OCR fixture: the amount runs straight into other glyphs.
        assertEquals(2000L, AmountDetector.detect("Tùng dùng Zalopay chuyển tiền\n2.000:s"));
    }

    @Test
    void ignoresPlainDigitRunsWithNoSeparator() {
        assertNull(AmountDetector.detect("Mã giao dịch 16022526601"));
    }

    @Test
    void ignoresDateLikeTokens() {
        assertNull(AmountDetector.detect("18:52 Thứ Bảy 12/09/2026"));
    }

    @Test
    void returnsNullWhenNoAmountPresent() {
        assertNull(AmountDetector.detect("Giao dịch thành công!"));
    }

    @Test
    void returnsNullForNullOrBlankText() {
        assertNull(AmountDetector.detect(null));
        assertNull(AmountDetector.detect(""));
        assertNull(AmountDetector.detect("   "));
    }
}
