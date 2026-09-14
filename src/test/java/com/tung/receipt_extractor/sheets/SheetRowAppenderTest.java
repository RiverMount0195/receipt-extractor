package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetResponse;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.GridData;
import com.google.api.services.sheets.v4.model.InsertDimensionRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.UpdateCellsRequest;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SheetRowAppenderTest {

    private static final String SPREADSHEET_ID = "sheet-id-123";
    private static final String SHEET_NAME = "Tháng 12";
    private static final String QUOTED_SHEET_NAME = "'Tháng 12'";
    private static final int SHEET_ID = 987;
    // Fixed at 2025-12-15T10:00:00+07:00, i.e. "today" is 15/12
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2025-12-15T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    @Test
    void insertsRowBelowMatchingDateRow() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(List.of(QUOTED_SHEET_NAME + "!A:A"))).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        when(get.execute()).thenReturn(spreadsheetWithDateRows("13/12", "14/12", "15/12", "16/12"));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(125000L, "chuyen tien");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> captor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets).batchUpdate(eq(SPREADSHEET_ID), captor.capture());

        List<Request> requests = captor.getValue().getRequests();
        assertEquals(2, requests.size());

        InsertDimensionRequest insertDimension = requests.get(0).getInsertDimension();
        assertEquals(SHEET_ID, insertDimension.getRange().getSheetId());
        assertEquals("ROWS", insertDimension.getRange().getDimension());
        // "15/12" is at row index 2 (0-based) among the 4 rows, so insertion happens at index 3
        assertEquals(3, insertDimension.getRange().getStartIndex());
        assertEquals(4, insertDimension.getRange().getEndIndex());
        assertEquals(Boolean.TRUE, insertDimension.getInheritFromBefore());

        UpdateCellsRequest updateCells = requests.get(1).getUpdateCells();
        assertEquals(SHEET_ID, updateCells.getStart().getSheetId());
        assertEquals(3, updateCells.getStart().getRowIndex());
        assertEquals(0, updateCells.getStart().getColumnIndex());
        assertEquals("userEnteredValue", updateCells.getFields());

        List<CellData> cells = updateCells.getRows().get(0).getValues();
        assertEquals(7, cells.size());
        assertNull(cells.get(0).getUserEnteredValue());
        assertNull(cells.get(1).getUserEnteredValue());
        assertNull(cells.get(2).getUserEnteredValue());
        assertEquals("chuyen tien", cells.get(3).getUserEnteredValue().getStringValue());
        assertEquals("Chuyển khoản", cells.get(4).getUserEnteredValue().getStringValue());
        assertEquals("Chi", cells.get(5).getUserEnteredValue().getStringValue());
        assertEquals(-125000.0, cells.get(6).getUserEnteredValue().getNumberValue());
    }

    @Test
    void secondSameDayInsertStillLandsDirectlyBelowDateRow() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        // A row for an earlier entry already sits directly below the "15/12" date row.
        when(get.execute()).thenReturn(spreadsheetWithDateRows("14/12", "15/12", "", "16/12"));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(20000L, "second entry today");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> captor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets).batchUpdate(eq(SPREADSHEET_ID), captor.capture());

        // "15/12" is at index 1, so the new row must land at index 2 regardless of the
        // already-inserted row already sitting there (same-day entries always insert
        // immediately below the date row, landing newest-first).
        InsertDimensionRequest insertDimension = captor.getValue().getRequests().get(0).getInsertDimension();
        assertEquals(2, insertDimension.getRange().getStartIndex());
        assertEquals(3, insertDimension.getRange().getEndIndex());
    }

    @Test
    void leavesAmountCellBlankWhenAmountIsNull() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        when(get.execute()).thenReturn(spreadsheetWithDateRows("15/12"));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(null, "no amount");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> captor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets).batchUpdate(eq(SPREADSHEET_ID), captor.capture());

        List<CellData> cells = captor.getValue().getRequests().get(1).getUpdateCells().getRows().get(0).getValues();
        assertNull(cells.get(6).getUserEnteredValue());
    }

    @Test
    void appendsToEndWhenTodaysDateIsNotFoundInSheet() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Append append = mock(Sheets.Spreadsheets.Values.Append.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        when(get.execute()).thenReturn(spreadsheetWithDateRows("1/11", "2/11"));
        when(spreadsheets.values()).thenReturn(values);
        when(values.append(eq(SPREADSHEET_ID), eq(QUOTED_SHEET_NAME + "!A:G"), any(ValueRange.class)))
                .thenReturn(append);
        when(append.setValueInputOption("USER_ENTERED")).thenReturn(append);
        when(append.execute()).thenReturn(new AppendValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(50000L, "fallback message");

        ArgumentCaptor<ValueRange> captor = ArgumentCaptor.forClass(ValueRange.class);
        verify(values).append(eq(SPREADSHEET_ID), eq(QUOTED_SHEET_NAME + "!A:G"), captor.capture());
        verify(spreadsheets, never()).batchUpdate(anyString(), any());

        List<Object> row = captor.getValue().getValues().get(0);
        assertEquals(7, row.size());
        assertEquals("", row.get(0));
        assertEquals("", row.get(1));
        assertEquals("", row.get(2));
        assertEquals("fallback message", row.get(3));
        assertEquals("Chuyển khoản", row.get(4));
        assertEquals("Chi", row.get(5));
        assertEquals(-50000L, row.get(6));
    }

    @Test
    void appendsToEndWhenSheetHasNoGridData() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Append append = mock(Sheets.Spreadsheets.Values.Append.class);

        Sheet sheetWithNoData = new Sheet().setProperties(new SheetProperties().setSheetId(SHEET_ID));

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        when(get.execute()).thenReturn(new Spreadsheet().setSheets(List.of(sheetWithNoData)));
        when(spreadsheets.values()).thenReturn(values);
        when(values.append(eq(SPREADSHEET_ID), eq(QUOTED_SHEET_NAME + "!A:G"), any(ValueRange.class)))
                .thenReturn(append);
        when(append.setValueInputOption("USER_ENTERED")).thenReturn(append);
        when(append.execute()).thenReturn(new AppendValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(1000L, "message");

        verify(values).append(eq(SPREADSHEET_ID), eq(QUOTED_SHEET_NAME + "!A:G"), any(ValueRange.class));
        verify(spreadsheets, never()).batchUpdate(anyString(), any());
    }

    @Test
    void escapesEmbeddedSingleQuoteInSheetName() throws Exception {
        String sheetNameWithQuote = "O'Brien's Sheet";
        String quotedRange = "'O''Brien''s Sheet'!A:A";

        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(List.of(quotedRange))).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        when(get.execute()).thenReturn(spreadsheetWithDateRows("1/11"));

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, sheetNameWithQuote), FIXED_CLOCK);

        appender.insertRow(1000L, "message");

        verify(get).setRanges(List.of(quotedRange));
    }

    @Test
    void swallowsExceptionFromSheetsApiCall() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenThrow(new IOException("boom"));

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        assertDoesNotThrow(() -> appender.insertRow(1000L, "message"));
    }

    private Spreadsheet spreadsheetWithDateRows(String... dates) {
        List<RowData> rows = new ArrayList<>();
        for (String date : dates) {
            rows.add(new RowData().setValues(List.of(new CellData().setFormattedValue(date))));
        }
        Sheet sheet = new Sheet()
                .setProperties(new SheetProperties().setSheetId(SHEET_ID))
                .setData(List.of(new GridData().setRowData(rows)));
        return new Spreadsheet().setSheets(List.of(sheet));
    }
}
