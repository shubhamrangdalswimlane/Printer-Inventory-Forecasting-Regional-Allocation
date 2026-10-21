package com.pifa.rules.model;

/**
 * Fact: the {@code Product_name} cell of one spreadsheet row, after matching against the product master.
 *
 * <p>Java does the lookup (normalisation + alias table) and sets {@code known}; the rules decide
 * which finding that deserves (blank, unknown, duplicate).
 */
public class ProductRow {
    private final int rowNo;
    private final String rawName;
    private final boolean known;
    private final String normalizedName;

    /**
     * @param rowNo          1-based spreadsheet row
     * @param rawName        the text exactly as typed in the file (may be null)
     * @param known          true if it matched a product or alias in the master
     * @param normalizedName normalised form (trimmed, single spaces, case-folded); used to find duplicates
     */
    public ProductRow(int rowNo, String rawName, boolean known, String normalizedName) {
        this.rowNo = rowNo;
        this.rawName = rawName;
        this.known = known;
        this.normalizedName = normalizedName;
    }

    /** @return 1-based spreadsheet row */
    public int getRowNo() { return rowNo; }
    /** @return the raw cell text, may be null */
    public String getRawName() { return rawName; }
    /** @return true if the name matched the master */
    public boolean isKnown() { return known; }
    /** @return the normalised name */
    public String getNormalizedName() { return normalizedName; }
    /** @return true if the cell is null or only whitespace (read by rules as {@code blank}) */
    public boolean isBlank() { return rawName == null || rawName.isBlank(); }
}
