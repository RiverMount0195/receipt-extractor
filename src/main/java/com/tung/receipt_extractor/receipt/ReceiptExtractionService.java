package com.tung.receipt_extractor.receipt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReceiptExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptExtractionService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String USER_PROMPT = "Extract the data from this receipt image.";

    private final ChatClient chatClient;

    public ReceiptExtractionService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ReceiptData extract(byte[] imageBytes, String mimeType) {
        log.info("Calling Gemini to extract receipt data: mimeType={}, imageSize={} bytes", mimeType, imageBytes.length);
        ReceiptData data = chatClient.prompt()
                .user(user -> user
                        .text(USER_PROMPT)
                        .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes)))
                .call()
                .entity(ReceiptData.class);
        log.info("Gemini extracted receipt data: {}", JSON.writeValueAsString(data));
        return data;
    }
}
