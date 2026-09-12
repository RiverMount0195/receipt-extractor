package com.tung.receipt_extractor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "sheets.credentials-path=src/test/resources/sheets/test-sheets-credentials.properties")
class ReceiptExtractorApplicationTests {

	@Test
	void contextLoads() {
	}

}
