package com.pifa.ingestion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds {@code .xlsx} files in the template format of PRD 8.3. One writer serves four uses:
 * the downloadable blank templates, normalised exports of a version, the synthetic-data
 * generator and the negative test pack. Because they share the writer, an exported file is
 * guaranteed to re-upload cleanly.
 */
public final class ExcelFiles {

    private ExcelFiles() {}

    /** One forecast row: product, quantity per region code, optional comment. */
    public record ForecastRow(String product, Map<String, Integer> qtyByRegion, String comment) {}

    /** One inventory row: product and available quantity. */
    public record InventoryRow(String product, int availableQty) {}

    /**
     * Protects against formula injection (CSV/Excel injection): text that starts with
     * {@code = + - @ TAB CR} would be run as a formula when opened, so a quote is put in front.
     *
     * @param s any text (may be null)
     * @return the text, prefixed with {@code '} if it starts with a dangerous character; "" for null
     */
    public static String safe(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) return "'" + s;
        return s;
    }

    /**
     * Writes a forecast workbook (sheet {@code Forecast}).
     *
     * @param regions region codes in column order (their capitalised form is the header, e.g. North)
     * @param rows    the data rows
     * @param month   value for every row's {@code Forecast_Month}, written as text so Excel cannot turn it into a date
     * @param meta    optional key/value pairs written to an extra {@code Metadata} sheet (export info); may be null
     * @return the .xlsx bytes
     */
    public static byte[] forecast(List<String> regions, List<ForecastRow> rows, String month, Map<String, String> meta) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Forecast");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Product_name");
            h.createCell(1).setCellValue("Forecast_Month");
            for (int i = 0; i < regions.size(); i++) h.createCell(2 + i).setCellValue(capitalise(regions.get(i)));
            h.createCell(2 + regions.size()).setCellValue("Comments");
            int r = 1;
            for (ForecastRow fr : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(safe(fr.product()));
                row.createCell(1).setCellValue(month);
                for (int i = 0; i < regions.size(); i++) {
                    row.createCell(2 + i).setCellValue(fr.qtyByRegion().getOrDefault(regions.get(i), 0));
                }
                if (fr.comment() != null) row.createCell(2 + regions.size()).setCellValue(safe(fr.comment()));
            }
            writeMeta(wb, meta);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes an inventory workbook (sheet {@code Inventory}).
     *
     * @param rows          the data rows
     * @param asOf          value for every row's {@code As_Of_Date} (written as a real date cell)
     * @param planningMonth value for {@code Planning_Month} on every row, or null to leave the column empty
     * @param meta          optional key/value pairs for a {@code Metadata} sheet; may be null
     * @return the .xlsx bytes
     */
    public static byte[] inventory(List<InventoryRow> rows, LocalDate asOf, String planningMonth, Map<String, String> meta) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Inventory");
            CreationHelper helper = wb.getCreationHelper();
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            Row h = sheet.createRow(0);
            String[] heads = {"Product_name", "Available_Qty", "As_Of_Date", "Planning_Month"};
            for (int i = 0; i < heads.length; i++) h.createCell(i).setCellValue(heads[i]);
            int r = 1;
            for (InventoryRow ir : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(safe(ir.product()));
                row.createCell(1).setCellValue(ir.availableQty());
                var d = row.createCell(2);
                d.setCellValue(asOf);
                d.setCellStyle(dateStyle);
                if (planningMonth != null) row.createCell(3).setCellValue(planningMonth);
            }
            writeMeta(wb, meta);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Adds the optional {@code Metadata} sheet (version id, cycle, exported by, time). */
    private static void writeMeta(XSSFWorkbook wb, Map<String, String> meta) {
        if (meta == null || meta.isEmpty()) return;
        Sheet s = wb.createSheet("Metadata");
        int r = 0;
        for (Map.Entry<String, String> e : new LinkedHashMap<>(meta).entrySet()) {
            Row row = s.createRow(r++);
            row.createCell(0).setCellValue(safe(e.getKey()));
            row.createCell(1).setCellValue(safe(e.getValue()));
        }
    }

    /** @param code upper-case region code, e.g. NORTH @return {@code North} */
    public static String capitalise(String code) {
        return code.isEmpty() ? code : code.charAt(0) + code.substring(1).toLowerCase();
    }
}
