package com.tung.receipt_extractor.receipt;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReceiptAiConfig {

    static final String SYSTEM_PROMPT = """
            You read photos and screenshots of Vietnamese bank-transfer receipts and extract data from them.
            Return only these fields:
            - bankSource: the name of the bank or e-wallet the money was sent from (for example Techcombank, Vietcombank, Zalopay). Null if unknown.
            - amount: the transferred amount as a positive integer in VND, with no thousands separators and no currency symbol. Null if unknown.
            - message: the transfer description or note, copied exactly as written on the receipt. Null if there is none.
            Never guess. If a field is not clearly visible, return null for it.
            """;

    @Bean
    ChatClient receiptChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM_PROMPT).build();
    }
}
