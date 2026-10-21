package com.pifa.rules.model;

/**
 * Fact: a month cell ({@code Forecast_Month} or {@code Planning_Month}) of one row.
 * The rules require every row to hold a valid {@code YYYY-MM} equal to the first row's value.
 */
public class MonthCell {
    private final int rowNo;
    private final String column;
    private final boolean valid;
    private final String value;
    private final String expected;

    /**
     * @param rowNo    1-based spreadsheet row
     * @param column   column header
     * @param valid    true if the cell parsed as a real {@code YYYY-MM} month
     * @param value    the normalised month text (null when invalid)
     * @param expected the month every row must equal (the first row's month)
     */
    public MonthCell(int rowNo, String column, boolean valid, String value, String expected) {
        this.rowNo = rowNo;
        this.column = column;
        this.valid = valid;
        this.value = value;
        this.expected = expected;
    }

    /** @return 1-based spreadsheet row */
    public int getRowNo() { return rowNo; }
    /** @return the column header */
    public String getColumn() { return column; }
    /** @return true if the cell is a valid YYYY-MM */
    public boolean isValid() { return valid; }
    /** @return the normalised month, or null */
    public String getValue() { return value; }
    /** @return the month all rows must share */
    public String getExpected() { return expected; }
}
