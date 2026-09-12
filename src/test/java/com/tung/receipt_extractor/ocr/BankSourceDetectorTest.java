package com.tung.receipt_extractor.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankSourceDetectorTest {

    @Test
    void detectsVietcombankFromVcbDigibankText() {
        String text = "VCBDigibank (©)\nGiao dịch thành công!\nVND\n2,000\n18:52 Thứ Bảy 12/09/2026\n"
                + "Tài khoản nhận 19071550854016";

        assertEquals("Vietcombank", BankSourceDetector.detect(text));
    }

    @Test
    void detectsTechcombankFromTechcombankText() {
        String text = "17:48 ® « Le atl Sul GO\n@ @\nTECHCOMBANK <>\nChuyển thành công\nTới LAI PHÙ MANH\nVND 5,000";

        assertEquals("Techcombank", BankSourceDetector.detect(text));
    }

    @Test
    void fallsBackToZalopayForUnrecognizedText() {
        String text = "ZaloPay\nThanh toán thành công\nVND 10,000";

        assertEquals("Zalopay", BankSourceDetector.detect(text));
    }

    @Test
    void fallsBackToZalopayForNullOrBlankText() {
        assertEquals("Zalopay", BankSourceDetector.detect(null));
        assertEquals("Zalopay", BankSourceDetector.detect(""));
        assertEquals("Zalopay", BankSourceDetector.detect("   "));
    }
}
