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
    /** Optional key of the thing the finding is about (e.g. a product id), so a UI can highlight it. */
    private String subject;

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

    /**
     * Builds an ERROR finding about a specific subject (used by the allocation rules).
     *
     * @param code    stable code, e.g. BR-VA-01
     * @param subject key of the thing concerned, e.g. the product id
     * @param column  region code or null
     * @param message human-readable explanation
     * @return the finding
     */
    public static Violation errorFor(String code, String subject, String column, String message) {
        Violation v = new Violation(code, Severity.ERROR, 0, column, message);
        v.subject = subject;
        return v;
    }

    /** Same as {@link #errorFor} but with WARNING severity. */
    public static Violation warningFor(String code, String subject, String column, String message) {
        Violation v = new Violation(code, Severity.WARNING, 0, column, message);
        v.subject = subject;
        return v;
    }

    /** @return the subject key (e.g. product id), or null */
    public String getSubject() { return subject; }

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
