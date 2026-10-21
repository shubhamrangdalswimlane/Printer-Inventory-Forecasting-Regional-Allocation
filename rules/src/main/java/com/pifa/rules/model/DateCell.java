package com.pifa.rules.model;

/** Fact: the {@code As_Of_Date} cell of one inventory row. */
public class DateCell {
    private final int rowNo;
    private final boolean valid;
    private final boolean consistent;
    private final boolean future;

    /**
     * @param rowNo      1-based spreadsheet row
     * @param valid      true if the cell parsed as a real date
     * @param consistent true if it equals the first row's date (the whole file must share one date)
     * @param future     true if the date is after today
     */
    public DateCell(int rowNo, boolean valid, boolean consistent, boolean future) {
        this.rowNo = rowNo;
        this.valid = valid;
        this.consistent = consistent;
        this.future = future;
    }

    /** @return 1-based spreadsheet row */
    public int getRowNo() { return rowNo; }
    /** @return true if the cell is a real date */
    public boolean isValid() { return valid; }
    /** @return true if it equals the first row's date */
    public boolean isConsistent() { return consistent; }
    /** @return true if the date is in the future */
    public boolean isFuture() { return future; }
}
