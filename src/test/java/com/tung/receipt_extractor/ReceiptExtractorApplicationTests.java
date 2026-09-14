package com.tung.receipt_extractor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"sheets.client-id=test-client-id",
		"sheets.client-secret=test-client-secret",
		"sheets.refresh-token=test-refresh-token",
		"sheets.spreadsheet-id=test-spreadsheet-id",
		"telegram.bot-token=test-bot-token",
		"telegram.allowed-chat-id=123456789",
		"telegram.webhook-secret-token=test-secret"
})
class ReceiptExtractorApplicationTests {

	@Test
	void contextLoads() {
	}

}
