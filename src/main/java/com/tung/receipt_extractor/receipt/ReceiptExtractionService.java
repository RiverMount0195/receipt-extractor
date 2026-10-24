package com.tung.receipt_extractor.receipt;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

@Service
public class ReceiptExtractionService {

    private static final String USER_PROMPT = "Extract the data from this receipt image.";

    private final ChatClient chatClient;

    public ReceiptExtractionService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ReceiptData extract(byte[] imageBytes, String mimeType) {
        return chatClient.prompt()
                .user(user -> user
                        .text(USER_PROMPT)
                        .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes)))
                .call()
                .entity(ReceiptData.class);
    }
}
