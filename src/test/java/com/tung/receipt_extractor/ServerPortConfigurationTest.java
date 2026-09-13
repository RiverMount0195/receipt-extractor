package com.tung.receipt_extractor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sheets.client-id=test-client-id",
        "sheets.client-secret=test-client-secret",
        "sheets.refresh-token=test-refresh-token",
        "sheets.spreadsheet-id=test-spreadsheet-id",
        "sheets.sheet-name=Test"
})
class ServerPortConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    void defaultsToPort8080WhenPortEnvVarUnset() {
        assertEquals("8080", environment.getProperty("server.port"));
    }
}
