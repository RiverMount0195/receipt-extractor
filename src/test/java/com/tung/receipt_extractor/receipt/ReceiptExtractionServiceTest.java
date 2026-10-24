package com.tung.receipt_extractor.receipt;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiptExtractionServiceTest {

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final ReceiptExtractionService service = new ReceiptExtractionService(chatClient);

    @Test
    void returnsReceiptDataParsedByTheModel() {
        ReceiptData expected = new ReceiptData("Techcombank", 50000L, "NGUYEN VAN A chuyen tien");
        when(chatClient.prompt().user(anyUserSpecConsumer()).call().entity(ReceiptData.class))
                .thenReturn(expected);

        ReceiptData actual = service.extract("fake-image-bytes".getBytes(), "image/jpeg");

        assertEquals(expected, actual);
    }

    @Test
    void attachesTheImageWithItsMimeTypeToTheUserMessage() {
        when(chatClient.prompt().user(anyUserSpecConsumer()).call().entity(ReceiptData.class))
                .thenReturn(new ReceiptData(null, null, null));

        service.extract("fake-image-bytes".getBytes(), "image/png");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(chatClient.prompt(), atLeastOnce()).user(captor.capture()); // stubbing also invoked user(any())
        ChatClient.PromptUserSpec userSpec = mock(ChatClient.PromptUserSpec.class, RETURNS_SELF);
        captor.getValue().accept(userSpec);
        verify(userSpec).media(eq(MimeTypeUtils.IMAGE_PNG), any(ByteArrayResource.class));
    }

    @Test
    void propagatesFailuresFromTheModelCall() {
        when(chatClient.prompt().user(anyUserSpecConsumer()).call().entity(ReceiptData.class))
                .thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class,
                () -> service.extract("fake-image-bytes".getBytes(), "image/jpeg"));
    }

    @SuppressWarnings("unchecked")
    private static Consumer<ChatClient.PromptUserSpec> anyUserSpecConsumer() {
        return any(Consumer.class);
    }
}
