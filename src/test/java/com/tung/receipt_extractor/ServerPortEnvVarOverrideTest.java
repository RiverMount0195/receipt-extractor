package com.tung.receipt_extractor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "PORT=9090",
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test",
        "telegram.bot-token=test-bot-token",
        "telegram.allowed-chat-id=123456789",
        "telegram.webhook-secret-token=test-secret"
})
class ServerPortEnvVarOverrideTest {

    @Autowired
    private Environment environment;

    @Test
    void usesPortEnvVarWhenSet() {
        assertEquals("9090", environment.getProperty("server.port"));
    }
}
