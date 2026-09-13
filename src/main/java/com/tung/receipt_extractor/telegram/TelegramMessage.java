package com.tung.receipt_extractor.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Comparator;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(TelegramChat chat, List<TelegramPhotoSize> photo, String caption, String text) {

    public TelegramPhotoSize largestPhoto() {
        if (photo == null || photo.isEmpty()) {
            return null;
        }
        return photo.stream().max(Comparator.comparingInt(TelegramPhotoSize::width)).orElse(null);
    }
}
