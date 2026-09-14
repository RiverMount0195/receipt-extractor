package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetResponse;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CopyPasteRequest;
import com.google.api.services.sheets.v4.model.GridData;
import com.google.api.services.sheets.v4.model.InsertDimensionRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.UpdateCellsRequest;
import com.google.api.services.sheets.v4.model.UpdateValuesResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void reusesEmptySpacerRowDirectlyBelowDateRow() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Update update = mock(Sheets.Spreadsheets.Values.Update.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(List.of(QUOTED_SHEET_NAME + "!A:G"))).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        // rows: 14/12 | (blank spacer) | 15/12 | (blank spacer) | 16/12
        when(get.execute()).thenReturn(spreadsheetWithRows(
                dateRow("14/12"), blankRow(), dateRow("15/12"), blankRow(), dateRow("16/12")));
        when(spreadsheets.values()).thenReturn(values);
        when(values.update(eq(SPREADSHEET_ID), eq(QUOTED_SHEET_NAME + "!D4:G4"), any(ValueRange.class)))
                .thenReturn(update);
        when(update.setValueInputOption("USER_ENTERED")).thenReturn(update);
        when(update.execute()).thenReturn(new UpdateValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(125000L, "chuyen tien");

        ArgumentCaptor<ValueRange> captor = ArgumentCaptor.forClass(ValueRange.class);
        verify(values).update(eq(SPREADSHEET_ID), eq(QUOTED_SHEET_NAME + "!D4:G4"), captor.capture());
        verify(spreadsheets, never()).batchUpdate(anyString(), any());

        List<Object> row = captor.getValue().getValues().get(0);
        assertEquals(4, row.size());
        assertEquals("chuyen tien", row.get(0));
        assertEquals("Chuyển khoản", row.get(1));
        assertEquals("Chi", row.get(2));
        assertEquals(-125000L, row.get(3));
    }

    @Test
    void insertsNewRowBelowExistingEntryWhenSpacerRowAlreadyHasAnEntry() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        // rows: 14/12 | 15/12 | (already has an entry) | 16/12
        RowData existingEntryRow = new RowData().setValues(List.of(
                new CellData().setFormattedValue(""),
                new CellData().setFormattedValue(""),
                new CellData().setFormattedValue(""),
                new CellData().setFormattedValue("earlier entry")));
        when(get.execute()).thenReturn(spreadsheetWithRows(
                dateRow("14/12"), dateRow("15/12"), existingEntryRow, dateRow("16/12")));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(20000L, "second entry today");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> captor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets).batchUpdate(eq(SPREADSHEET_ID), captor.capture());

        // "15/12" is at index 1, its spacer row (index 2) already has an entry, so the new
        // row must land directly below that entry, at index 3.
        List<Request> requests = captor.getValue().getRequests();
        InsertDimensionRequest insertDimension = requests.get(0).getInsertDimension();
        assertEquals(SHEET_ID, insertDimension.getRange().getSheetId());
        assertEquals(3, insertDimension.getRange().getStartIndex());
        assertEquals(4, insertDimension.getRange().getEndIndex());
        assertEquals(Boolean.TRUE, insertDimension.getInheritFromBefore());

        UpdateCellsRequest updateCells = requests.get(1).getUpdateCells();
        assertEquals(3, updateCells.getStart().getRowIndex());
        assertEquals(0, updateCells.getStart().getColumnIndex());
        assertEquals("userEnteredValue", updateCells.getFields());

        List<CellData> cells = updateCells.getRows().get(0).getValues();
        assertEquals("second entry today", cells.get(3).getUserEnteredValue().getStringValue());
        assertEquals(-20000.0, cells.get(6).getUserEnteredValue().getNumberValue());
    }

    @Test
    void insertsDirectlyBelowDateRowWhenNoRowExistsBelowIt() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        // "15/12" is the very last fetched row - nothing below it at all.
        when(get.execute()).thenReturn(spreadsheetWithRows(dateRow("14/12"), dateRow("15/12")));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(125000L, "chuyen tien");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> captor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets).batchUpdate(eq(SPREADSHEET_ID), captor.capture());

        InsertDimensionRequest insertDimension = captor.getValue().getRequests().get(0).getInsertDimension();
        assertEquals(2, insertDimension.getRange().getStartIndex());
        assertEquals(3, insertDimension.getRange().getEndIndex());
    }

    @Test
    void leavesAmountCellBlankWhenAmountIsNull() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Update update = mock(Sheets.Spreadsheets.Values.Update.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        when(get.execute()).thenReturn(spreadsheetWithRows(dateRow("15/12"), blankRow()));
        when(spreadsheets.values()).thenReturn(values);
        when(values.update(eq(SPREADSHEET_ID), anyString(), any(ValueRange.class))).thenReturn(update);
        when(update.setValueInputOption("USER_ENTERED")).thenReturn(update);
        when(update.execute()).thenReturn(new UpdateValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(null, "no amount");

        ArgumentCaptor<ValueRange> captor = ArgumentCaptor.forClass(ValueRange.class);
        verify(values).update(eq(SPREADSHEET_ID), anyString(), captor.capture());

        List<Object> row = captor.getValue().getValues().get(0);
        assertEquals("", row.get(3));
    }

    @Test
    void insertsMissingDateRowAtEndByCopyingLastDateRowWhenTodayIsAfterAllExistingDates() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Update update = mock(Sheets.Spreadsheets.Values.Update.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        // rows: 13/12 | 14/12 - today (15/12) is after both, so 14/12 (index 1) is the
        // template and the new date row lands at the end (index 2).
        when(get.execute()).thenReturn(spreadsheetWithRows(dateRow("13/12"), dateRow("14/12")));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());
        when(spreadsheets.values()).thenReturn(values);
        when(values.update(eq(SPREADSHEET_ID), eq(QUOTED_SHEET_NAME + "!A3"), any(ValueRange.class)))
                .thenReturn(update);
        when(update.setValueInputOption("USER_ENTERED")).thenReturn(update);
        when(update.execute()).thenReturn(new UpdateValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(50000L, "new date message");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> batchCaptor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets, times(2))
                .batchUpdate(eq(SPREADSHEET_ID), batchCaptor.capture());
        List<BatchUpdateSpreadsheetRequest> batches = batchCaptor.getAllValues();

        // First batch: insert a blank row at index 2, copy 14/12's row (index 1) into it.
        List<Request> dateRowRequests = batches.get(0).getRequests();
        InsertDimensionRequest insertDimension = dateRowRequests.get(0).getInsertDimension();
        assertEquals(2, insertDimension.getRange().getStartIndex());
        assertEquals(3, insertDimension.getRange().getEndIndex());

        CopyPasteRequest copyPaste = dateRowRequests.get(1).getCopyPaste();
        assertEquals(1, copyPaste.getSource().getStartRowIndex());
        assertEquals(2, copyPaste.getSource().getEndRowIndex());
        assertEquals(2, copyPaste.getDestination().getStartRowIndex());
        assertEquals(3, copyPaste.getDestination().getEndRowIndex());
        assertEquals(0, copyPaste.getSource().getStartColumnIndex());
        assertEquals(7, copyPaste.getSource().getEndColumnIndex());
        assertEquals("PASTE_NORMAL", copyPaste.getPasteType());

        // Date column of the new row (sheet row 3, 1-based) is set to today.
        ArgumentCaptor<ValueRange> dateCaptor = ArgumentCaptor.forClass(ValueRange.class);
        verify(values).update(eq(SPREADSHEET_ID), eq(QUOTED_SHEET_NAME + "!A3"), dateCaptor.capture());
        assertEquals("15/12", dateCaptor.getValue().getValues().get(0).get(0));

        // Second batch: the actual entry inserted directly below the new date row (index 3).
        List<Request> entryRequests = batches.get(1).getRequests();
        InsertDimensionRequest entryInsert = entryRequests.get(0).getInsertDimension();
        assertEquals(3, entryInsert.getRange().getStartIndex());
        assertEquals("new date message",
                entryRequests.get(1).getUpdateCells().getRows().get(0).getValues().get(3)
                        .getUserEnteredValue().getStringValue());
    }

    @Test
    void insertsMissingDateRowBetweenExistingDatesInChronologicalOrder() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Update update = mock(Sheets.Spreadsheets.Values.Update.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        // rows: 14/12 | 16/12 - today (15/12) sits between them.
        when(get.execute()).thenReturn(spreadsheetWithRows(dateRow("14/12"), dateRow("16/12")));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());
        when(spreadsheets.values()).thenReturn(values);
        when(values.update(eq(SPREADSHEET_ID), anyString(), any(ValueRange.class))).thenReturn(update);
        when(update.setValueInputOption("USER_ENTERED")).thenReturn(update);
        when(update.execute()).thenReturn(new UpdateValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(50000L, "between message");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> batchCaptor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets, times(2))
                .batchUpdate(eq(SPREADSHEET_ID), batchCaptor.capture());

        List<Request> dateRowRequests = batchCaptor.getAllValues().get(0).getRequests();
        // New date row inserted at index 1 (right before "16/12"), copying "14/12" (index 0).
        assertEquals(1, dateRowRequests.get(0).getInsertDimension().getRange().getStartIndex());
        CopyPasteRequest copyPaste = dateRowRequests.get(1).getCopyPaste();
        assertEquals(0, copyPaste.getSource().getStartRowIndex());
        assertEquals(1, copyPaste.getDestination().getStartRowIndex());
    }

    @Test
    void skipsNonDateRowsWhenLocatingTemplateAndInsertionPoint() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Update update = mock(Sheets.Spreadsheets.Values.Update.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        // rows: 14/12 (index 0) | non-date text (index 1, must be skipped) | 16/12 (index 2)
        RowData nonDateRow = new RowData().setValues(List.of(new CellData().setFormattedValue("some entry")));
        when(get.execute()).thenReturn(spreadsheetWithRows(dateRow("14/12"), nonDateRow, dateRow("16/12")));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());
        when(spreadsheets.values()).thenReturn(values);
        when(values.update(eq(SPREADSHEET_ID), anyString(), any(ValueRange.class))).thenReturn(update);
        when(update.setValueInputOption("USER_ENTERED")).thenReturn(update);
        when(update.execute()).thenReturn(new UpdateValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(50000L, "between message");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> batchCaptor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets, times(2))
                .batchUpdate(eq(SPREADSHEET_ID), batchCaptor.capture());

        List<Request> dateRowRequests = batchCaptor.getAllValues().get(0).getRequests();
        // The non-date row at index 1 must not be mistaken for the insertion point or the
        // template: new date row still lands at index 2 (right before "16/12"), copying
        // "14/12" (index 0), unaffected by the row in between.
        assertEquals(2, dateRowRequests.get(0).getInsertDimension().getRange().getStartIndex());
        CopyPasteRequest copyPaste = dateRowRequests.get(1).getCopyPaste();
        assertEquals(0, copyPaste.getSource().getStartRowIndex());
        assertEquals(2, copyPaste.getDestination().getStartRowIndex());
    }

    @Test
    void insertsMissingDateRowBeforeAllExistingDatesWhenTodayIsEarliest() throws Exception {
        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.Update update = mock(Sheets.Spreadsheets.Values.Update.class);
        Sheets.Spreadsheets.BatchUpdate batchUpdate = mock(Sheets.Spreadsheets.BatchUpdate.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(any())).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        // rows: 16/12 | 17/12 - today (15/12) is earlier than both.
        when(get.execute()).thenReturn(spreadsheetWithRows(dateRow("16/12"), dateRow("17/12")));
        when(spreadsheets.batchUpdate(eq(SPREADSHEET_ID), any(BatchUpdateSpreadsheetRequest.class)))
                .thenReturn(batchUpdate);
        when(batchUpdate.execute()).thenReturn(new BatchUpdateSpreadsheetResponse());
        when(spreadsheets.values()).thenReturn(values);
        when(values.update(eq(SPREADSHEET_ID), anyString(), any(ValueRange.class))).thenReturn(update);
        when(update.setValueInputOption("USER_ENTERED")).thenReturn(update);
        when(update.execute()).thenReturn(new UpdateValuesResponse());

        SheetRowAppender appender = new SheetRowAppender(
                sheetsClient, new SheetsProperties(SPREADSHEET_ID, SHEET_NAME), FIXED_CLOCK);

        appender.insertRow(50000L, "earliest message");

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> batchCaptor =
                ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets, times(2))
                .batchUpdate(eq(SPREADSHEET_ID), batchCaptor.capture());

        List<Request> dateRowRequests = batchCaptor.getAllValues().get(0).getRequests();
        // New row inserted at index 0 (before "16/12"); the template ("16/12", originally
        // at index 0 too) has shifted down to index 1 by the time it's copied from.
        assertEquals(0, dateRowRequests.get(0).getInsertDimension().getRange().getStartIndex());
        CopyPasteRequest copyPaste = dateRowRequests.get(1).getCopyPaste();
        assertEquals(1, copyPaste.getSource().getStartRowIndex());
        assertEquals(0, copyPaste.getDestination().getStartRowIndex());
    }

    @Test
    void appendsPlainRowWhenNoDateRowExistsToUseAsTemplate() throws Exception {
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
        // No row in the sheet has any date text at all.
        when(get.execute()).thenReturn(spreadsheetWithRows(blankRow(), blankRow()));
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
        String quotedRange = "'O''Brien''s Sheet'!A:G";

        Sheets sheetsClient = mock(Sheets.class);
        Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);

        when(sheetsClient.spreadsheets()).thenReturn(spreadsheets);
        when(spreadsheets.get(SPREADSHEET_ID)).thenReturn(get);
        when(get.setRanges(List.of(quotedRange))).thenReturn(get);
        when(get.setIncludeGridData(true)).thenReturn(get);
        when(get.setFields(anyString())).thenReturn(get);
        when(get.execute()).thenReturn(spreadsheetWithRows(dateRow("1/11")));

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

    private RowData dateRow(String date) {
        return new RowData().setValues(List.of(new CellData().setFormattedValue(date)));
    }

    private RowData blankRow() {
        return new RowData();
    }

    private Spreadsheet spreadsheetWithRows(RowData... rows) {
        Sheet sheet = new Sheet()
                .setProperties(new SheetProperties().setSheetId(SHEET_ID))
                .setData(List.of(new GridData().setRowData(new ArrayList<>(List.of(rows)))));
        return new Spreadsheet().setSheets(List.of(sheet));
    }
}
