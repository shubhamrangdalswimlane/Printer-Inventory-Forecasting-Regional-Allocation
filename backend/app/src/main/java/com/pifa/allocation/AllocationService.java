package com.pifa.allocation;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.ingestion.ExcelFiles;
import com.pifa.master.MasterDataService;
import com.pifa.parameters.RuleParameterService;
import com.pifa.rules.RulesFacade;
import com.pifa.rules.model.AllocationConfig;
import com.pifa.rules.model.AllocationDirective;
import com.pifa.rules.model.AllocationLine;
import com.pifa.rules.model.Classification;
import com.pifa.rules.model.ProductStock;
import com.pifa.rules.model.Strategy;
import com.pifa.rules.model.Violation;
import com.pifa.security.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Planner's allocation (PRD FR-PL-02..05, FR-PL-09, FR-PL-10; BR-AL-01..07).
 *
 * <p>Flow: Drools classifies each product (constrained / sufficient) and picks a strategy;
 * {@link AllocationCalculator} computes the region quantities; the result is written as the
 * <em>proposed</em> allocation and copied into the <em>approved</em> column, which the Planner may then
 * edit (with a reason). The Drools validation rules check the numbers at any time; ERRORs block approval.
 * Every run records the ruleset version and the rule-parameter version it used.
 */
@Service
public class AllocationService {

    /** One cell whose number would change (preview) or did change (run). */
    public record Change(UUID productId, String product, String region, Integer before, int after) {}

    /**
     * Outcome of a proposal run or preview.
     *
     * @param saved            true for a run, false for a preview
     * @param products         products processed
     * @param constrained      how many were constrained
     * @param sufficient       how many were sufficient
     * @param changedCells     cells whose approved value changes
     * @param changes          the first 500 changes, for the diff view
     * @param rulesetVersion   Drools ruleset version used
     * @param parameterVersion rule-parameter version used
     */
    public record ProposalResult(boolean saved, int products, int constrained, int sufficient, int changedCells, List<Change> changes,
                                 String rulesetVersion, int parameterVersion) {}

    /** One region cell of the allocation grid. */
    public record Cell(UUID lineId, int demand, Integer proposed, Integer approved, Integer finalQty, long rowVersion) {}

    /** Stock of one product in the cycle. */
    public record Stock(int available, int reserve, String reserveReason, int allocatable, String classification,
                        String explanation, Integer unallocated, long rowVersion) {}

    /** One product row of the allocation grid. */
    public record GridRow(UUID productId, String product, Stock stock, Map<String, Cell> regions) {}

    /** A page of the grid plus a summary of the whole cycle. */
    public record GridPage(int total, List<GridRow> rows, Map<String, Object> summary) {}

    /** All plan lines of one product, as loaded for a run or a validation. */
    private static final class Product {
        UUID id;
        String name;
        int allocatable;
        String classification;
        final Map<String, Integer> demand = new LinkedHashMap<>();
        final Map<String, Integer> approved = new HashMap<>();
        final Map<String, Integer> finalQty = new HashMap<>();
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AllocationService.class);

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final RulesFacade rules;
    private final RuleParameterService params;
    private final MasterDataService master;
    private final AuditService audit;
    private final com.pifa.events.LiveEvents events;
    private final com.pifa.ops.PifaMetrics metrics;

    public AllocationService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, RulesFacade rules, RuleParameterService params,
                             MasterDataService master, AuditService audit, com.pifa.events.LiveEvents events,
                             com.pifa.ops.PifaMetrics metrics) {
        this.events = events;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.rules = rules;
        this.params = params;
        this.master = master;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- proposal

    /**
     * Computes the rule-based allocation for every product of the cycle.
     *
     * <p>With {@code save=false} nothing is written and the result lists what <em>would</em> change
     * (the "re-run with a diff" of PRD FR-PL-09). With {@code save=true} the proposed and approved
     * columns are overwritten (Planner edits are replaced), the stock rows get their classification and
     * explanation, and the cycle records the ruleset and parameter version used.
     *
     * @param cycleId the cycle (must be in PLANNING)
     * @param save    false = preview, true = run and store
     * @param user    the caller (must be Planner)
     * @return summary and changes
     * @throws ApiException 403 not Planner, 409 cycle not in PLANNING
     */
    @Transactional
    public ProposalResult propose(UUID cycleId, boolean save, CurrentUser user) {
        return propose(cycleId, save, user, null);
    }

    /**
     * Same as {@link #propose(UUID, boolean, CurrentUser)} but the Planner chooses how constrained stock is split for
     * this run. Products with enough stock always get their full demand ("as per forecast"); the basis only matters
     * when stock is short.
     *
     * @param basis {@code FIXED} (the region weights, 25% each by default = "split the stock equally"),
     *              {@code FORECAST_SHARE} (in proportion to each region's forecast), {@code SALES_SHARE} (by sales history),
     *              or null / blank to use the rule parameters' current mode
     * @throws ApiException 400 for an unknown basis, 403 not Planner, 409 cycle not in PLANNING
     */
    @Transactional
    public ProposalResult propose(UUID cycleId, boolean save, CurrentUser user, String basis) {
        if (!user.has("planner")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the Planner may run the allocation");
        requireState(cycleId, "PLANNING");
        if (basis != null && !basis.isBlank() && !List.of("FIXED", "FORECAST_SHARE", "SALES_SHARE").contains(basis)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BASIS", "Basis must be FIXED, FORECAST_SHARE or SALES_SHARE");
        }
        long t0 = System.nanoTime();
        var version = params.current();
        String mode = basis == null || basis.isBlank() ? version.values().weightMode() : basis;
        boolean useSales = "SALES_SHARE".equals(mode);
        boolean useForecast = "FORECAST_SHARE".equals(mode);
        List<String> regions = master.activeRegionCodes();
        String[] regionArr = regions.toArray(String[]::new);
        int[] weights = regions.stream().mapToInt(r -> version.values().regionWeights().getOrDefault(r, 0)).toArray();
        Map<UUID, Product> products = load(cycleId);
        // Phase 7 weight mode: each product's own sales share per region (null map entry = no sales, use fixed weights).
        Map<UUID, int[]> shareWeights = useSales ? salesShareWeights(regions, version.values().shareMonths()) : Map.of();
        long tLoaded = System.nanoTime();

        List<ProductStock> facts = new ArrayList<>();
        for (Product p : products.values()) {
            facts.add(new ProductStock(p.id.toString(), p.name, p.allocatable, p.demand.values().stream().mapToInt(Integer::intValue).sum()));
        }
        Map<String, Strategy> strategy = new HashMap<>();
        for (AllocationDirective d : rules.decideStrategies(facts, new AllocationConfig(version.values().capAtDemand()))) {
            strategy.put(d.getProductId(), d.getStrategy());
        }
        Map<String, Classification> classes = new HashMap<>();
        facts.forEach(f -> classes.put(f.getProductId(), f.getClassification()));
        long tRules = System.nanoTime();

        List<Change> changes = new ArrayList<>();
        int changed = 0, constrained = 0;
        List<Object[]> lineUpdates = new ArrayList<>();
        List<Object[]> stockUpdates = new ArrayList<>();
        for (Product p : products.values()) {
            int[] demand = regions.stream().mapToInt(r -> p.demand.getOrDefault(r, 0)).toArray();
            Strategy s = strategy.get(p.id.toString());
            int[] w = shareWeights.getOrDefault(p.id, weights);
            if (useForecast) {
                int[] byDemand = AllocationCalculator.sharePercent(java.util.Arrays.stream(demand).asLongStream().toArray());
                if (byDemand != null) w = byDemand;
            }
            AllocationCalculator.Result r = switch (s) {
                case FULL_DEMAND -> AllocationCalculator.fullDemand(p.allocatable, demand);
                case WEIGHTED_BASELINE -> AllocationCalculator.weightedBaseline(p.allocatable, demand, w, regionArr);
                case WEIGHTED_BASELINE_CAPPED -> AllocationCalculator.weightedBaselineCapped(p.allocatable, demand, w, regionArr);
            };
            if (s != Strategy.FULL_DEMAND && (useSales || useForecast)) {
                String why = w == weights ? " (no " + (useSales ? "sales history" : "forecast") + ": fixed weights)"
                    : useSales ? " (sales share, last " + version.values().shareMonths() + " months)" : " (in proportion to forecast)";
                r = new AllocationCalculator.Result(r.alloc(), r.unallocated(), r.explanation() + why);
            }
            if (classes.get(p.id.toString()) == Classification.CONSTRAINED) constrained++;
            for (int i = 0; i < regionArr.length; i++) {
                Integer before = p.approved.get(regionArr[i]);
                if (before == null || before != r.alloc()[i]) {
                    changed++;
                    if (changes.size() < 500) changes.add(new Change(p.id, p.name, regionArr[i], before, r.alloc()[i]));
                }
                lineUpdates.add(new Object[]{r.alloc()[i], cycleId, p.id, regionArr[i]});
            }
            stockUpdates.add(new Object[]{cycleId, p.id, classes.get(p.id.toString()).name(), r.explanation(), r.unallocated()});
        }

        long tCalc = System.nanoTime();
        if (save) {
            // The new proposal also becomes the Planner's starting point: approved := proposed (earlier edits are replaced).
            // One set-based statement with array parameters instead of 40,000 single-row updates: its speed does
            // not depend on table statistics (single-row batches took 1 s or 17 s depending on table history).
            jdbcTemplate.update(con -> {
                var ps = con.prepareStatement("""
                        UPDATE plan_line l SET proposed_alloc_qty = u.q, approved_alloc_qty = u.q, row_version = l.row_version + 1
                        FROM unnest(?::uuid[], ?::text[], ?::int[]) AS u(pid, reg, q)
                        WHERE l.cycle_id = ? AND l.product_id = u.pid AND l.region_code = u.reg
                        """);
                ps.setArray(1, con.createArrayOf("uuid", lineUpdates.stream().map(u -> u[2]).toArray()));
                ps.setArray(2, con.createArrayOf("text", lineUpdates.stream().map(u -> u[3]).toArray()));
                ps.setArray(3, con.createArrayOf("int4", lineUpdates.stream().map(u -> u[0]).toArray()));
                ps.setObject(4, cycleId);
                return ps;
            });
            jdbcTemplate.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO plan_product_stock (cycle_id, product_id, available_qty, classification, allocation_explanation, unallocated_qty)
                        SELECT ?, u.pid, 0, u.cls, u.expl, u.una FROM unnest(?::uuid[], ?::text[], ?::text[], ?::int[]) AS u(pid, cls, expl, una)
                        ON CONFLICT (cycle_id, product_id) DO UPDATE SET classification = EXCLUDED.classification,
                            allocation_explanation = EXCLUDED.allocation_explanation, unallocated_qty = EXCLUDED.unallocated_qty
                        """);
                ps.setObject(1, cycleId);
                ps.setArray(2, con.createArrayOf("uuid", stockUpdates.stream().map(u -> u[1]).toArray()));
                ps.setArray(3, con.createArrayOf("text", stockUpdates.stream().map(u -> u[2]).toArray()));
                ps.setArray(4, con.createArrayOf("text", stockUpdates.stream().map(u -> u[3]).toArray()));
                ps.setArray(5, con.createArrayOf("int4", stockUpdates.stream().map(u -> u[4]).toArray()));
                return ps;
            });
            jdbc.sql("UPDATE planning_cycle SET proposal_at = now(), proposal_ruleset_version = :rv, proposal_parameter_version = :pv, allocation_stale = FALSE WHERE id = :c")
                .param("rv", RulesFacade.RULESET_VERSION).param("pv", version.version()).param("c", cycleId).update();
            jdbc.sql("INSERT INTO workflow_event (cycle_id, action, from_state, to_state, actor, comment) VALUES (:c, 'ALLOCATION_PROPOSED', 'PLANNING', 'PLANNING', :u, :m)")
                .param("c", cycleId).param("u", user.username()).param("m", "ruleset " + RulesFacade.RULESET_VERSION + ", parameters v" + version.version()).update();
            events.publish(cycleId, "ALLOCATION");
            audit.record(user.username(), "ALLOCATION_PROPOSED", "planning_cycle", cycleId.toString(),
                Map.of("products", products.size(), "constrained", constrained, "changedCells", changed,
                    "rulesetVersion", RulesFacade.RULESET_VERSION, "parameterVersion", version.version()));
        }
        if (save) metrics.allocation(System.nanoTime() - t0);
        log.info("Allocation {} for {} products: load {} ms, rules {} ms, calc {} ms, save {} ms", save ? "run" : "preview", products.size(),
            (tLoaded - t0) / 1_000_000, (tRules - tLoaded) / 1_000_000, (tCalc - tRules) / 1_000_000, (System.nanoTime() - tCalc) / 1_000_000);
        return new ProposalResult(save, products.size(), constrained, products.size() - constrained, changed, changes,
            RulesFacade.RULESET_VERSION, version.version());
    }

    // ---------------------------------------------------------------- validation

    /**
     * Runs the allocation validation rules (BR-VA-01..04) over one stage of the allocation.
     *
     * @param cycleId the cycle
     * @param stage   {@code APPROVED} (the Planner's numbers) or {@code FINAL} (after transfers)
     * @return findings, each with the product id as subject; empty if no allocation exists yet
     */
    public List<Violation> validate(UUID cycleId, String stage) {
        boolean fin = "FINAL".equalsIgnoreCase(stage);
        Map<UUID, Product> products = load(cycleId);
        List<ProductStock> stocks = new ArrayList<>();
        List<AllocationLine> lines = new ArrayList<>();
        for (Product p : products.values()) {
            Map<String, Integer> alloc = fin ? p.finalQty : p.approved;
            if (alloc.isEmpty()) continue;
            int totalDemand = p.demand.values().stream().mapToInt(Integer::intValue).sum();
            ProductStock s = new ProductStock(p.id.toString(), p.name, p.allocatable, totalDemand);
            s.setClassification(p.classification != null ? Classification.valueOf(p.classification)
                : totalDemand > p.allocatable ? Classification.CONSTRAINED : Classification.SUFFICIENT);
            stocks.add(s);
            alloc.forEach((region, qty) -> lines.add(new AllocationLine(p.id.toString(), p.name, region, qty, p.demand.getOrDefault(region, 0))));
        }
        return stocks.isEmpty() ? List.of() : rules.validateAllocation(stocks, lines);
    }

    /** @return number of ERROR findings of a stage (used by the workflow guards) */
    public int errorCount(UUID cycleId, String stage) {
        return (int) validate(cycleId, stage).stream().filter(v -> v.getSeverity() == Violation.Severity.ERROR).count();
    }

    // ---------------------------------------------------------------- grid

    /**
     * One page of the demand-versus-stock / allocation grid, ordered by product name, plus a summary.
     *
     * @param cycleId the cycle
     * @param search  part of the product name, or null
     * @param onlyConstrained true to list constrained products only
     * @param page    zero-based page
     * @param size    rows per page (1..500)
     * @return the page
     */
    public GridPage grid(UUID cycleId, String search, boolean onlyConstrained, int page, int size) {
        int s = Math.min(Math.max(size, 1), 500);
        String like = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        String filter = """
                FROM product p LEFT JOIN plan_product_stock st ON st.cycle_id = :c AND st.product_id = p.id
                WHERE EXISTS (SELECT 1 FROM plan_line l WHERE l.cycle_id = :c AND l.product_id = p.id)
                AND (CAST(:q AS TEXT) IS NULL OR lower(p.product_name) LIKE :q)
                AND (:oc = FALSE OR st.classification = 'CONSTRAINED')
                """;
        int total = jdbc.sql("SELECT COUNT(*) " + filter).param("c", cycleId).param("q", like).param("oc", onlyConstrained).query(Integer.class).single();
        Map<UUID, GridRow> rows = new LinkedHashMap<>();
        jdbc.sql("SELECT p.id, p.product_name, st.available_qty, st.reserve_qty, st.reserve_reason, st.allocatable_qty, st.classification, "
                + "st.allocation_explanation, st.unallocated_qty, st.row_version " + filter + " ORDER BY p.product_name LIMIT :s OFFSET :o")
            .param("c", cycleId).param("q", like).param("oc", onlyConstrained).param("s", s).param("o", Math.max(page, 0) * s)
            .query((rs, n) -> {
                UUID id = rs.getObject(1, UUID.class);
                Stock st = new Stock(rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                    (Integer) rs.getObject(9), rs.getLong(10));
                rows.put(id, new GridRow(id, rs.getString(2), st, new LinkedHashMap<>()));
                return null;
            }).list();
        if (!rows.isEmpty()) {
            jdbc.sql("""
                    SELECT l.product_id, l.region_code, l.id, COALESCE(l.revised_qty, l.published_qty), l.proposed_alloc_qty,
                           l.approved_alloc_qty, l.final_alloc_qty, l.row_version
                    FROM plan_line l JOIN region r ON r.code = l.region_code
                    WHERE l.cycle_id = :c AND l.product_id IN (:ids) ORDER BY r.display_order
                    """).param("c", cycleId).param("ids", new ArrayList<>(rows.keySet()))
                .query((rs, n) -> {
                    rows.get(rs.getObject(1, UUID.class)).regions().put(rs.getString(2), new Cell(rs.getObject(3, UUID.class), rs.getInt(4),
                        (Integer) rs.getObject(5), (Integer) rs.getObject(6), (Integer) rs.getObject(7), rs.getLong(8)));
                    return null;
                }).list();
        }
        return new GridPage(total, new ArrayList<>(rows.values()), summary(cycleId));
    }

    /** Cycle-level numbers for the Planner banner: products, constrained count, stale flag, proposal stamp. */
    public Map<String, Object> summary(UUID cycleId) {
        Map<String, Object> m = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT (SELECT COUNT(DISTINCT product_id) FROM plan_line WHERE cycle_id = :c),
                       (SELECT COUNT(*) FROM plan_product_stock WHERE cycle_id = :c AND classification = 'CONSTRAINED'),
                       (SELECT COALESCE(SUM(allocatable_qty), 0) FROM plan_product_stock WHERE cycle_id = :c),
                       (SELECT COALESCE(SUM(COALESCE(revised_qty, published_qty)), 0) FROM plan_line WHERE cycle_id = :c),
                       (SELECT COALESCE(SUM(approved_alloc_qty), 0) FROM plan_line WHERE cycle_id = :c),
                       c.proposal_at, c.proposal_ruleset_version, c.proposal_parameter_version, c.allocation_stale
                FROM planning_cycle c WHERE c.id = :c
                """).param("c", cycleId).query((rs, n) -> {
                    m.put("products", rs.getInt(1));
                    m.put("constrained", rs.getInt(2));
                    m.put("allocatable", rs.getLong(3));
                    m.put("demand", rs.getLong(4));
                    m.put("approved", rs.getLong(5));
                    m.put("proposalAt", rs.getObject(6, java.time.OffsetDateTime.class));
                    m.put("rulesetVersion", rs.getString(7));
                    m.put("parameterVersion", rs.getObject(8));
                    m.put("stale", rs.getBoolean(9));
                    return null;
                }).list();
        return m;
    }

    // ---------------------------------------------------------------- export

    /**
     * Excel export of an allocation stage (PRD FR-PL-10, FR-RM-09): all regions, or one file for one region.
     *
     * @param cycleId the cycle
     * @param stage   {@code proposed}, {@code approved} or {@code final}
     * @param region  region code for a one-region file, or null for all regions
     * @param user    the caller: Planner/Admin may export anything; a Regional Manager only their own region
     * @return xlsx bytes
     * @throws ApiException 403 not allowed, 400 bad stage/region
     */
    public byte[] export(UUID cycleId, String stage, String region, CurrentUser user) {
        String column = switch (stage == null ? "" : stage.toLowerCase(Locale.ROOT)) {
            case "proposed" -> "proposed_alloc_qty";
            case "approved" -> "approved_alloc_qty";
            case "final" -> "final_alloc_qty";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_STAGE", "stage must be proposed, approved or final");
        };
        boolean privileged = user.has("planner") || user.has("admin");
        if (!privileged) {
            if (!user.has("regional_manager") || region == null || !user.inRegion(region)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Regional Managers may export their own region only");
            }
        }
        List<String> regions = region == null ? master.activeRegionCodes() : List.of(region.toUpperCase(Locale.ROOT));
        if (region != null && !master.activeRegionCodes().contains(regions.get(0))) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REGION", "Unknown region");
        String month = jdbc.sql("SELECT month FROM planning_cycle WHERE id = :c").param("c", cycleId).query(String.class).single();
        Map<String, Map<String, Object[]>> data = new java.util.TreeMap<>(); // product -> region -> {demand, qty}
        jdbc.sql("SELECT p.product_name, l.region_code, COALESCE(l.revised_qty, l.published_qty), " + column
                + " FROM plan_line l JOIN product p ON p.id = l.product_id WHERE l.cycle_id = :c")
            .param("c", cycleId).query((rs, n) -> data.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                .put(rs.getString(2), new Object[]{rs.getInt(3), rs.getObject(4)})).list();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Allocation");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Product_name");
            int c = 1;
            for (String r : regions) {
                h.createCell(c++).setCellValue(ExcelFiles.capitalise(r) + " demand");
                h.createCell(c++).setCellValue(ExcelFiles.capitalise(r) + " allocation");
            }
            h.createCell(c).setCellValue("Total allocation");
            int rowNo = 1;
            for (var e : data.entrySet()) {
                Row row = sheet.createRow(rowNo++);
                row.createCell(0).setCellValue(ExcelFiles.safe(e.getKey()));
                int col = 1, total = 0;
                for (String r : regions) {
                    Object[] v = e.getValue().getOrDefault(r, new Object[]{0, null});
                    row.createCell(col++).setCellValue((Integer) v[0]);
                    int q = v[1] == null ? 0 : (Integer) v[1];
                    row.createCell(col++).setCellValue(q);
                    total += q;
                }
                row.createCell(col).setCellValue(total);
            }
            Sheet meta = wb.createSheet("Metadata");
            String[][] info = {{"Cycle", month}, {"Stage", stage}, {"Regions", String.join(",", regions)}, {"Exported by", user.username()},
                {"Exported at (UTC)", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()}};
            for (int i = 0; i < info.length; i++) {
                Row r = meta.createRow(i);
                r.createCell(0).setCellValue(info[i][0]);
                r.createCell(1).setCellValue(ExcelFiles.safe(info[i][1]));
            }
            wb.write(out);
            audit.record(user.username(), "ALLOCATION_EXPORT", "planning_cycle", cycleId.toString(), Map.of("stage", stage, "regions", String.join(",", regions)));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Per-product region weights from historical sales (weight mode SALES_SHARE, Phase 7).
     *
     * <p>"Last N months" are the N most recent months present in the sales history (as in the Regional Manager's
     * sales analysis), so the weights stay meaningful when the data ends a month or two before today.
     *
     * @param regions active region codes in display order
     * @param months  look-back window
     * @return product id to weights in percent (region order, totalling 100); products without any sales in the window
     *         are absent, so the caller falls back to the fixed weights
     */
    private Map<UUID, int[]> salesShareWeights(List<String> regions, int months) {
        Map<UUID, long[]> sales = new HashMap<>();
        jdbcTemplate.query("""
                SELECT product_id, region_code, SUM(qty) FROM sales_history
                WHERE month IN (SELECT DISTINCT month FROM sales_history ORDER BY month DESC LIMIT ?)
                GROUP BY product_id, region_code
                """, rs -> {
            int idx = regions.indexOf(rs.getString(2));
            if (idx >= 0) sales.computeIfAbsent(rs.getObject(1, UUID.class), k -> new long[regions.size()])[idx] += rs.getLong(3);
        }, months);
        Map<UUID, int[]> out = new HashMap<>();
        sales.forEach((pid, s) -> {
            int[] w = AllocationCalculator.sharePercent(s);
            if (w != null) out.put(pid, w);
        });
        return out;
    }

    /**
     * Loads every plan line of the cycle with its product's stock, grouped by product (name order),
     * regions in display order.
     *
     * <p>Deliberately two simple indexed reads joined in Java instead of one SQL join: right after a bulk
     * import the table statistics are not yet collected, and Postgres then picked a nested loop over plan
     * lines x stock that took 27 s for 10,000 products. These two reads take well under a second
     * whatever the statistics say.
     */
    private Map<UUID, Product> load(UUID cycleId) {
        Map<UUID, Object[]> stock = new HashMap<>(); // product -> {allocatable, classification}
        jdbc.sql("SELECT product_id, allocatable_qty, classification FROM plan_product_stock WHERE cycle_id = :c").param("c", cycleId)
            .query((rs, n) -> stock.put(rs.getObject(1, UUID.class), new Object[]{rs.getInt(2), rs.getString(3)})).list();
        Map<String, Integer> regionOrder = new HashMap<>();
        List<String> regions = master.activeRegionCodes();
        for (int i = 0; i < regions.size(); i++) regionOrder.put(regions.get(i), i);

        Map<UUID, Product> byId = new HashMap<>();
        jdbc.sql("""
                SELECT l.product_id, p.product_name, l.region_code, COALESCE(l.revised_qty, l.published_qty), l.approved_alloc_qty, l.final_alloc_qty
                FROM plan_line l JOIN product p ON p.id = l.product_id WHERE l.cycle_id = :c
                """).param("c", cycleId).query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    Product p = byId.computeIfAbsent(id, k -> new Product());
                    p.id = id;
                    p.name = rs.getString(2);
                    p.demand.put(rs.getString(3), rs.getInt(4));
                    if (rs.getObject(5) != null) p.approved.put(rs.getString(3), rs.getInt(5));
                    if (rs.getObject(6) != null) p.finalQty.put(rs.getString(3), rs.getInt(6));
                    return null;
                }).list();

        Map<UUID, Product> ordered = new LinkedHashMap<>();
        byId.values().stream().sorted(java.util.Comparator.comparing((Product p) -> p.name).thenComparing(p -> p.id)).forEach(p -> {
            // Put each product's regions in display order (N, E, W, S) so every consumer sees the same order.
            Map<String, Integer> sortedDemand = new LinkedHashMap<>();
            p.demand.entrySet().stream().sorted(java.util.Comparator.comparingInt(e -> regionOrder.getOrDefault(e.getKey(), 99)))
                .forEach(e -> sortedDemand.put(e.getKey(), e.getValue()));
            p.demand.clear();
            p.demand.putAll(sortedDemand);
            Object[] s = stock.get(p.id);
            p.allocatable = s == null ? 0 : (Integer) s[0];
            p.classification = s == null ? null : (String) s[1];
            ordered.put(p.id, p);
        });
        return ordered;
    }

    private void requireState(UUID cycleId, String state) {
        String s = jdbc.sql("SELECT state FROM planning_cycle WHERE id = :c").param("c", cycleId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Cycle not found"));
        if (!state.equals(s)) throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATE", "This needs state " + state + " but the cycle is " + s);
    }
}
