package com.tung.receipt_extractor.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class TelegramConfig {

    @Bean
    public TelegramProperties telegramProperties(
            @Value("${telegram.bot-token}") String botToken,
            @Value("${telegram.allowed-chat-id}") long allowedChatId,
            @Value("${telegram.webhook-secret-token}") String webhookSecretToken) {
        return new TelegramProperties(botToken, allowedChatId, webhookSecretToken);
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
