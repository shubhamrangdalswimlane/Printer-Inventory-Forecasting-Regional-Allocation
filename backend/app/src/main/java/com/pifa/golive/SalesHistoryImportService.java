package com.pifa.golive;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.ingestion.ExcelFiles;
import com.pifa.ingestion.ExcelReader;
import com.pifa.master.MasterDataService;
import com.pifa.master.ProductNames;
import com.pifa.storage.VirusScanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports real monthly sales into {@code sales_history} (Phase 6a "one-off import of real sales history (24 months)";
 * Phase 7 reuses it for the SFTP sales/actuals feed).
 *
 * <p>File layout - sheet {@code Sales}, one row per product and month, one column per active region (the same
 * region headers as the forecast template):
 * <pre>
 *   Product_name | Month   | North | East | West | South
 *   Mono Laser M100 | 2025-11 | 120 | 80 | 95 | 60
 * </pre>
 * A blank region cell means "no data" and leaves any existing value alone; 0 means zero sales.
 *
 * <p><b>All-or-nothing:</b> if any row has a problem (unknown product, bad month, bad quantity, duplicate
 * product+month, future month) nothing is written and every problem is returned. Otherwise existing rows for the same
 * product, region and month are overwritten (re-importing a corrected file is safe) and are marked with the
 * given source, so they are never removed by the synthetic-data purge.
 */
@Service
public class SalesHistoryImportService {

    /**
     * Result of an import.
     *
     * @param imported  product-region-month values written (0 when problems were found)
     * @param products  distinct products in the file
     * @param firstMonth earliest month in the file, or null
     * @param lastMonth  latest month in the file, or null
     * @param problems  row-level problems; when not empty nothing was written
     */
    public record Result(int imported, int products, String firstMonth, String lastMonth, List<String> problems) {
        /** @return true when the file was imported */
        public boolean ok() { return problems.isEmpty(); }
    }

    /** At most this many problems are listed; the rest are counted. Keeps a badly wrong file's response small. */
    private static final int MAX_PROBLEMS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final MasterDataService master;
    private final VirusScanner scanner;
    private final AuditService audit;

    public SalesHistoryImportService(JdbcTemplate jdbcTemplate, MasterDataService master, VirusScanner scanner, AuditService audit) {
        this.jdbcTemplate = jdbcTemplate;
        this.master = master;
        this.scanner = scanner;
        this.audit = audit;
    }

    /**
     * Validates and imports a sales workbook.
     *
     * @param bytes    the .xlsx file
     * @param fileName its name (recorded in the audit row)
     * @param source   {@code UPLOAD} (Admin UI) or {@code SFTP} (sales feed); stored on each row
     * @param actor    who imports: the Admin's username, or the SFTP account
     * @return what was imported, or the problems (then nothing was written)
     * @throws ApiException 422 if the file is not a readable workbook with the required columns or is infected;
     *                      503 if the virus scanner is unavailable
     */
    @Transactional
    public Result importSales(byte[] bytes, String fileName, String source, String actor) {
        GoLiveFiles.scan(scanner, bytes);
        List<String> regions = master.activeRegionCodes();
        List<String> regionHeaders = regions.stream().map(ExcelFiles::capitalise).toList();
        List<String> required = new ArrayList<>(List.of("Product_name", "Month"));
        required.addAll(regionHeaders);
        ExcelReader.SheetData sheet = GoLiveFiles.read(bytes, List.of("Sales"), required, new HashSet<>(required), 2_000_000);

        Map<String, UUID> lookup = master.productLookup();
        String currentMonth = java.time.YearMonth.now().toString();
        List<String> problems = new ArrayList<>();
        int[] extra = {0};
        java.util.function.Consumer<String> problem = p -> { if (problems.size() < MAX_PROBLEMS) problems.add(p); else extra[0]++; };

        List<UUID> pids = new ArrayList<>();
        List<String> regs = new ArrayList<>();
        List<String> months = new ArrayList<>();
        List<Integer> qtys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<UUID> products = new LinkedHashSet<>();
        TreeSet<String> monthSet = new TreeSet<>();

        for (ExcelReader.RowData r : sheet.rows()) {
            String name = GoLiveFiles.text(r.get("Product_name"));
            String month = GoLiveFiles.month(r.get("Month"));
            UUID pid = name == null ? null : lookup.get(ProductNames.normalize(name));
            if (name == null) { problem.accept("Row " + r.rowNo() + ": Product_name is empty"); continue; }
            if (pid == null) { problem.accept("Row " + r.rowNo() + ": unknown product '" + name + "' (add it or an alias in Master data)"); continue; }
            if (month == null) { problem.accept("Row " + r.rowNo() + ": Month must be YYYY-MM"); continue; }
            if (month.compareTo(currentMonth) > 0) { problem.accept("Row " + r.rowNo() + ": month " + month + " is in the future"); continue; }
            if (!seen.add(pid + "|" + month)) { problem.accept("Row " + r.rowNo() + ": '" + name + "' " + month + " appears twice"); continue; }
            for (int i = 0; i < regions.size(); i++) {
                ExcelReader.RawCell cell = r.get(regionHeaders.get(i));
                if (cell == null || cell.value() == null) continue; // blank = no data for this region
                Integer q = GoLiveFiles.qty(cell);
                if (q == null) { problem.accept("Row " + r.rowNo() + ": " + regionHeaders.get(i) + " must be a whole number >= 0"); continue; }
                pids.add(pid);
                regs.add(regions.get(i));
                months.add(month);
                qtys.add(q);
            }
            products.add(pid);
            monthSet.add(month);
        }
        if (extra[0] > 0) problems.add("... and " + extra[0] + " more problems");
        if (!problems.isEmpty()) {
            audit.record(actor, "SALES_IMPORT_REJECTED", "file", fileName, Map.of("source", source, "problems", problems.size()));
            return new Result(0, 0, null, null, problems);
        }

        // One set-based upsert per chunk (array parameters), so 1M values take seconds rather than minutes.
        final int chunk = 50_000;
        for (int from = 0; from < pids.size(); from += chunk) {
            int to = Math.min(from + chunk, pids.size());
            List<UUID> p = pids.subList(from, to);
            List<String> rg = regs.subList(from, to);
            List<String> m = months.subList(from, to);
            List<Integer> q = qtys.subList(from, to);
            jdbcTemplate.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO sales_history (product_id, region_code, month, qty, source)
                        SELECT u.pid, u.reg, u.mon, u.q, ? FROM unnest(?::uuid[], ?::text[], ?::text[], ?::int[]) AS u(pid, reg, mon, q)
                        ON CONFLICT (product_id, region_code, month) DO UPDATE SET qty = EXCLUDED.qty, source = EXCLUDED.source
                        """);
                ps.setString(1, source);
                ps.setArray(2, con.createArrayOf("uuid", p.toArray()));
                ps.setArray(3, con.createArrayOf("text", rg.toArray()));
                ps.setArray(4, con.createArrayOf("text", m.toArray()));
                ps.setArray(5, con.createArrayOf("int4", q.toArray()));
                return ps;
            });
        }
        Result result = new Result(pids.size(), products.size(), monthSet.isEmpty() ? null : monthSet.first(),
            monthSet.isEmpty() ? null : monthSet.last(), List.of());
        audit.record(actor, "SALES_IMPORTED", "file", fileName, Map.of("source", source, "values", result.imported(),
            "products", result.products(), "from", String.valueOf(result.firstMonth()), "to", String.valueOf(result.lastMonth())));
        return result;
    }
}
