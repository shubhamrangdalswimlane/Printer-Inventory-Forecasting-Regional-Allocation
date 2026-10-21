package com.pifa.synthetic;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.config.PifaProperties;
import com.pifa.dataset.DatasetType;
import com.pifa.ingestion.ExcelFiles;
import com.pifa.ingestion.ExcelFiles.ForecastRow;
import com.pifa.ingestion.ExcelFiles.InventoryRow;
import com.pifa.ingestion.IngestionService;
import com.pifa.ingestion.IngestionService.IngestRequest;
import com.pifa.ingestion.IngestionService.IngestResult;
import com.pifa.master.MasterDataService;
import com.pifa.security.CurrentUser;
import com.pifa.storage.FileStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates realistic fake data so the whole pipeline can be exercised before real feeds exist
 * (PRD section 12): a 25-model product master, 24 months of sales history, a forecast file, an
 * inventory file with about 40% constrained models, and a "negative pack" of deliberately bad files.
 *
 * <p>Generation is <b>deterministic</b>: the same seed always produces the same data. Files are built
 * in the real Excel templates and pushed through {@link IngestionService}, exactly like production
 * files; their versions are tagged {@code SYNTHETIC}. The whole service is disabled in production.
 */
@Service
public class SyntheticDataService {

    /** One bad file and the finding code it must be rejected with. */
    public record NegativeFile(String name, byte[] bytes, String expectedCode, boolean needsValidFilesFirst) {}

    /** Invented models only (no real brands): mono laser, colour laser, ink tank, MFP and photo families. */
    static final List<String> PRODUCTS = List.of(
        "Mono Laser M100", "Mono Laser M110", "Mono Laser M120", "Mono Laser M200", "Mono Laser M210", "Mono Laser M300",
        "Color Laser C220", "Color Laser C230", "Color Laser C320", "Color Laser C330", "Color Laser C420", "A3 Color Laser L900",
        "Ink Tank T310", "Ink Tank T320", "Ink Tank T410", "Ink Tank T420", "Ink Tank T510",
        "MFP Laser X450", "MFP Laser X460", "MFP Laser X550", "MFP Laser X560", "MFP Laser X650",
        "Photo Ink P700", "Photo Ink P710", "A3 Mono Laser L910");

    /** Regional share of demand (must follow the order of the region master: NORTH, EAST, WEST, SOUTH). */
    private static final double[] MIX = {0.30, 0.25, 0.25, 0.20};

    private final MasterDataService master;
    private final IngestionService ingestion;
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final FileStore files;
    private final AuditService audit;
    private final PifaProperties props;

    /**
     * @throws IllegalStateException at startup if synthetic data is enabled while the {@code prod}
     *         profile is active (environment guard, PRD section 12)
     */
    public SyntheticDataService(MasterDataService master, IngestionService ingestion, JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                                FileStore files, AuditService audit, PifaProperties props, Environment env) {
        this.master = master;
        this.ingestion = ingestion;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.files = files;
        this.audit = audit;
        this.props = props;
        boolean prod = List.of(env.getActiveProfiles()).contains("prod");
        if (prod && props.synthetic().enabled()) {
            throw new IllegalStateException("pifa.synthetic.enabled must be false when the prod profile is active");
        }
    }

    /** @throws ApiException 403 when the generator is disabled by configuration */
    private void requireEnabled() {
        if (!props.synthetic().enabled()) throw new ApiException(HttpStatus.FORBIDDEN, "SYNTHETIC_DISABLED", "Synthetic data is disabled in this environment");
    }

    /**
     * Creates the product master and 24 months of sales history if they are missing (idempotent).
     *
     * @param seed random seed; the same seed gives the same history
     */
    @Transactional
    public void seedMaster(long seed) {
        requireEnabled();
        List<String> regions = master.activeRegionCodes();
        YearMonth last = YearMonth.now().minusMonths(1);
        List<Object[]> rows = new ArrayList<>();
        for (int p = 0; p < PRODUCTS.size(); p++) {
            UUID pid = master.addProduct(PRODUCTS.get(p), "SYNTHETIC");
            Random rnd = new Random(seed * 1000 + p);
            double base = 30 + rnd.nextInt(370);
            for (int m = 23; m >= 0; m--) {
                YearMonth month = last.minusMonths(m);
                for (int r = 0; r < regions.size(); r++) {
                    double trend = 1 + 0.01 * (23 - m);
                    double qty = base * MIX[r % MIX.length] * season(month.getMonthValue()) * trend * (0.9 + 0.2 * rnd.nextDouble());
                    rows.add(new Object[]{pid, regions.get(r), month.toString(), (int) Math.round(qty)});
                }
            }
        }
        jdbcTemplate.batchUpdate("INSERT INTO sales_history (product_id, region_code, month, qty, source) VALUES (?,?,?,?, 'SYNTHETIC') ON CONFLICT DO NOTHING",
            rows, 1000, (ps, r) -> { ps.setObject(1, r[0]); ps.setString(2, (String) r[1]); ps.setString(3, (String) r[2]); ps.setInt(4, (Integer) r[3]); });
    }

    /** Seasonality: Q4 and March (fiscal year end) are peak months. */
    private static double season(int month) {
        if (month >= 10) return 1.3;
        if (month == 3) return 1.25;
        return 1.0;
    }

    /**
     * Generates one forecast file and one inventory file and ingests both, like an SFTP drop.
     *
     * @param month cycle month {@code YYYY-MM}; null means next month
     * @param seed  random seed (deterministic output)
     * @param user  recorded as the actor
     * @return results of the forecast and inventory ingestion, in that order
     */
    public List<IngestResult> generate(String month, long seed, CurrentUser user) {
        requireEnabled();
        seedMaster(seed);
        String m = month != null ? month : YearMonth.now().plusMonths(1).toString();
        byte[][] pair = buildPair(m, seed);
        IngestResult f = ingestion.ingest(new IngestRequest(pair[0], "FORECAST_" + m + "_synthetic.xlsx", DatasetType.FORECAST_UPLOAD, "SYNTHETIC", user.username(), user.username()));
        IngestResult i = ingestion.ingest(new IngestRequest(pair[1], "INVENTORY_" + LocalDate.now().minusDays(1) + "_synthetic.xlsx", DatasetType.INVENTORY_UPLOAD, "SYNTHETIC", user.username(), user.username()));
        return List.of(f, i);
    }

    /**
     * Builds the forecast and inventory workbook bytes (no ingestion).
     * About 40% of models get stock below demand (constrained); product index 3 has zero stock (edge
     * case) and product index 7 is left out of the forecast (stock with no forecast / PRODUCT_NOT_IN_FILE).
     *
     * @param month cycle month
     * @param seed  random seed
     * @return {@code [forecastBytes, inventoryBytes]}
     */
    public byte[][] buildPair(String month, long seed) {
        List<String> regions = master.activeRegionCodes();
        Random rnd = new Random(seed);
        int monthNo = Integer.parseInt(month.substring(5));
        List<ForecastRow> forecast = new ArrayList<>();
        List<InventoryRow> inventory = new ArrayList<>();
        for (int p = 0; p < PRODUCTS.size(); p++) {
            Random pr = new Random(seed * 1000 + p);
            double base = 30 + pr.nextInt(370);
            Map<String, Integer> qty = new LinkedHashMap<>();
            int total = 0;
            for (int r = 0; r < regions.size(); r++) {
                int q = (int) Math.round(base * MIX[r % MIX.length] * season(monthNo) * (0.95 + 0.15 * rnd.nextDouble()));
                qty.put(regions.get(r), q);
                total += q;
            }
            if (p != 7) forecast.add(new ForecastRow(PRODUCTS.get(p), qty, null));
            int stock = p == 3 ? 0 : (rnd.nextDouble() < 0.4 ? (int) (total * (0.5 + 0.4 * rnd.nextDouble())) : (int) (total * (1.1 + 0.7 * rnd.nextDouble())));
            inventory.add(new InventoryRow(PRODUCTS.get(p), stock));
        }
        return new byte[][]{
            ExcelFiles.forecast(regions, forecast, month, null),
            ExcelFiles.inventory(inventory, LocalDate.now().minusDays(1), month, null)};
    }

    /**
     * Builds the negative test pack: one file per rejection code of PRD 8.4 that a file can trigger.
     * The {@code duplicate_file} item only works after the valid forecast was ingested, and it must be
     * the <em>exact bytes</em> that were ingested (Excel writers embed timestamps, so regenerating the
     * file would give a different hash).
     *
     * @param month                cycle month used inside the files
     * @param ingestedForecastBytes the exact bytes of an already-ingested forecast file, resent as a duplicate
     * @return the bad files with their expected code
     */
    public List<NegativeFile> negativePack(String month, byte[] ingestedForecastBytes) {
        List<String> regions = master.activeRegionCodes();
        String p0 = PRODUCTS.get(0), p1 = PRODUCTS.get(1);
        List<NegativeFile> pack = new ArrayList<>();
        // Header: Product_name, Forecast_Month, North, East, West, South
        pack.add(neg("unknown_product", "PRODUCT_UNKNOWN", fc(month, new Object[]{"Totally Unknown 9000", month, 1, 1, 1, 1})));
        pack.add(neg("duplicate_product", "PRODUCT_DUPLICATE", fc(month, new Object[]{p0, month, 1, 1, 1, 1}, new Object[]{p0, month, 2, 2, 2, 2})));
        pack.add(neg("negative_quantity", "QTY_INVALID", fc(month, new Object[]{p0, month, -5, 1, 1, 1})));
        pack.add(neg("text_quantity", "QTY_INVALID", fc(month, new Object[]{p0, month, "lots", 1, 1, 1})));
        pack.add(neg("blank_product", "PRODUCT_BLANK", fc(month, new Object[]{"", month, 1, 1, 1, 1})));
        pack.add(neg("wrong_month_between_rows", "MONTH_MISMATCH", fc(month, new Object[]{p0, month, 1, 1, 1, 1}, new Object[]{p1, YearMonth.parse(month).plusMonths(1).toString(), 1, 1, 1, 1})));
        pack.add(neg("missing_column", "HEADER_MISSING", custom("Forecast", new String[]{"Product_name", "Forecast_Month", "North", "East", "West"}, new Object[]{p0, month, 1, 1, 1})));
        pack.add(neg("sheet_missing", "SHEET_MISSING", custom("Sheet1", new String[]{"Product_name"}, new Object[]{p0})));
        pack.add(neg("formula_without_value", "QTY_INVALID", formulaFile(month, p0)));
        pack.add(neg("future_date_inventory", "DATE_INVALID", ExcelFiles.inventory(List.of(new InventoryRow(p0, 10)), LocalDate.now().plusDays(10), month, null)));
        pack.add(new NegativeFile("not_an_xlsx", "this is plain text, not a workbook".getBytes(StandardCharsets.UTF_8), "FILE_TYPE", false));
        pack.add(new NegativeFile("macro_workbook", macroZip(), "FILE_TYPE", false));
        pack.add(new NegativeFile("duplicate_file", ingestedForecastBytes, "DUPLICATE_FILE", true));
        return pack;
    }

    /** Pairs a pack file name with the dataset type it targets (inventory file is the future-date one). */
    public static DatasetType typeOf(NegativeFile f) {
        return f.name().equals("future_date_inventory") ? DatasetType.INVENTORY_UPLOAD : DatasetType.FORECAST_UPLOAD;
    }

    /**
     * Runs the whole negative pack through the real pipeline.
     *
     * @param month cycle month
     * @param seed  random seed
     * @param user  recorded as the actor
     * @return map of pack file name to its ingestion result (all expected REJECTED)
     */
    public Map<String, IngestResult> runNegativePack(String month, long seed, CurrentUser user) {
        requireEnabled();
        seedMaster(seed);
        // A valid pair first, so the cycle exists and the duplicate file has something to duplicate.
        byte[][] pair = buildPair(month, seed);
        ingestion.ingest(new IngestRequest(pair[0], "FORECAST_" + month + "_synthetic.xlsx", DatasetType.FORECAST_UPLOAD, "SYNTHETIC", user.username(), user.username()));
        ingestion.ingest(new IngestRequest(pair[1], "INVENTORY_" + LocalDate.now().minusDays(1) + "_synthetic.xlsx", DatasetType.INVENTORY_UPLOAD, "SYNTHETIC", user.username(), user.username()));
        Map<String, IngestResult> out = new LinkedHashMap<>();
        for (NegativeFile f : negativePack(month, pair[0])) {
            out.put(f.name(), ingestion.ingest(new IngestRequest(f.bytes(), f.name() + ".xlsx", typeOf(f), "SYNTHETIC", user.username(), user.username())));
        }
        return out;
    }

    /**
     * Removes every synthetic version, job, sales row, product and empty cycle plus the stored files
     * (PRD FR-AD-10: purge all synthetic data before go-live). Audit entries are kept.
     *
     * @param user the Admin performing the purge
     * @return number of dataset versions removed
     */
    @Transactional
    public int purgeAll(CurrentUser user) {
        requireEnabled();
        List<String> keys = jdbc.sql("SELECT object_key FROM dataset_version WHERE source = 'SYNTHETIC' AND object_key IS NOT NULL").query(String.class).list();
        jdbc.sql("DELETE FROM ingestion_job WHERE channel = 'SYNTHETIC'").update();
        jdbc.sql("DELETE FROM forecast_line WHERE version_id IN (SELECT id FROM dataset_version WHERE source = 'SYNTHETIC')").update();
        jdbc.sql("DELETE FROM inventory_line WHERE version_id IN (SELECT id FROM dataset_version WHERE source = 'SYNTHETIC')").update();
        int versions = jdbc.sql("DELETE FROM dataset_version WHERE source = 'SYNTHETIC'").update();
        jdbc.sql("DELETE FROM sales_history WHERE source = 'SYNTHETIC'").update();
        jdbc.sql("DELETE FROM product_alias WHERE product_id IN (SELECT id FROM product WHERE source = 'SYNTHETIC')").update();
        jdbc.sql("""
                DELETE FROM product WHERE source = 'SYNTHETIC'
                AND NOT EXISTS (SELECT 1 FROM forecast_line f WHERE f.product_id = product.id)
                AND NOT EXISTS (SELECT 1 FROM inventory_line i WHERE i.product_id = product.id)
                AND NOT EXISTS (SELECT 1 FROM sales_history s WHERE s.product_id = product.id)
                """).update();
        jdbc.sql("DELETE FROM planning_cycle c WHERE NOT EXISTS (SELECT 1 FROM dataset_version v WHERE v.cycle_id = c.id)").update();
        keys.forEach(files::delete);
        audit.record(user.username(), "SYNTHETIC_PURGE", "system", null, Map.of("versions", versions));
        return versions;
    }

    // ---------------------------------------------------------------- file builders for the negative pack

    private NegativeFile neg(String name, String code, byte[] bytes) {
        return new NegativeFile(name, bytes, code, false);
    }

    /** A forecast workbook with the standard header and the given raw rows. */
    private byte[] fc(String month, Object[]... rows) {
        return custom("Forecast", new String[]{"Product_name", "Forecast_Month", "North", "East", "West", "South"}, rows);
    }

    /** Builds a workbook with one sheet, a header row and arbitrary cell values (strings or numbers). */
    private static byte[] custom(String sheetName, String[] headers, Object[]... rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet(sheetName);
            Row h = s.createRow(0);
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else row.createCell(c).setCellValue(String.valueOf(v));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A forecast workbook whose North cell is a formula that has no stored result. */
    private byte[] formulaFile(String month, String product) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Forecast");
            String[] headers = {"Product_name", "Forecast_Month", "North", "East", "West", "South"};
            Row h = s.createRow(0);
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
            Row r = s.createRow(1);
            r.createCell(0).setCellValue(product);
            r.createCell(1).setCellValue(month);
            r.createCell(2).setCellFormula("1+1"); // never evaluated -> no cached value
            for (int c = 3; c < 6; c++) r.createCell(c).setCellValue(1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A zip that contains a VBA macro project entry, which the reader must refuse. */
    private static byte[] macroZip() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("[Content_Types].xml"));
            z.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
            z.write(new byte[]{1, 2, 3});
            z.closeEntry();
            z.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
