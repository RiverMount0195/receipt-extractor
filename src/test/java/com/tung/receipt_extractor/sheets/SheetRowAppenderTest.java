package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SheetRowAppenderTest {

    private static final String SPREADSHEET_ID = "sheet-id-123";
    private static final String SHEET_NAME = "Test";

    @Test
    void appendsRowWithBankSourceAmountMessageAndTimestampInOrder() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Append append = mock(Sheets.Spreadsheets.Values.Append.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.values()).thenReturn(values);
        when(values.append(eq(SPREADSHEET_ID), eq(SHEET_NAME), any(ValueRange.class))).thenReturn(append);
        when(append.setValueInputOption("USER_ENTERED")).thenReturn(append);
        when(append.execute()).thenReturn(new AppendValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME));

        appender.appendRow("Vietcombank", 125000L, "chuyen tien");

        ArgumentCaptor<ValueRange> captor = ArgumentCaptor.forClass(ValueRange.class);
        verify(values).append(eq(SPREADSHEET_ID), eq(SHEET_NAME), captor.capture());

        List<Object> row = captor.getValue().getValues().get(0);
        assertEquals("Vietcombank", row.get(0));
        assertEquals(125000L, row.get(1));
        assertEquals("chuyen tien", row.get(2));
        assertDoesNotThrow(() -> Instant.parse((String) row.get(3)));
    }

    @Test
    void convertsNullAmountToEmptyStringInRow() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Append append = mock(Sheets.Spreadsheets.Values.Append.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.values()).thenReturn(values);
        when(values.append(eq(SPREADSHEET_ID), eq(SHEET_NAME), any(ValueRange.class))).thenReturn(append);
        when(append.setValueInputOption("USER_ENTERED")).thenReturn(append);
        when(append.execute()).thenReturn(new AppendValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME));

        appender.appendRow("Zalopay", null, "");

        ArgumentCaptor<ValueRange> captor = ArgumentCaptor.forClass(ValueRange.class);
        verify(values).append(eq(SPREADSHEET_ID), eq(SHEET_NAME), captor.capture());

        List<Object> row = captor.getValue().getValues().get(0);
        assertEquals("", row.get(1));
    }

    @Test
    void swallowsExceptionFromSheetsApiCall() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.values()).thenReturn(values);
        when(values.append(eq(SPREADSHEET_ID), eq(SHEET_NAME), any(ValueRange.class)))
                .thenThrow(new IOException("boom"));

        SheetRowAppender appender = new SheetRowAppender(sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME));

        assertDoesNotThrow(() -> appender.appendRow("Zalopay", null, ""));
    }
}
