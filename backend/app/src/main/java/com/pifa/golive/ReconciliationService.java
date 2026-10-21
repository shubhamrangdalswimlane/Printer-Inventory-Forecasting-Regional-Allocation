package com.pifa.golive;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.ingestion.ExcelFiles;
import com.pifa.ingestion.ExcelReader;
import com.pifa.master.MasterDataService;
import com.pifa.master.ProductNames;
import com.pifa.security.CurrentUser;
import com.pifa.storage.VirusScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Parallel-run reconciliation (Phase 6b; go-live checklist item 11).
 *
 * <p>During the parallel-run month the business runs its old spreadsheet process next to PIFA. At the end, an Admin
 * uploads the spreadsheet's final allocation and this service compares it, product by product and region by region,
 * with PIFA's allocation for the same cycle. Every difference must then be explained (a policy difference, a manual
 * override in the old process, a data error) or fixed before go-live.
 *
 * <p>File layout - sheet {@code Allocation}: {@code Product_name} plus one column per active region (same headers
 * as the forecast template). PIFA's side is the FINAL allocation when the cycle is FINAL, otherwise the Planner's
 * approved allocation (or the proposal, where nothing was approved yet), and the result says which was used.
 *
 * <p>The summary is written to {@code system_status} (key {@code golive.reconciliation}) for the readiness check.
 */
@Service
public class ReconciliationService {

    /**
     * One product-region cell where the two sides differ.
     *
     * @param product product name
     * @param region  region code
     * @param pifa    PIFA quantity (0 when PIFA has no line)
     * @param legacy  quantity in the uploaded file (0 when the file has no row)
     * @param diff    pifa - legacy
     */
    public record Difference(String product, String region, int pifa, int legacy, int diff) {}

    /**
     * The reconciliation.
     *
     * @param cycleMonth        the cycle compared
     * @param pifaStage         FINAL, APPROVED or PROPOSED: which PIFA numbers were used
     * @param cellsCompared     product-region cells compared
     * @param matching          cells with equal quantities
     * @param pifaTotal         total units allocated by PIFA
     * @param legacyTotal       total units in the file
     * @param differences       the differing cells, largest absolute difference first (at most 5,000 listed)
     * @param differenceCount   number of differing cells (may exceed the list)
     * @param onlyInFile        product names in the file that are not in the cycle (unknown or not planned)
     * @param onlyInPifa        products PIFA allocated to that the file does not mention
     * @param ok                true when nothing differs
     */
    public record Result(String cycleMonth, String pifaStage, int cellsCompared, int matching, long pifaTotal, long legacyTotal,
                         List<Difference> differences, int differenceCount, List<String> onlyInFile, List<String> onlyInPifa, boolean ok) {}

    private final JdbcClient jdbc;
    private final MasterDataService master;
    private final VirusScanner scanner;
    private final AuditService audit;

    public ReconciliationService(JdbcClient jdbc, MasterDataService master, VirusScanner scanner, AuditService audit) {
        this.jdbc = jdbc;
        this.master = master;
        this.scanner = scanner;
        this.audit = audit;
    }

    /**
     * Compares an uploaded allocation with PIFA's allocation of a cycle.
     *
     * @param cycleId  the parallel-run cycle
     * @param bytes    the legacy allocation workbook
     * @param fileName its name (recorded)
     * @param user     the caller; must be Admin
     * @return the reconciliation
     * @throws ApiException 403 for non-Admins, 404 unknown cycle, 422 unreadable/infected file or bad quantities
     */
    public Result reconcile(UUID cycleId, byte[] bytes, String fileName, CurrentUser user) {
        if (!user.has("admin")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only an Admin can run a reconciliation");
        record Cycle(String month, String state) {}
        Cycle cycle = jdbc.sql("SELECT month, state FROM planning_cycle WHERE id = :id").param("id", cycleId)
            .query((rs, n) -> new Cycle(rs.getString(1), rs.getString(2))).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Cycle not found"));
        GoLiveFiles.scan(scanner, bytes);
        List<String> regions = master.activeRegionCodes();
        List<String> headers = regions.stream().map(ExcelFiles::capitalise).toList();
        List<String> required = new ArrayList<>(List.of("Product_name"));
        required.addAll(headers);
        ExcelReader.SheetData sheet = GoLiveFiles.read(bytes, List.of("Allocation"), required, new HashSet<>(required), 200_000);

        // PIFA side: product -> region -> qty, using the most settled numbers the cycle has.
        boolean fin = "FINAL".equals(cycle.state());
        String column = fin ? "final_alloc_qty" : "COALESCE(approved_alloc_qty, proposed_alloc_qty)";
        Map<UUID, Map<String, Integer>> pifa = new HashMap<>();
        boolean[] anyApproved = {false};
        jdbc.sql("SELECT product_id, region_code, " + column + ", approved_alloc_qty IS NOT NULL FROM plan_line WHERE cycle_id = :c")
            .param("c", cycleId).query((rs, n) -> {
                if (rs.getBoolean(4)) anyApproved[0] = true;
                pifa.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>()).put(rs.getString(2), rs.getInt(3));
                return null;
            }).list();
        String stage = fin ? "FINAL" : anyApproved[0] ? "APPROVED" : "PROPOSED";

        // File side, keyed the same way. Problems with quantities are fatal: a reconciliation on bad input is useless.
        Map<String, UUID> lookup = master.productLookup();
        Map<UUID, String> names = master.productNames();
        Map<UUID, Map<String, Integer>> legacy = new LinkedHashMap<>();
        Set<String> onlyInFile = new java.util.TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (ExcelReader.RowData r : sheet.rows()) {
            String name = GoLiveFiles.text(r.get("Product_name"));
            if (name == null) continue;
            UUID pid = lookup.get(ProductNames.normalize(name));
            if (pid == null || !pifa.containsKey(pid)) { onlyInFile.add(name); continue; }
            Map<String, Integer> row = legacy.computeIfAbsent(pid, k -> new HashMap<>());
            for (int i = 0; i < regions.size(); i++) {
                ExcelReader.RawCell cell = r.get(headers.get(i));
                Integer q = cell == null || cell.value() == null ? Integer.valueOf(0) : GoLiveFiles.qty(cell);
                if (q == null) { problems.add("Row " + r.rowNo() + ": " + headers.get(i) + " must be a whole number >= 0"); continue; }
                row.merge(regions.get(i), q, Integer::sum); // a product listed twice is summed, as the old sheet would
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_INVALID", problems.size() + " invalid quantities",
                Map.of("problems", problems.subList(0, Math.min(200, problems.size()))));
        }

        // Compare every cell present on either side.
        List<Difference> diffs = new ArrayList<>();
        int compared = 0, matching = 0;
        long pifaTotal = 0, legacyTotal = 0;
        List<String> onlyInPifa = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Integer>> e : pifa.entrySet()) {
            Map<String, Integer> file = legacy.get(e.getKey());
            int pifaSum = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            if (file == null && pifaSum > 0) onlyInPifa.add(names.get(e.getKey()));
            for (String reg : regions) {
                int p = e.getValue().getOrDefault(reg, 0);
                int l = file == null ? 0 : file.getOrDefault(reg, 0);
                compared++;
                pifaTotal += p;
                legacyTotal += l;
                if (p == l) matching++;
                else diffs.add(new Difference(names.get(e.getKey()), reg, p, l, p - l));
            }
        }
        diffs.sort((a, b) -> Integer.compare(Math.abs(b.diff()), Math.abs(a.diff())));
        java.util.Collections.sort(onlyInPifa);
        int diffCount = diffs.size();
        boolean ok = diffCount == 0 && onlyInFile.isEmpty();
        Result result = new Result(cycle.month(), stage, compared, matching, pifaTotal, legacyTotal,
            List.copyOf(diffs.subList(0, Math.min(5_000, diffs.size()))), diffCount, List.copyOf(onlyInFile), onlyInPifa, ok);

        String summary = cycle.month() + " (" + stage + "): " + diffCount + " differing cells of " + compared
            + ", " + onlyInFile.size() + " file products not in the cycle";
        jdbc.sql("INSERT INTO system_status (key, value, ok, updated_at) VALUES ('golive.reconciliation', :v, :ok, now()) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, ok = EXCLUDED.ok, updated_at = now()")
            .param("v", summary).param("ok", ok).update();
        audit.record(user.username(), "RECONCILIATION_RUN", "planning_cycle", cycleId.toString(),
            Map.of("file", fileName, "stage", stage, "differences", diffCount, "onlyInFile", onlyInFile.size()));
        return result;
    }
}
