package com.tung.receipt_extractor.telegram;

import com.tung.receipt_extractor.ocr.OcrResponse;
import com.tung.receipt_extractor.ocr.ReceiptExtractionService;
import com.tung.receipt_extractor.sheets.SheetRowAppender;
import net.sourceforge.tess4j.TesseractException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TelegramWebhookController.class)
class TelegramWebhookControllerTest {

    private static final String SECRET_TOKEN = "test-secret";
    private static final long ALLOWED_CHAT_ID = 123456789L;
    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    @TestConfiguration
    static class Config {
        @Bean
        TelegramProperties telegramProperties() {
            return new TelegramProperties("test-bot-token", ALLOWED_CHAT_ID, SECRET_TOKEN);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramClient telegramClient;

    @MockitoBean
    private ReceiptExtractionService receiptExtractionService;

    @MockitoBean
    private SheetRowAppender sheetRowAppender;

    @Test
    void missingOrWrongSecretTokenHeaderReturns401AndDoesNothingElse() throws Exception {
        String payload = """
                { "update_id": 1, "message": { "chat": { "id": 123456789 }, "text": "hi" } }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(telegramClient, receiptExtractionService, sheetRowAppender);
    }

    @Test
    void photoWithCaptionUsesCaptionAsMessageOverride() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenReturn("photos/file.jpg");
        when(telegramClient.downloadFile("photos/file.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(receiptExtractionService.extract(any()))
                .thenReturn(new OcrResponse("raw text", "Techcombank", 50000L, "extracted message"));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "small-id", "width": 90, "height": 90 },
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ],
                    "caption": "override message"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).getFilePath("large-id");
        verify(sheetRowAppender).insertRow(50000L, "override message");
        verify(telegramClient).sendMessage(eq(ALLOWED_CHAT_ID), contains("override message"));
    }

    @Test
    void photoWithoutCaptionUsesExtractedMessage() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenReturn("photos/file.jpg");
        when(telegramClient.downloadFile("photos/file.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(receiptExtractionService.extract(any()))
                .thenReturn(new OcrResponse("raw text", "Techcombank", 50000L, "extracted message"));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(sheetRowAppender).insertRow(50000L, "extracted message");
    }

    @Test
    void noPhotoRepliesWithErrorAndDoesNotAppendToSheet() throws Exception {
        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "text": "hello"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, "Please send a photo of your receipt.");
        verify(receiptExtractionService, never()).extract(any());
        verify(sheetRowAppender, never()).insertRow(any(), any());
    }

    @Test
    void replySendFailureDoesNotPreventTelegramFrom200Response() throws Exception {
        doThrow(new RuntimeException("boom")).when(telegramClient).sendMessage(anyLong(), anyString());

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "text": "hello"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, "Please send a photo of your receipt.");
    }

    @Test
    void wrongChatIdIsIgnoredWithNoReplyOrProcessing() throws Exception {
        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 999 },
                    "text": "hello"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(telegramClient, receiptExtractionService, sheetRowAppender);
    }

    @Test
    void updateWithNoMessageIsIgnored() throws Exception {
        String payload = """
                {
                  "update_id": 1,
                  "edited_message": {
                    "chat": { "id": 123456789 },
                    "text": "edited"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(telegramClient, receiptExtractionService, sheetRowAppender);
    }

    @Test
    void exceptionDuringProcessingRepliesWithGenericErrorAndStillReturns200() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenThrow(new RuntimeException("boom"));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, "Sorry, couldn't process that image. Please try again.");
        verify(sheetRowAppender, never()).insertRow(any(), any());
    }

    @Test
    void successfulProcessingRepliesWithExactConfirmationText() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenReturn("photos/file.jpg");
        when(telegramClient.downloadFile("photos/file.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(receiptExtractionService.extract(any()))
                .thenReturn(new OcrResponse("raw text", "Techcombank", 50000L, "extracted message"));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ],
                    "caption": "override message"
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, "Recorded: Techcombank, 50000, override message");
    }

    @Test
    void successfulProcessingWithAllNullFieldsRepliesWithUnknownPlaceholders() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenReturn("photos/file.jpg");
        when(telegramClient.downloadFile("photos/file.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(receiptExtractionService.extract(any()))
                .thenReturn(new OcrResponse("raw text", null, null, null));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, "Recorded: unknown, unknown, unknown");
    }

    @Test
    void malformedJsonBodyReturns200AndDoesNotInteractWithAnything() throws Exception {
        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not valid json"))
                .andExpect(status().isOk());

        verifyNoInteractions(telegramClient, receiptExtractionService, sheetRowAppender);
    }

    @Test
    void tesseractExceptionDuringProcessingRepliesWithGenericErrorAndStillReturns200() throws Exception {
        when(telegramClient.getFilePath("large-id")).thenReturn("photos/file.jpg");
        when(telegramClient.downloadFile("photos/file.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(receiptExtractionService.extract(any())).thenThrow(new TesseractException("boom"));

        String payload = """
                {
                  "update_id": 1,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "large-id", "width": 800, "height": 800 }
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .header(SECRET_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, "Sorry, couldn't process that image. Please try again.");
        verify(sheetRowAppender, never()).insertRow(any(), any());
    }

    @Test
    void absentSecretTokenHeaderReturns401() throws Exception {
        String payload = """
                { "update_id": 1, "message": { "chat": { "id": 123456789 }, "text": "hi" } }
                """;

        mockMvc.perform(post("/api/telegram-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(telegramClient, receiptExtractionService, sheetRowAppender);
    }
}
