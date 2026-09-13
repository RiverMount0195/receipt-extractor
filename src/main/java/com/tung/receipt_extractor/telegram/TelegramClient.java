package com.tung.receipt_extractor.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

@Service
public class TelegramClient {

    private static final String TELEGRAM_API_BASE_URL = "https://api.telegram.org";

    private final RestClient restClient;
    private final String botToken;

    public TelegramClient(RestClient.Builder restClientBuilder, TelegramProperties telegramProperties) {
        this.restClient = restClientBuilder.build();
        this.botToken = telegramProperties.botToken();
    }

    public String getFilePath(String fileId) {
        URI uri = URI.create(TELEGRAM_API_BASE_URL + "/bot" + botToken + "/getFile?file_id=" + fileId);
        TelegramFileResponse response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(TelegramFileResponse.class);
        return response.result().filePath();
    }

    public byte[] downloadFile(String filePath) {
        URI uri = URI.create(TELEGRAM_API_BASE_URL + "/file/bot" + botToken + "/" + filePath);
        return restClient.get()
                .uri(uri)
                .retrieve()
                .body(byte[].class);
    }

    public void sendMessage(long chatId, String text) {
        URI uri = URI.create(TELEGRAM_API_BASE_URL + "/bot" + botToken + "/sendMessage");
        restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chat_id", chatId, "text", text))
                .retrieve()
                .toBodilessEntity();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramFileResponse(boolean ok, TelegramFile result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramFile(@JsonProperty("file_id") String fileId, @JsonProperty("file_path") String filePath) {
    }
}
