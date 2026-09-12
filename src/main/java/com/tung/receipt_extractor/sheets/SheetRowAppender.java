package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class SheetRowAppender {

    private static final Logger log = LoggerFactory.getLogger(SheetRowAppender.class);
    private static final String VALUE_INPUT_OPTION = "USER_ENTERED";

    private final Sheets sheetsClient;
    private final SheetsProperties sheetsProperties;

    public SheetRowAppender(Sheets sheetsClient, SheetsProperties sheetsProperties) {
        this.sheetsClient = sheetsClient;
        this.sheetsProperties = sheetsProperties;
    }

    public void appendRow(String bankSource, Long amount, String message) {
        try {
            Object amountValue = amount == null ? "" : amount;
            List<Object> row = List.of(bankSource, amountValue, message, Instant.now().toString());
            ValueRange body = new ValueRange().setValues(List.of(row));

            sheetsClient.spreadsheets().values()
                    .append(sheetsProperties.spreadsheetId(), sheetsProperties.sheetName(), body)
                    .setValueInputOption(VALUE_INPUT_OPTION)
                    .execute();
        } catch (Exception e) {
            log.error("Failed to append row to Google Sheet [spreadsheetId={}, sheetName={}]",
                    sheetsProperties.spreadsheetId(), sheetsProperties.sheetName(), e);
        }
    }
}
