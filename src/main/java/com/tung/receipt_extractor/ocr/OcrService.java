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
    private final Object lock = new Object();

    public OcrService(ITesseract tesseract) {
        this.tesseract = tesseract;
    }

    public String extractText(byte[] imageBytes) throws TesseractException, IOException {
        File tempFile = Files.createTempFile("ocr-", "." + detectFormat(imageBytes)).toFile();
        try {
            Files.write(tempFile.toPath(), imageBytes);
            // Tess4J's Tesseract is not thread-safe: doOCR() mutates a shared native
            // handle field on init()/dispose(), so concurrent calls on the same
            // singleton instance can corrupt or double-free it. Serialize access.
            synchronized (lock) {
                return tesseract.doOCR(tempFile);
            }
        } finally {
            Files.deleteIfExists(tempFile.toPath());
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
                ImageReader reader = readers.next();
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                reader.dispose();
                return format;
            }
        }
        return DEFAULT_FORMAT;
    }
}
