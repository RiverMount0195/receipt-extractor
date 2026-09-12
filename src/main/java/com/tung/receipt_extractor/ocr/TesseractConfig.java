package com.tung.receipt_extractor.ocr;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TesseractConfig {

    @Bean
    public ITesseract tesseract(
            @Value("${ocr.tessdata-path}") String tessdataPath,
            @Value("${ocr.languages}") String languages) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage(languages);
        return tesseract;
    }
}
