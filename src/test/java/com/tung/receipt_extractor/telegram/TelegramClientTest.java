package com.tung.receipt_extractor.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramClientTest {

    private static final String BOT_TOKEN = "test-bot-token";
    private static final TelegramProperties PROPERTIES =
            new TelegramProperties(BOT_TOKEN, 123456789L, "test-secret");

    @Test
    void getFilePathReturnsFilePathFromTelegramResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/getFile?file_id=file-123"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"ok\":true,\"result\":{\"file_id\":\"file-123\",\"file_path\":\"photos/file_1.jpg\"}}",
                        MediaType.APPLICATION_JSON));
        TelegramClient client = new TelegramClient(builder, PROPERTIES);

        String filePath = client.getFilePath("file-123");

        assertEquals("photos/file_1.jpg", filePath);
        server.verify();
    }

    @Test
    void downloadFileReturnsRawBytesFromTelegramFileEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] imageBytes = {1, 2, 3, 4};
        server.expect(requestTo("https://api.telegram.org/file/bot" + BOT_TOKEN + "/photos/file_1.jpg"))
                .andExpect(method(GET))
                .andRespond(withSuccess(imageBytes, MediaType.APPLICATION_OCTET_STREAM));
        TelegramClient client = new TelegramClient(builder, PROPERTIES);

        byte[] result = client.downloadFile("photos/file_1.jpg");

        assertArrayEquals(imageBytes, result);
        server.verify();
    }

    @Test
    void getFilePathRedactsBotTokenFromExceptionMessageOnServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/getFile?file_id=file-123"))
                .andExpect(method(GET))
                .andRespond(withServerError());
        TelegramClient client = new TelegramClient(builder, PROPERTIES);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> client.getFilePath("file-123"));

        assertFalse(exception.getMessage().contains(BOT_TOKEN),
                "Exception message should not contain the raw bot token: " + exception.getMessage());
        server.verify();
    }

    @Test
    void getFilePathThrowsClearExceptionWhenTelegramResponseHasNoFilePath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/getFile?file_id=file-123"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"ok\":false}", MediaType.APPLICATION_JSON));
        TelegramClient client = new TelegramClient(builder, PROPERTIES);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> client.getFilePath("file-123"));

        assertEquals("Telegram getFile returned no file path", exception.getMessage());
        server.verify();
    }

    @Test
    void sendMessagePostsChatIdAndTextToTelegram() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"chat_id\":123456789,\"text\":\"hello\"}"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        TelegramClient client = new TelegramClient(builder, PROPERTIES);

        client.sendMessage(123456789L, "hello");

        server.verify();
    }
}
