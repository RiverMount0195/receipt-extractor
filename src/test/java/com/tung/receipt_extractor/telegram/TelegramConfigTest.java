package com.tung.receipt_extractor.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TelegramConfigTest {

    @Test
    void buildsTelegramPropertiesFromConfiguredValues() {
        TelegramConfig config = new TelegramConfig();

        TelegramProperties properties = config.telegramProperties("test-bot-token", 123456789L, "test-secret");

        assertEquals("test-bot-token", properties.botToken());
        assertEquals(123456789L, properties.allowedChatId());
        assertEquals("test-secret", properties.webhookSecretToken());
    }

    @Test
    void buildsRestClientBuilder() {
        TelegramConfig config = new TelegramConfig();

        assertNotNull(config.restClientBuilder());
    }
}
