package com.pifa.rules.model;

/**
 * Fact: one quantity cell (a region column in the forecast, or {@code Available_Qty} in inventory).
 *
 * <p>Java parses the cell; if it is not a non-negative whole number it sets {@code valid=false}
 * with a {@code reason}. The rules then add the business limits (maximum, spike warning).
 */
public class QtyCell {
    private final int rowNo;
    private final String column;
    private final boolean valid;
    private final long value;
    private final String reason;
    private final double avgSales6;

    /**
     * @param rowNo     1-based spreadsheet row
     * @param column    column header, e.g. {@code North}
     * @param valid     true if the cell held a whole number &gt;= 0
     * @param value     the parsed quantity (meaningful only when {@code valid})
     * @param reason    why it is invalid (blank, not an integer, negative, formula without value); null if valid
     * @param avgSales6 average monthly sales of the last 6 months for this product and region, or
     *                  -1 when unknown or not applicable (inventory cells); used for the spike warning
     */
    public QtyCell(int rowNo, String column, boolean valid, long value, String reason, double avgSales6) {
        this.rowNo = rowNo;
        this.column = column;
        this.valid = valid;
        this.value = value;
        this.reason = reason;
        this.avgSales6 = avgSales6;
    }

    /** @return 1-based spreadsheet row */
    public int getRowNo() { return rowNo; }
    /** @return the column header */
    public String getColumn() { return column; }
    /** @return true if the cell is a whole number &gt;= 0 */
    public boolean isValid() { return valid; }
    /** @return the parsed quantity */
    public long getValue() { return value; }
    /** @return the reason it is invalid, or null */
    public String getReason() { return reason; }
    /** @return 6-month average sales, or -1 if unknown */
    public double getAvgSales6() { return avgSales6; }
}
