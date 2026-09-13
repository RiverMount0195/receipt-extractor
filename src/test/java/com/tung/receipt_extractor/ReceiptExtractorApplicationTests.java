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
		"sheets.sheet-name=Test"
})
class ReceiptExtractorApplicationTests {

	@Test
	void contextLoads() {
	}

}
