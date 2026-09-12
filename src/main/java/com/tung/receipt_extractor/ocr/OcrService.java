package com.tung.receipt_extractor.ocr;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

@Service
public class OcrService {

    private static final String DEFAULT_FORMAT = "png";

    private final ITesseract tesseract;

    public OcrService(ITesseract tesseract) {
        this.tesseract = tesseract;
    }

    public String extractText(byte[] imageBytes) throws TesseractException, IOException {
        File tempFile = Files.createTempFile("ocr-", "." + detectFormat(imageBytes)).toFile();
        try {
            Files.write(tempFile.toPath(), imageBytes);
            return tesseract.doOCR(tempFile);
        } finally {
            tempFile.delete();
        }
    }

    /**
     * Tess4J's doOCR(File) picks an ImageIO reader based on the file's extension
     * rather than sniffing its content, so the temp file must carry the image's
     * real format as its extension or Tesseract throws "Unsupported image format".
     */
    private String detectFormat(byte[] imageBytes) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                return readers.next().getFormatName().toLowerCase();
            }
        }
        return DEFAULT_FORMAT;
    }
}
