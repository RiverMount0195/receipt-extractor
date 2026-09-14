package com.tung.receipt_extractor.sheets;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetResponse;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.DimensionRange;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridCoordinate;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RegisterReflectionForBinding({
        ValueRange.class, AppendValuesResponse.class, UpdateValuesResponse.class,
        Spreadsheet.class, Sheet.class, SheetProperties.class, GridData.class, RowData.class,
        CellData.class, ExtendedValue.class, GridCoordinate.class,
        BatchUpdateSpreadsheetRequest.class, BatchUpdateSpreadsheetResponse.class,
        Request.class, InsertDimensionRequest.class, DimensionRange.class, UpdateCellsRequest.class
})
public class SheetRowAppender {

    private static final Logger log = LoggerFactory.getLogger(SheetRowAppender.class);
    private static final String VALUE_INPUT_OPTION = "USER_ENTERED";
    private static final DateTimeFormatter DATE_COLUMN_FORMATTER = DateTimeFormatter.ofPattern("d/M");
    private static final String PAYMENT_METHOD = "Chuyển khoản";
    private static final String INCOME_OUTCOME = "Chi";
    private static final String ROWS_DIMENSION = "ROWS";
    private static final String USER_ENTERED_VALUE_FIELD = "userEnteredValue";

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
        String sheetName = sheetsProperties.sheetName();
        try {
            String today = ZonedDateTime.now(clock).format(DATE_COLUMN_FORMATTER);
            SheetSnapshot snapshot = fetchColumnAData(spreadsheetId, sheetName);
            int dateRowIndex = findDateRowIndex(snapshot.rowData(), today);

            if (dateRowIndex >= 0) {
                insertBelowRow(spreadsheetId, snapshot.sheetId(), dateRowIndex, amount, message);
            } else {
                log.warn("Date {} not found in sheet [spreadsheetId={}, sheetName={}]; appending to end",
                        today, spreadsheetId, sheetName);
                appendToEnd(spreadsheetId, sheetName, amount, message);
            }
        } catch (Exception e) {
            log.error("Failed to insert row into Google Sheet [spreadsheetId={}, sheetName={}]",
                    spreadsheetId, sheetName, e);
        }
    }

    private record SheetSnapshot(int sheetId, List<RowData> rowData) {
    }

    private SheetSnapshot fetchColumnAData(String spreadsheetId, String sheetName) throws Exception {
        Spreadsheet spreadsheet = sheetsClient.spreadsheets()
                .get(spreadsheetId)
                .setRanges(List.of(quotedSheetName(sheetName) + "!A:A"))
                .setIncludeGridData(true)
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

    private void insertBelowRow(String spreadsheetId, int sheetId, int dateRowIndex, Long amount, String message)
            throws Exception {
        int insertIndex = dateRowIndex + 1;

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

    private CellData stringCell(String value) {
        return new CellData().setUserEnteredValue(new ExtendedValue().setStringValue(value));
    }

    private CellData numberCell(double value) {
        return new CellData().setUserEnteredValue(new ExtendedValue().setNumberValue(value));
    }

    private void appendToEnd(String spreadsheetId, String sheetName, Long amount, String message) throws Exception {
        Object amountValue = amount == null ? "" : -amount;
        List<Object> row = List.of(
                "", "", "",
                Objects.requireNonNullElse(message, ""),
                PAYMENT_METHOD,
                INCOME_OUTCOME,
                amountValue);
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
