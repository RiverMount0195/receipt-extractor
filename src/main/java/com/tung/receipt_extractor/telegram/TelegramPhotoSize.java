package com.tung.receipt_extractor.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramPhotoSize(@JsonProperty("file_id") String fileId, int width, int height) {
}
