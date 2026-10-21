package com.pifa.golive;

import com.pifa.common.ApiException;
import com.pifa.ingestion.ExcelReader;
import com.pifa.ingestion.ExcelReader.RawCell;
import com.pifa.rules.model.Violation;
import com.pifa.storage.VirusScanner;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Shared file handling for the go-live tools (matching report, sales-history import, reconciliation).
 *
 * <p>These files come from Admins rather than upstream systems, but they are still untrusted: every file is
 * virus-scanned and read through {@link ExcelReader}, which rejects macros, encrypted files, external links and
 * zip bombs. Cell parsing here follows the same rules as forecast ingestion (whole numbers &gt;= 0, YYYY-MM months).
 */
final class GoLiveFiles {

    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private GoLiveFiles() {}

    /**
     * Virus-scans a file, failing closed.
     *
     * @param scanner the configured scanner
     * @param bytes   the whole file
     * @throws ApiException 422 FILE_INFECTED if malware was found, 503 SCAN_UNAVAILABLE if the scanner gave no verdict
     */
    static void scan(VirusScanner scanner, byte[] bytes) {
        boolean clean;
        try {
            clean = scanner.isClean(bytes);
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SCAN_UNAVAILABLE", "The virus scanner is not available; try again later");
        }
        if (!clean) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_INFECTED", "The file was rejected by the virus scanner");
    }

    /**
     * Reads the first of the given worksheets that exists.
     *
     * @param bytes    the .xlsx file
     * @param sheets   sheet names to try, in order (e.g. {@code Forecast}, {@code Inventory})
     * @param required headers that must exist
     * @param known    all accepted headers (others only produce a warning)
     * @param maxRows  row limit
     * @return the sheet data
     * @throws ApiException 422 FILE_INVALID listing the file-level problems (wrong type, no such sheet, missing header ...)
     */
    static ExcelReader.SheetData read(byte[] bytes, List<String> sheets, List<String> required, Set<String> known, int maxRows) {
        ExcelReader.SheetData last = null;
        for (String sheet : sheets) {
            last = ExcelReader.read(bytes, sheet, required, known, maxRows);
            boolean sheetMissing = last.violations().stream().anyMatch(v -> "SHEET_MISSING".equals(v.getCode()));
            if (!sheetMissing) break;
        }
        if (last.hasErrors()) {
            List<String> problems = new ArrayList<>();
            for (Violation v : last.violations()) {
                if (v.getSeverity() == Violation.Severity.ERROR) problems.add(v.getMessage());
            }
            if (problems.stream().anyMatch(p -> p.startsWith("Sheet '"))) problems = List.of("No sheet named " + String.join(" or ", sheets));
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_INVALID", String.join("; ", problems), Map.of("problems", problems));
        }
        return last;
    }

    /**
     * Builds an empty template workbook: one sheet with a bold header row.
     *
     * @param sheet   worksheet name, e.g. {@code Sales}
     * @param headers column headers in order
     * @return the .xlsx bytes
     */
    static byte[] template(String sheet, List<String> headers) {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var s = wb.createSheet(sheet);
            var bold = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            var row = s.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                var cell = row.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(bold);
                s.setColumnWidth(i, i == 0 ? 40 * 256 : 14 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not build template", e);
        }
    }

    /** @return the cell as trimmed text (whole numbers without ".0"), or null if empty */
    static String text(RawCell c) {
        if (c == null || c.value() == null) return null;
        Object v = c.value();
        if (v instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * @param c a quantity cell
     * @return the value as a whole number &gt;= 0 (numeric cells or integer-looking text), or null if blank or invalid
     */
    static Integer qty(RawCell c) {
        if (c == null || c.value() == null || c.uncachedFormula()) return null;
        double d;
        if (c.value() instanceof Double dd) d = dd;
        else {
            try { d = Double.parseDouble(c.value().toString().trim()); } catch (NumberFormatException e) { return null; }
        }
        if (d != Math.rint(d) || d < 0 || d > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    /** @return the cell as {@code YYYY-MM} (a date cell becomes its month), or null if blank or invalid */
    static String month(RawCell c) {
        if (c == null || c.value() == null) return null;
        if (c.value() instanceof LocalDate d) return YearMonth.from(d).toString();
        String s = c.value().toString().trim();
        return MONTH.matcher(s).matches() ? s : null;
    }
}
