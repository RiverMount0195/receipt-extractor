package com.tung.receipt_extractor.telegram;

public record TelegramProperties(String botToken, long allowedChatId, String webhookSecretToken) {
}
