package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SheetsConfigTest {

    private static final String TEST_CREDENTIALS_PATH = "src/test/resources/sheets/test-sheets-credentials.properties";

    @Test
    void loadsCredentialsFromPropertiesFile() throws Exception {
        Properties credentials = SheetsConfig.loadCredentials(TEST_CREDENTIALS_PATH);

        assertEquals("test-client-id", credentials.getProperty("client-id"));
        assertEquals("test-client-secret", credentials.getProperty("client-secret"));
        assertEquals("test-refresh-token", credentials.getProperty("refresh-token"));
        assertEquals("test-access-token", credentials.getProperty("access-token"));
        assertEquals("test-spreadsheet-id", credentials.getProperty("spreadsheet-id"));
        assertEquals("Test", credentials.getProperty("sheet-name"));
    }

    @Test
    void buildsSheetsClientFromCredentials() throws Exception {
        SheetsConfig config = new SheetsConfig();

        Sheets sheetsClient = config.sheetsClient(TEST_CREDENTIALS_PATH);

        assertNotNull(sheetsClient);
    }

    @Test
    void buildsSheetsPropertiesFromCredentials() throws Exception {
        SheetsConfig config = new SheetsConfig();

        SheetsProperties sheetsProperties = config.sheetsProperties(TEST_CREDENTIALS_PATH);

        assertEquals("test-spreadsheet-id", sheetsProperties.spreadsheetId());
        assertEquals("Test", sheetsProperties.sheetName());
    }
}
