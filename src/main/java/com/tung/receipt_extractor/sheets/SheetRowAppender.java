package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetResponse;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CopyPasteRequest;
import com.google.api.services.sheets.v4.model.DimensionRange;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridCoordinate;
import com.google.api.services.sheets.v4.model.GridData;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.InsertDimensionRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.UpdateCellsRequest;
import com.google.api.services.sheets.v4.model.UpdateValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.MonthDay;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RegisterReflectionForBinding({
        ValueRange.class, AppendValuesResponse.class, UpdateValuesResponse.class,
        Spreadsheet.class, Sheet.class, SheetProperties.class, GridData.class, RowData.class,
        CellData.class, ExtendedValue.class, GridCoordinate.class, GridRange.class,
        BatchUpdateSpreadsheetRequest.class, BatchUpdateSpreadsheetResponse.class,
        Request.class, InsertDimensionRequest.class, DimensionRange.class, UpdateCellsRequest.class,
        CopyPasteRequest.class
})
public class SheetRowAppender {

    private static final Logger log = LoggerFactory.getLogger(SheetRowAppender.class);
    private static final String VALUE_INPUT_OPTION = "USER_ENTERED";
    private static final DateTimeFormatter DATE_COLUMN_FORMATTER = DateTimeFormatter.ofPattern("d/M");
    private static final String PAYMENT_METHOD = "Chuyển khoản";
    private static final String INCOME_OUTCOME = "Chi";
    private static final String ROWS_DIMENSION = "ROWS";
    private static final String USER_ENTERED_VALUE_FIELD = "userEnteredValue";
    private static final String ROW_FIELDS_MASK = "sheets(properties(sheetId),data(rowData(values(formattedValue))))";
    private static final String COPY_PASTE_NORMAL = "PASTE_NORMAL";
    private static final int TRANSACTION_COLUMN_COUNT = 7; // A-G
    private static final String SHEET_NAME_PREFIX = "Tháng ";

    private final Sheets sheetsClient;
    private final SheetsProperties sheetsProperties;
    private final Clock clock;

    public SheetRowAppender(Sheets sheetsClient, SheetsProperties sheetsProperties, Clock clock) {
        this.sheetsClient = sheetsClient;
        this.sheetsProperties = sheetsProperties;
        this.clock = clock;
    }

    public void insertRow(Long amount, String message) {
        String spreadsheetId = sheetsProperties.spreadsheetId();
        ZonedDateTime now = ZonedDateTime.now(clock);
        String sheetName = SHEET_NAME_PREFIX + now.getMonthValue();
        try {
            String today = now.format(DATE_COLUMN_FORMATTER);
            SheetSnapshot snapshot = fetchRowData(spreadsheetId, sheetName);
            int dateRowIndex = findDateRowIndex(snapshot.rowData(), today);

            if (dateRowIndex >= 0) {
                writeUnderDateRow(spreadsheetId, sheetName, snapshot, dateRowIndex, amount, message);
            } else {
                insertMissingDateRowThenEntry(spreadsheetId, sheetName, snapshot, today, amount, message);
            }
        } catch (Exception e) {
            log.error("Failed to insert row into Google Sheet [spreadsheetId={}, sheetName={}]",
                    spreadsheetId, sheetName, e);
        }
    }

    private record SheetSnapshot(int sheetId, List<RowData> rowData) {
    }

    private SheetSnapshot fetchRowData(String spreadsheetId, String sheetName) throws Exception {
        Spreadsheet spreadsheet = sheetsClient.spreadsheets()
                .get(spreadsheetId)
                .setRanges(List.of(quotedSheetName(sheetName) + "!A:G"))
                .setIncludeGridData(true)
                .setFields(ROW_FIELDS_MASK)
                .execute();

        Sheet sheet = spreadsheet.getSheets().get(0);
        int sheetId = sheet.getProperties().getSheetId();
        List<GridData> data = sheet.getData();
        if (data == null || data.isEmpty() || data.get(0).getRowData() == null) {
            return new SheetSnapshot(sheetId, List.of());
        }
        return new SheetSnapshot(sheetId, data.get(0).getRowData());
    }

    private int findDateRowIndex(List<RowData> rowData, String today) {
        for (int i = 0; i < rowData.size(); i++) {
            List<CellData> values = rowData.get(i).getValues();
            if (values == null || values.isEmpty()) {
                continue;
            }
            String text = values.get(0).getFormattedValue();
            if (today.equals(text)) {
                return i;
            }
        }
        return -1;
    }

    private record DateRow(int rowIndex, MonthDay date) {
    }

    private List<DateRow> findDateRows(List<RowData> rowData) {
        List<DateRow> dateRows = new ArrayList<>();
        for (int i = 0; i < rowData.size(); i++) {
            List<CellData> values = rowData.get(i).getValues();
            if (values == null || values.isEmpty()) {
                continue;
            }
            String text = values.get(0).getFormattedValue();
            if (text == null || text.isEmpty()) {
                continue;
            }
            try {
                dateRows.add(new DateRow(i, MonthDay.parse(text, DATE_COLUMN_FORMATTER)));
            } catch (DateTimeParseException e) {
                // Not a date row (e.g. an entry row); ignore.
            }
        }
        return dateRows;
    }

    /**
     * When today's date has no row at all, create one by copying the format and values of
     * the closest existing earlier date row (or the first date row, if today is earlier
     * than every existing date), keeping chronological order, then set its date column to
     * today. The actual entry is then inserted as a fresh row directly below it.
     */
    private void insertMissingDateRowThenEntry(String spreadsheetId, String sheetName, SheetSnapshot snapshot,
            String today, Long amount, String message) throws Exception {
        List<RowData> rowData = snapshot.rowData();
        List<DateRow> dateRows = findDateRows(rowData);

        if (dateRows.isEmpty()) {
            log.warn("No existing date row in sheet [spreadsheetId={}, sheetName={}] to copy as a template; "
                    + "appending plain row", spreadsheetId, sheetName);
            appendToEnd(spreadsheetId, sheetName, amount, message);
            return;
        }

        MonthDay todayMonthDay = MonthDay.parse(today, DATE_COLUMN_FORMATTER);
        int templateRowIndex = dateRows.get(0).rowIndex();
        int insertionIndex = rowData.size();
        for (DateRow dateRow : dateRows) {
            if (dateRow.date().isBefore(todayMonthDay)) {
                templateRowIndex = dateRow.rowIndex();
            } else {
                insertionIndex = dateRow.rowIndex();
                break;
            }
        }

        insertDateRowByCopyingTemplate(spreadsheetId, snapshot.sheetId(), templateRowIndex, insertionIndex);
        try {
            writeDateColumn(spreadsheetId, sheetName, insertionIndex, today);
            insertRowAt(spreadsheetId, snapshot.sheetId(), insertionIndex + 1, amount, message);
        } catch (Exception e) {
            log.error("Created new date row for {} in sheet [spreadsheetId={}, sheetName={}] at row {} but failed "
                    + "to finish writing it - the date text and/or the entry may be missing; manual reconciliation "
                    + "needed", today, spreadsheetId, sheetName, insertionIndex + 1, e);
            throw e;
        }
    }

    private void insertDateRowByCopyingTemplate(String spreadsheetId, int sheetId, int templateRowIndex,
            int insertionIndex) throws Exception {
        DimensionRange insertRange = new DimensionRange()
                .setSheetId(sheetId)
                .setDimension(ROWS_DIMENSION)
                .setStartIndex(insertionIndex)
                .setEndIndex(insertionIndex + 1);
        Request insertRequest = new Request().setInsertDimension(
                new InsertDimensionRequest().setRange(insertRange).setInheritFromBefore(true));

        // The insert above shifts rows at/after insertionIndex down by one; account for
        // that when the template row sits at or after the insertion point (i.e. today is
        // earlier than every existing date, so the template is the first date row, which
        // is also the insertion point).
        int adjustedTemplateRowIndex = templateRowIndex >= insertionIndex ? templateRowIndex + 1 : templateRowIndex;

        GridRange source = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(adjustedTemplateRowIndex)
                .setEndRowIndex(adjustedTemplateRowIndex + 1)
                .setStartColumnIndex(0)
                .setEndColumnIndex(TRANSACTION_COLUMN_COUNT);
        GridRange destination = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(insertionIndex)
                .setEndRowIndex(insertionIndex + 1)
                .setStartColumnIndex(0)
                .setEndColumnIndex(TRANSACTION_COLUMN_COUNT);
        Request copyRequest = new Request().setCopyPaste(
                new CopyPasteRequest().setSource(source).setDestination(destination).setPasteType(COPY_PASTE_NORMAL));

        BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest()
                .setRequests(List.of(insertRequest, copyRequest));

        sheetsClient.spreadsheets().batchUpdate(spreadsheetId, body).execute();
    }

    private void writeDateColumn(String spreadsheetId, String sheetName, int rowIndex, String dateText)
            throws Exception {
        int sheetRowNumber = rowIndex + 1; // A1 notation is 1-based
        String range = quotedSheetName(sheetName) + "!A" + sheetRowNumber;
        ValueRange body = new ValueRange().setValues(List.of(List.of(dateText)));

        sheetsClient.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption(VALUE_INPUT_OPTION)
                .execute();
    }

    /**
     * Each date row is followed by a template spacer row reserved for that day's entries.
     * Reuse it in place while it's blank; once it holds an entry, subsequent same-day
     * entries insert directly below the last one, so entries stack chronologically under
     * the date.
     */
    private void writeUnderDateRow(String spreadsheetId, String sheetName, SheetSnapshot snapshot,
            int dateRowIndex, Long amount, String message) throws Exception {
        int belowIndex = dateRowIndex + 1;
        List<RowData> rowData = snapshot.rowData();
        RowData belowRow = belowIndex < rowData.size() ? rowData.get(belowIndex) : null;

        if (belowRow == null) {
            insertRowAt(spreadsheetId, snapshot.sheetId(), belowIndex, amount, message);
        } else if (isRowEmpty(belowRow)) {
            updateRowInPlace(spreadsheetId, sheetName, belowIndex, amount, message);
        } else {
            insertRowAt(spreadsheetId, snapshot.sheetId(), belowIndex + 1, amount, message);
        }
    }

    private boolean isRowEmpty(RowData row) {
        List<CellData> values = row.getValues();
        if (values == null || values.isEmpty()) {
            return true;
        }
        for (CellData cell : values) {
            String text = cell.getFormattedValue();
            if (text != null && !text.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void insertRowAt(String spreadsheetId, int sheetId, int insertIndex, Long amount, String message)
            throws Exception {
        DimensionRange range = new DimensionRange()
                .setSheetId(sheetId)
                .setDimension(ROWS_DIMENSION)
                .setStartIndex(insertIndex)
                .setEndIndex(insertIndex + 1);

        Request insertRequest = new Request().setInsertDimension(
                new InsertDimensionRequest().setRange(range).setInheritFromBefore(true));

        Request updateRequest = new Request().setUpdateCells(
                new UpdateCellsRequest()
                        .setStart(new GridCoordinate().setSheetId(sheetId).setRowIndex(insertIndex).setColumnIndex(0))
                        .setRows(List.of(buildRowData(amount, message)))
                        .setFields(USER_ENTERED_VALUE_FIELD));

        BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest()
                .setRequests(List.of(insertRequest, updateRequest));

        sheetsClient.spreadsheets().batchUpdate(spreadsheetId, body).execute();
    }

    private void updateRowInPlace(String spreadsheetId, String sheetName, int rowIndex, Long amount, String message)
            throws Exception {
        int sheetRowNumber = rowIndex + 1; // A1 notation is 1-based
        String range = quotedSheetName(sheetName) + "!D" + sheetRowNumber + ":G" + sheetRowNumber;
        ValueRange body = new ValueRange().setValues(List.of(transactionColumns(amount, message)));

        sheetsClient.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption(VALUE_INPUT_OPTION)
                .execute();
    }

    private RowData buildRowData(Long amount, String message) {
        List<CellData> cells = new ArrayList<>();
        cells.add(new CellData()); // A: date (left blank)
        cells.add(new CellData()); // B: unused
        cells.add(new CellData()); // C: unused
        cells.add(stringCell(Objects.requireNonNullElse(message, ""))); // D: Description
        cells.add(stringCell(PAYMENT_METHOD)); // E: Payment Method
        cells.add(stringCell(INCOME_OUTCOME)); // F: Income / Outcome
        cells.add(amount == null ? new CellData() : numberCell(-amount)); // G: Amount
        return new RowData().setValues(cells);
    }

    private List<Object> transactionColumns(Long amount, String message) {
        Object amountValue = amount == null ? "" : -amount;
        return List.of(
                Objects.requireNonNullElse(message, ""),
                PAYMENT_METHOD,
                INCOME_OUTCOME,
                amountValue);
    }

    private CellData stringCell(String value) {
        return new CellData().setUserEnteredValue(new ExtendedValue().setStringValue(value));
    }

    private CellData numberCell(double value) {
        return new CellData().setUserEnteredValue(new ExtendedValue().setNumberValue(value));
    }

    private void appendToEnd(String spreadsheetId, String sheetName, Long amount, String message) throws Exception {
        List<Object> row = new ArrayList<>();
        row.add("");
        row.add("");
        row.add("");
        row.addAll(transactionColumns(amount, message));
        ValueRange body = new ValueRange().setValues(List.of(row));

        sheetsClient.spreadsheets().values()
                .append(spreadsheetId, quotedSheetName(sheetName) + "!A:G", body)
                .setValueInputOption(VALUE_INPUT_OPTION)
                .execute();
    }

    private String quotedSheetName(String sheetName) {
        return "'" + sheetName.replace("'", "''") + "'";
    }
}
