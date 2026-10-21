package com.pifa.rules.model;

/**
 * One finding produced by validation rules (a "violation" in PRD terms).
 *
 * <p>ERROR findings block the whole file (all-or-nothing, PRD 8.4). WARNING findings are shown to
 * the user but do not block.
 */
public class Violation {

    /** How serious a finding is. */
    public enum Severity { ERROR, WARNING }

    private final String code;
    private final Severity severity;
    private final int rowNo;
    private final String column;
    private final String message;

    /**
     * @param code     stable machine code from PRD 8.4, e.g. {@code PRODUCT_UNKNOWN}
     * @param severity ERROR blocks the file; WARNING does not
     * @param rowNo    1-based spreadsheet row the finding refers to; 0 means the whole file
     * @param column   column header the finding refers to, or {@code null}
     * @param message  human-readable explanation shown in the error report
     */
    public Violation(String code, Severity severity, int rowNo, String column, String message) {
        this.code = code;
        this.severity = severity;
        this.rowNo = rowNo;
        this.column = column;
        this.message = message;
    }

    /** Convenience factory used by DRL files: builds an ERROR finding. */
    public static Violation error(String code, int rowNo, String column, String message) {
        return new Violation(code, Severity.ERROR, rowNo, column, message);
    }

    /** Convenience factory used by DRL files: builds a WARNING finding. */
    public static Violation warning(String code, int rowNo, String column, String message) {
        return new Violation(code, Severity.WARNING, rowNo, column, message);
    }

    /** @return the stable machine code */
    public String getCode() { return code; }
    /** @return ERROR or WARNING */
    public Severity getSeverity() { return severity; }
    /** @return 1-based row number, or 0 for a file-level finding */
    public int getRowNo() { return rowNo; }
    /** @return the column header, or null */
    public String getColumn() { return column; }
    /** @return the human-readable message */
    public String getMessage() { return message; }
}
