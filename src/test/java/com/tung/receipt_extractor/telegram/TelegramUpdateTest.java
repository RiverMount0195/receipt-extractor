package com.tung.receipt_extractor.telegram;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TelegramUpdateTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    void parsesPhotoMessageWithCaption() {
        String json = """
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

        TelegramUpdate update = objectMapper.readValue(json, TelegramUpdate.class);

        assertEquals(1L, update.updateId());
        assertEquals(123456789L, update.message().chat().id());
        assertEquals("override message", update.message().caption());
        assertEquals("large-id", update.message().largestPhoto().fileId());
    }

    @Test
    void parsesTextOnlyMessageWithNoPhoto() {
        String json = """
                {
                  "update_id": 2,
                  "message": {
                    "chat": { "id": 123456789 },
                    "text": "hello"
                  }
                }
                """;

        TelegramUpdate update = objectMapper.readValue(json, TelegramUpdate.class);

        assertEquals("hello", update.message().text());
        assertNull(update.message().largestPhoto());
    }

    @Test
    void ignoresUnknownFieldsAndUnrelatedUpdateTypes() {
        String json = """
                {
                  "update_id": 3,
                  "edited_message": {
                    "chat": { "id": 123456789 },
                    "text": "edited"
                  }
                }
                """;

        TelegramUpdate update = objectMapper.readValue(json, TelegramUpdate.class);

        assertNull(update.message());
    }

    @Test
    void picksLargestPhotoByWidthRegardlessOfArrayOrder() {
        String json = """
                {
                  "update_id": 4,
                  "message": {
                    "chat": { "id": 123456789 },
                    "photo": [
                      { "file_id": "medium-id", "width": 320, "height": 320 },
                      { "file_id": "large-id", "width": 800, "height": 800 },
                      { "file_id": "small-id", "width": 90, "height": 90 }
                    ]
                  }
                }
                """;

        TelegramUpdate update = objectMapper.readValue(json, TelegramUpdate.class);

        assertEquals("large-id", update.message().largestPhoto().fileId());
    }
}
