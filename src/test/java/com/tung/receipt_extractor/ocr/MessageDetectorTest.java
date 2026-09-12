package com.tung.receipt_extractor.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageDetectorTest {

    @Test
    void extractsTechcombankMessageBetweenLoiNhanAndNgayThucHien() {
        String text = """
                TECHCOMBANK
                Chuyển thành công
                Lời nhắn
                NGUYEN SON TUNG chuyen tien
                Ngày thực hiện
                12 thg 9, 2026 lúc 17:47
                """;

        assertEquals("NGUYEN SON TUNG chuyen tien", MessageDetector.detect(text, "Techcombank"));
    }

    @Test
    void joinsMultiLineTechcombankMessageWithSpaces() {
        String text = """
                Lời nhắn
                NGUYEN SON TUNG
                chuyen tien
                Ngày thực hiện
                12 thg 9, 2026
                """;

        assertEquals("NGUYEN SON TUNG chuyen tien", MessageDetector.detect(text, "Techcombank"));
    }

    @Test
    void extractsVietcombankMessageBetweenNoiDungAndMaThamChieu() {
        String text = """
                Nội dung NGUYEN SON TUNG
                chuyen tien test
                Mã tham chiếu 6255BFTVGLA3IR6P
                """;

        assertEquals("NGUYEN SON TUNG chuyen tien test", MessageDetector.detect(text, "Vietcombank"));
    }

    @Test
    void extractsZalopayMessageFromGhiChuToEndOfLine() {
        String text = """
                Ghi chú: Tùng dùng Zalopay chuyền tiền
                Đóng Chi tiết giao dịch
                """;

        assertEquals("Tùng dùng Zalopay chuyền tiền", MessageDetector.detect(text, "Zalopay"));
    }

    @Test
    void extractsZalopayMessageWhenGhiChuIsTheLastLineWithNoTrailingNewline() {
        String text = "Giao dich thanh cong\nGhi chú: Test Ăn tối";

        assertEquals("Test Ăn tối", MessageDetector.detect(text, "Zalopay"));
    }

    @Test
    void returnsEmptyWhenTechcombankEndLabelMissing() {
        String text = """
                Lời nhắn
                NGUYEN SON TUNG chuyen tien
                """;

        assertEquals("", MessageDetector.detect(text, "Techcombank"));
    }

    @Test
    void returnsEmptyWhenStartLabelMissing() {
        String text = """
                Chuyển thành công
                Ngày thực hiện
                12 thg 9, 2026
                """;

        assertEquals("", MessageDetector.detect(text, "Techcombank"));
    }

    @Test
    void returnsEmptyForUnknownBankSource() {
        assertEquals("", MessageDetector.detect("Lời nhắn test Ngày thực hiện", "SomeOtherBank"));
    }

    @Test
    void returnsEmptyForNullBankSource() {
        assertEquals("", MessageDetector.detect("Lời nhắn test Ngày thực hiện", null));
    }

    @Test
    void returnsEmptyForNullOrBlankText() {
        assertEquals("", MessageDetector.detect(null, "Techcombank"));
        assertEquals("", MessageDetector.detect("", "Vietcombank"));
        assertEquals("", MessageDetector.detect("   ", "Zalopay"));
    }
}
