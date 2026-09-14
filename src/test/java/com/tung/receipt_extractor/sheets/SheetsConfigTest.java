package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SheetsConfigTest {

    @Test
    void buildsSheetsClientFromCredentials() throws Exception {
        SheetsConfig config = new SheetsConfig();

        Sheets sheetsClient = config.sheetsClient("test-client-id", "test-client-secret", "test-refresh-token");

        assertNotNull(sheetsClient);
    }

    @Test
    void buildsSheetsPropertiesFromCredentials() {
        SheetsConfig config = new SheetsConfig();

        SheetsProperties sheetsProperties = config.sheetsProperties("test-spreadsheet-id");

        assertEquals("test-spreadsheet-id", sheetsProperties.spreadsheetId());
    }
}
