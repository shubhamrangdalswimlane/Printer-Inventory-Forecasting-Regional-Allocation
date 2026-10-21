package com.pifa.ingestion;

import com.pifa.rules.model.Violation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Safely reads one worksheet of an {@code .xlsx} file into plain Java objects.
 *
 * <p>This class does the <em>mechanical</em> checks (file type, macros, encryption, sheet and
 * header presence, row limit) and returns raw cell values. It does not judge business content;
 * the Drools ingestion rules do that. Findings are returned as {@link Violation}s, never thrown.
 */
public final class ExcelReader {

    private ExcelReader() {}

    /**
     * One cell as read from the file.
     *
     * @param value           {@code String}, {@code Double}, {@code LocalDate}, {@code Boolean} or {@code null} for an empty cell
     * @param uncachedFormula true if the cell is a formula that has no stored result (cannot be trusted)
     */
    public record RawCell(Object value, boolean uncachedFormula) {}

    /**
     * One data row.
     *
     * @param rowNo 1-based spreadsheet row number (the header is row 1, so data starts at 2)
     * @param cells cells keyed by lower-case header name; only headers present in the file appear
     */
    public record RowData(int rowNo, Map<String, RawCell> cells) {
        /** @param header header in any letter case @return the cell, or null if the column is absent */
        public RawCell get(String header) { return cells.get(header.toLowerCase(Locale.ROOT)); }
    }

    /**
     * Result of reading a sheet.
     *
     * @param rows       data rows (blank rows skipped); empty when a blocking file-level problem was found
     * @param violations file-level findings (FILE_TYPE, SHEET_MISSING, HEADER_MISSING, HEADER_UNKNOWN, ROW_LIMIT)
     */
    public record SheetData(List<RowData> rows, List<Violation> violations) {
        /** @return true if any finding is an ERROR (the file must be rejected) */
        public boolean hasErrors() {
            return violations.stream().anyMatch(v -> v.getSeverity() == Violation.Severity.ERROR);
        }
    }

    /**
     * Reads the named worksheet.
     *
     * <p>Checks, in order: the bytes are a ZIP-based OOXML file (not legacy/encrypted OLE2), contain
     * no macro project and no external links; the workbook opens; the sheet exists; every required
     * header is present (case-insensitive); the number of data rows is within the limit. Unknown
     * headers only produce a WARNING and are ignored.
     *
     * @param bytes     the whole file
     * @param sheetName worksheet to read, e.g. {@code Forecast} (case-insensitive)
     * @param required  headers that must exist
     * @param known     all headers the template defines (required + optional); others raise HEADER_UNKNOWN
     * @param maxRows   maximum data rows allowed
     * @return the rows and findings; never null
     */
    public static SheetData read(byte[] bytes, String sheetName, List<String> required, Set<String> known, int maxRows) {
        List<Violation> found = new ArrayList<>();

        String typeProblem = checkContainer(bytes);
        if (typeProblem != null) {
            found.add(Violation.error("FILE_TYPE", 0, null, typeProblem));
            return new SheetData(List.of(), found);
        }

        // Defence against zip bombs: refuse entries that expand far beyond their compressed size.
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(200L * 1024 * 1024);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet(sheetName);
            if (sheet == null) {
                found.add(Violation.error("SHEET_MISSING", 0, null, "Sheet '" + sheetName + "' not found"));
                return new SheetData(List.of(), found);
            }

            // Header row = first row. Map lower-case header -> column index.
            Row header = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> index = new LinkedHashMap<>();
            if (header != null) {
                for (Cell c : header) {
                    Object v = value(c).value();
                    if (v != null && !v.toString().isBlank()) {
                        index.putIfAbsent(v.toString().trim().toLowerCase(Locale.ROOT), c.getColumnIndex());
                    }
                }
            }
            Set<String> knownLower = new java.util.HashSet<>();
            known.forEach(k -> knownLower.add(k.toLowerCase(Locale.ROOT)));
            for (String r : required) {
                if (!index.containsKey(r.toLowerCase(Locale.ROOT))) {
                    found.add(Violation.error("HEADER_MISSING", 1, r, "Required column '" + r + "' is missing"));
                }
            }
            for (String h : index.keySet()) {
                if (!knownLower.contains(h)) {
                    found.add(Violation.warning("HEADER_UNKNOWN", 1, h, "Unknown column '" + h + "' was ignored"));
                }
            }
            if (found.stream().anyMatch(v -> v.getSeverity() == Violation.Severity.ERROR)) {
                return new SheetData(List.of(), found);
            }

            List<RowData> rows = new ArrayList<>();
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Map<String, RawCell> cells = new HashMap<>();
                boolean any = false;
                for (Map.Entry<String, Integer> e : index.entrySet()) {
                    Cell c = row.getCell(e.getValue());
                    RawCell rc = c == null ? new RawCell(null, false) : value(c);
                    if (rc.value() != null || rc.uncachedFormula()) any = true;
                    cells.put(e.getKey(), rc);
                }
                if (!any) continue; // fully blank row
                if (rows.size() >= maxRows) {
                    found.add(Violation.error("ROW_LIMIT", 0, null, "File has more than " + maxRows + " data rows"));
                    return new SheetData(List.of(), found);
                }
                rows.add(new RowData(i + 1, cells));
            }
            return new SheetData(rows, found);
        } catch (IOException | RuntimeException e) {
            found.add(Violation.error("FILE_TYPE", 0, null, "File cannot be opened as .xlsx: " + e.getMessage()));
            return new SheetData(List.of(), found);
        }
    }

    /**
     * Inspects the raw bytes before POI is allowed to open them.
     *
     * @param b the whole file
     * @return null if it looks like a plain .xlsx; otherwise a message saying why it is rejected
     *         (wrong signature, legacy/encrypted OLE2 file, macro project, or external links)
     */
    static String checkContainer(byte[] b) {
        if (b.length < 4) return "File is empty or too small to be .xlsx";
        // OLE2 header: legacy .xls, or a password-protected OOXML package.
        if ((b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0) {
            return "File is a legacy .xls or an encrypted/password-protected workbook";
        }
        // ZIP local-file header "PK\3\4": a genuine .xlsx is a zip.
        if (!(b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4)) return "File is not a real .xlsx (bad signature)";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry e;
            int count = 0;
            while ((e = zip.getNextEntry()) != null) {
                if (++count > 2000) return "File contains too many parts";
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.contains("vbaproject")) return "Macro-enabled workbooks are not accepted";
                if (n.startsWith("xl/externallinks/")) return "Workbooks with external links are not accepted";
            }
        } catch (IOException ex) {
            return "File is corrupt: " + ex.getMessage();
        }
        return null;
    }

    /**
     * Converts one POI cell to a {@link RawCell}.
     *
     * <p>Numbers formatted as dates become {@link LocalDate}. For formulas the stored (cached)
     * result is used; a formula that has no stored result is flagged {@code uncachedFormula}.
     *
     * @param c the POI cell
     * @return the cell value wrapper
     */
    static RawCell value(Cell c) {
        CellType t = c.getCellType();
        if (t == CellType.FORMULA) {
            if (c instanceof XSSFCell x && !x.getCTCell().isSetV()) return new RawCell(null, true);
            t = c.getCachedFormulaResultType();
        }
        return switch (t) {
            case STRING -> new RawCell(c.getStringCellValue(), false);
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                ? new RawCell(c.getLocalDateTimeCellValue().toLocalDate(), false)
                : new RawCell(c.getNumericCellValue(), false);
            case BOOLEAN -> new RawCell(c.getBooleanCellValue(), false);
            default -> new RawCell(null, false);
        };
    }
}
