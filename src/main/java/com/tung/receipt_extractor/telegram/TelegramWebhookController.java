package com.tung.receipt_extractor.telegram;

import com.tung.receipt_extractor.ocr.OcrResponse;
import com.tung.receipt_extractor.ocr.ReceiptExtractionService;
import com.tung.receipt_extractor.sheets.SheetRowAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);
    private static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final String NO_PHOTO_REPLY = "Please send a photo of your receipt.";
    private static final String PROCESSING_FAILED_REPLY = "Sorry, couldn't process that image. Please try again.";

    private final ObjectMapper objectMapper;
    private final TelegramProperties telegramProperties;
    private final TelegramClient telegramClient;
    private final ReceiptExtractionService receiptExtractionService;
    private final SheetRowAppender sheetRowAppender;

    public TelegramWebhookController(
            ObjectMapper objectMapper,
            TelegramProperties telegramProperties,
            TelegramClient telegramClient,
            ReceiptExtractionService receiptExtractionService,
            SheetRowAppender sheetRowAppender) {
        this.objectMapper = objectMapper;
        this.telegramProperties = telegramProperties;
        this.telegramClient = telegramClient;
        this.receiptExtractionService = receiptExtractionService;
        this.sheetRowAppender = sheetRowAppender;
    }

    @PostMapping("/api/telegram-webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = SECRET_TOKEN_HEADER, required = false) String secretTokenHeader,
            @RequestBody String rawBody) {
        if (!telegramProperties.webhookSecretToken().equals(secretTokenHeader)) {
            log.warn("Rejected Telegram webhook call with invalid secret token header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Telegram webhook payload: {}", rawBody);

        TelegramUpdate update;
        try {
            update = objectMapper.readValue(rawBody, TelegramUpdate.class);
        } catch (Exception e) {
            log.error("Failed to parse Telegram webhook payload", e);
            return ResponseEntity.ok().build();
        }

        TelegramMessage message = update.message();
        if (message == null || message.chat() == null) {
            return ResponseEntity.ok().build();
        }

        long chatId = message.chat().id();
        if (chatId != telegramProperties.allowedChatId()) {
            log.warn("Ignoring Telegram message from non-allow-listed chat id {}", chatId);
            return ResponseEntity.ok().build();
        }

        TelegramPhotoSize largestPhoto = message.largestPhoto();
        if (largestPhoto == null) {
            replySafely(chatId, NO_PHOTO_REPLY);
            return ResponseEntity.ok().build();
        }

        try {
            String filePath = telegramClient.getFilePath(largestPhoto.fileId());
            byte[] imageBytes = telegramClient.downloadFile(filePath);

            OcrResponse extracted = receiptExtractionService.extract(imageBytes);
            String finalMessage = message.caption() != null && !message.caption().isBlank()
                    ? message.caption()
                    : extracted.message();

            sheetRowAppender.appendRow(extracted.bankSource(), extracted.amount(), finalMessage);

            replySafely(chatId, buildConfirmationReply(extracted.bankSource(), extracted.amount(), finalMessage));
        } catch (Exception e) {
            log.error("Failed to process Telegram update {} for chat id {}", update.updateId(), chatId, e);
            replySafely(chatId, PROCESSING_FAILED_REPLY);
        }

        return ResponseEntity.ok().build();
    }

    private void replySafely(long chatId, String text) {
        try {
            telegramClient.sendMessage(chatId, text);
        } catch (RuntimeException e) {
            log.error("Failed to send Telegram reply to chat id {}", chatId, e);
        }
    }

    private String buildConfirmationReply(String bankSource, Long amount, String message) {
        String bankSourceText = (bankSource == null || bankSource.isBlank()) ? "unknown" : bankSource;
        String amountText = amount == null ? "unknown" : String.valueOf(amount);
        String messageText = (message == null || message.isBlank()) ? "unknown" : message;
        return "Recorded: " + bankSourceText + ", " + amountText + ", " + messageText;
    }
}
