package com.tung.receipt_extractor.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankSourceDetectorTest {

    @Test
    void detectsVietcombankFromVcbDigibankScreenshotThatAlsoMentionsRecipientTechcombank() {
        String text = """
                VCBDigibank (©)
                Giao dịch thành công!
                VND
                2,000
                18:52 Thứ Bảy 12/09/2026
                Tài khoản nhận 19071550854016
                Tén người nhận NGUYEN SON TUNG
                Ngân hàng nhận $» TECHCOMBANK
                Ngân hàng Kỹ thương Việt Nam
                Nội dung NGUYEN SON TUNG
                chuyen tien test
                Mã tham chiếu 6255BFTVGLA3IR6P
                Phí chuyển tiền Miễn phí
                Hình thức chuyển Chuyển tiền nhanh
                napas 247
                Mã giao dịch 16022526601
                """;

        assertEquals("Vietcombank", BankSourceDetector.detect(text));
    }

    @Test
    void detectsTechcombankFromTechcombankScreenshotThatMentionsRecipientBidv() {
        String text = """
                17:48 ® « Le atl Sul GO
                @ @
                TECHCOMBANK <>
                Chuyển thành công
                Tới LAI PHÙ MANH
                VND 5,000
                Tài khoản nhận
                Ngân hàng TMCP Đầu tư và Phát triển Việt Nam
                8830 3021 58
                Lời nhắn
                NGUYEN SON TUNG chuyen tien
                Ngày thực hiện
                12 thg 9, 2026 lúc 17:47
                Mã giao dịch
                FT26255169957373
                Đây là khoản chỉ tiêu gì?

                FC Ăn thả ga, voucher ngập lối chỉ từ 0 Ð tại
                * Highlands, KFC, Pizza 4Ps...Đổi ngay
                GZ -G
                Chia sẻ Lưu người nhận Chia tiền
                Quay về Trang chủ
                """;

        assertEquals("Techcombank", BankSourceDetector.detect(text));
    }

    @Test
    void detectsZalopayFromZalopayScreenshotThatMentionsRecipientTechcombankByVietnameseName() {
        String text = """
                18:34 Le all ll
                Y) :
                Giao dịch thành công
                Mã giao dịch: #260912003596627 >
                Thời gian: 18:34 12/09/2026
                Tùng dùng Zalopay chuyển tiền
                2.000:s

                NGUYEN SON TUNG
                $2 Ngân hàng TMCP Kỹ thương ViệtNam >
                9071350854016
                Ghi chú: Tùng dùng Zalopay chuyền tiền
                Đóng Chỉ tiết giao dịch
                Dành riêng chuyển tiền
                XMB [ `
                ; F i / Rig ff
                i =m ff | Ee —
                = J7 Uf-~=- / =

                Se an

                Đổi qua tai Cửa hang Quét QR :
                Tích xu đổi quà độc quyền f

                Xem Tarot miễn phí 5
                Luận giải tương lai, khám pha vận may!
                """;

        assertEquals("Zalopay", BankSourceDetector.detect(text));
    }

    @Test
    void fallsBackToZalopayWhenNoKeywordAppearsAtAll() {
        // Real Zalopay screenshot where "Zalopay" itself never appears as a word;
        // the recipient bank is only named in Vietnamese ("Kỹ thương Việt Nam"),
        // which must NOT be treated as a match for the "Techcombank" keyword.
        String text = """
                18:36 4 Be wail al @
                © :
                Giao dịch thành công
                Mã giao dịch: #260912003601608 >
                Thời gian: 18:36 12/09/2026
                Test Ăn tối
                2.000:s
                NGUYEN SON TUNG
                $2 Ngân hàng TMCP Kỹ thương ViệtNam >
                9071350854016
                Ghi chú: Test Ăn tối
                Đóng Chỉ tiết giao dịch
                Dành riêng chuyển tiền
                XMB [
                k I ... J Rim ie -
                Se - K Bove ——.

                "= 7 #j ` "ÿ |
                Se
                Đổi qua tai Cửa hang Quét QR :

                Tích xu đổi quà độc quyền f
                Xem Tarot miễn phí 5
                Luận giải tương lai, khám pha vận may!
                """;

        assertEquals("Zalopay", BankSourceDetector.detect(text));
    }

    @Test
    void picksWhicheverKeywordAppearsEarliestInTheText() {
        assertEquals("Techcombank", BankSourceDetector.detect("Techcombank transfer to Vietcombank account"));
        assertEquals("Vietcombank", BankSourceDetector.detect("Vietcombank transfer to Techcombank account"));
    }

    @Test
    void fallsBackToZalopayForGenericTextWithNoBankKeywords() {
        String text = "Thanh toan thanh cong 10,000 VND";

        assertEquals("Zalopay", BankSourceDetector.detect(text));
    }

    @Test
    void fallsBackToZalopayForNullOrBlankText() {
        assertEquals("Zalopay", BankSourceDetector.detect(null));
        assertEquals("Zalopay", BankSourceDetector.detect(""));
        assertEquals("Zalopay", BankSourceDetector.detect("   "));
    }
}
