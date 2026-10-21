package com.pifa.ingestion;

import com.pifa.audit.AuditService;
import com.pifa.config.PifaProperties;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetType;
import com.pifa.ingestion.ExcelReader.RawCell;
import com.pifa.ingestion.ExcelReader.RowData;
import com.pifa.ingestion.ExcelReader.SheetData;
import com.pifa.allocation.StockService;
import com.pifa.forecast.WorkingCopyService;
import com.pifa.parameters.RuleParameterService;
import com.pifa.master.MasterDataService;
import com.pifa.notify.NotificationService;
import com.pifa.master.ProductNames;
import com.pifa.rules.RulesFacade;
import com.pifa.rules.model.DateCell;
import com.pifa.rules.model.IngestionConfig;
import com.pifa.rules.model.MonthCell;
import com.pifa.rules.model.ProductRow;
import com.pifa.rules.model.QtyCell;
import com.pifa.rules.model.Violation;
import com.pifa.storage.FileStore;
import com.pifa.storage.VirusScanner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ingestion pipeline: bytes in, either a new VALID dataset version or a REJECTED job out.
 *
 * <p>The same method serves every channel (SFTP poller, UI upload, synthetic generator), so they
 * are validated and versioned identically (PRD 8.1). Order of work:
 * <ol>
 *   <li>size limit, duplicate-content check, antivirus scan;</li>
 *   <li>open the workbook and check structure ({@link ExcelReader});</li>
 *   <li>turn rows into facts, run the Drools ingestion rules, add Java checks (cycle, missing products);</li>
 *   <li>if any ERROR exists the file is rejected as a whole (all-or-nothing, PRD 8.4);</li>
 *   <li>otherwise, in <b>one database transaction</b>: create the version, its lines, the job record,
 *       auto-activate if allowed, and write the audit entry.</li>
 * </ol>
 * Nothing is written to the database before step 5 except on rejection, so a crash can never leave a partial version.
 */
@Service
public class IngestionService {

    /** Everything needed to ingest one file. */
    public record IngestRequest(byte[] bytes, String filename, DatasetType type, String channel, String actor, String account) {}

    /** One finding as stored and returned to clients. */
    public record Finding(int rowNo, String column, String code, String severity, String message) {}

    /**
     * Outcome of an ingestion.
     *
     * @param jobId     id of the ingestion job (always created, also for rejections)
     * @param status    {@code VALID} or {@code REJECTED}
     * @param versionId the new version's id when VALID, else null
     * @param label     the new version's label (e.g. FC-2026-11-v1) when VALID, else null
     * @param activated true if the new version was auto-activated
     * @param findings  all errors and warnings
     */
    public record IngestResult(UUID jobId, String status, UUID versionId, String label, boolean activated, List<Finding> findings) {}

    /** Open cycle states in which a new inventory file may become active (PRD 8.5). */
    private static final Set<String> INVENTORY_ACTIVATE_STATES = Set.of("FORECAST_DRAFT", "REGIONAL_REVIEW", "PLANNING");
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final RulesFacade rules;
    private final RuleParameterService params;
    private final StockService stock;
    private final com.pifa.events.LiveEvents events;
    private final com.pifa.ops.PifaMetrics metrics;
    private final MasterDataService master;
    private final DatasetRepository repo;
    private final FileStore files;
    private final VirusScanner scanner;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final PifaProperties props;
    private final WorkingCopyService workingCopy;
    private final NotificationService notifications;

    public IngestionService(RulesFacade rules, MasterDataService master, DatasetRepository repo, FileStore files,
                            VirusScanner scanner, AuditService audit, JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                            TransactionTemplate tx, PifaProperties props, WorkingCopyService workingCopy,
                            NotificationService notifications, RuleParameterService params, StockService stock,
                            com.pifa.events.LiveEvents events, com.pifa.ops.PifaMetrics metrics) {
        this.events = events;
        this.metrics = metrics;
        this.params = params;
        this.stock = stock;
        this.workingCopy = workingCopy;
        this.notifications = notifications;
        this.rules = rules;
        this.master = master;
        this.repo = repo;
        this.files = files;
        this.scanner = scanner;
        this.audit = audit;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = tx;
        this.props = props;
    }

    /** Data collected while validating, needed later to write the version. */
    private static final class Prepared {
        final List<Violation> violations = new ArrayList<>();
        final Map<String, List<Object[]>> lines = new LinkedHashMap<>(); // table -> rows of column values (without version id)
        String month;           // cycle month the file belongs to (null if it could not be determined)
        int rowCount;
    }

    /**
     * Ingests one file.
     *
     * @param req the file and its origin
     * @return the result; a REJECTED result is a normal outcome, not an exception
     * @throws IllegalArgumentException if {@code req.type()} is not an upload type
     */
    public IngestResult ingest(IngestRequest req) {
        long t0 = System.nanoTime();
        IngestResult r = doIngest(req);
        metrics.ingestion(req.type().name(), r.status(), req.channel(), System.nanoTime() - t0);
        return r;
    }

    /** The actual pipeline; {@link #ingest} wraps it with metrics. */
    private IngestResult doIngest(IngestRequest req) {
        if (!req.type().isUpload()) throw new IllegalArgumentException("Not an uploadable type: " + req.type());
        String sha = sha256(req.bytes());
        Prepared p = validate(req, sha);

        boolean hasError = p.violations.stream().anyMatch(v -> v.getSeverity() == Violation.Severity.ERROR);
        if (hasError) return reject(req, sha, p.violations);

        String objectKey = null;
        try {
            // File is written before the transaction; if the transaction fails the file is removed again below.
            UUID versionId = UUID.randomUUID();
            String[] keyHolder = new String[1];
            IngestResult result = tx.execute(status -> commit(req, sha, p, versionId, keyHolder));
            objectKey = keyHolder[0];
            return result;
        } catch (DuplicateKeyException e) {
            // Two identical files raced past the pre-check; the unique index on sha256 caught the loser.
            return reject(req, sha, List.of(Violation.error("DUPLICATE_FILE", 0, null, "A file with the same content was already ingested")));
        }
    }

    // ---------------------------------------------------------------- validation

    /**
     * Runs every check that needs no database writes and prepares the lines to insert.
     *
     * @param req the file and its origin
     * @param sha SHA-256 of the file content
     * @return findings plus (when no ERROR) the prepared lines, month and row count
     */
    private Prepared validate(IngestRequest req, String sha) {
        Prepared p = new Prepared();
        IngestionConfig cfg = new IngestionConfig(props.ingestion().maxQty(), params.current().values().spikeFactor());

        if (req.bytes().length > props.ingestion().maxFileBytes()) {
            p.violations.add(Violation.error("FILE_TYPE", 0, null, "File is larger than " + props.ingestion().maxFileBytes() / 1_048_576 + " MB"));
            return p;
        }
        boolean duplicate = jdbc.sql("SELECT EXISTS (SELECT 1 FROM dataset_version WHERE sha256 = :s)").param("s", sha).query(Boolean.class).single();
        if (duplicate) {
            p.violations.add(Violation.error("DUPLICATE_FILE", 0, null, "A file with the same content was already ingested"));
            return p;
        }
        try {
            if (!scanner.isClean(req.bytes())) {
                p.violations.add(Violation.error("FILE_VIRUS", 0, null, "The antivirus scan found malware"));
                return p;
            }
        } catch (RuntimeException e) {
            // Fail closed: if we cannot prove the file is clean, we do not accept it.
            p.violations.add(Violation.error("FILE_VIRUS", 0, null, "The antivirus scan failed: " + e.getMessage()));
            return p;
        }

        boolean forecast = req.type() == DatasetType.FORECAST_UPLOAD;
        List<String> regions = master.activeRegionCodes();
        List<String> required = new ArrayList<>(req.type().fixedRequiredHeaders());
        Set<String> known = new HashSet<>(required);
        known.add("Product_code"); // optional in both templates (Phase 7)
        if (forecast) {
            regions.forEach(r -> required.add(ExcelFiles.capitalise(r)));
            known.addAll(required);
            known.add("Comments");
        } else {
            known.add("Planning_Month");
        }

        SheetData sheet = ExcelReader.read(req.bytes(), req.type().sheetName(), required, known, props.ingestion().maxRows());
        p.violations.addAll(sheet.violations());
        if (sheet.hasErrors()) return p;

        Map<String, UUID> lookup = master.productLookup();
        Map<String, UUID> codes = master.productCodeLookup();
        List<Object> facts = new ArrayList<>();
        Set<UUID> seenProducts = new HashSet<>();
        List<Object[]> lines = new ArrayList<>();

        if (forecast) {
            Map<String, Double> avg = avgSales6();
            String expectedMonth = sheet.rows().stream().map(r -> parseMonth(r.get("Forecast_Month"))).filter(m -> m != null).findFirst().orElse(null);
            for (RowData row : sheet.rows()) {
                UUID pid = addProductFact(row, lookup, codes, facts, seenProducts, p.violations);
                String m = parseMonth(row.get("Forecast_Month"));
                facts.add(new MonthCell(row.rowNo(), "Forecast_Month", m != null, m, expectedMonth));
                String comment = text(row.get("Comments"));
                if (comment != null && comment.length() > 500) {
                    p.violations.add(Violation.warning("COMMENT_TRUNCATED", row.rowNo(), "Comments", "Comment longer than 500 characters was truncated"));
                    comment = comment.substring(0, 500);
                }
                for (String region : regions) {
                    String header = ExcelFiles.capitalise(region);
                    QtyParse q = parseQty(row.get(header));
                    double a = pid == null ? -1 : avg.getOrDefault(pid + "|" + region, -1.0);
                    facts.add(new QtyCell(row.rowNo(), header, q.valid, q.value, q.reason, a));
                    if (pid != null && q.valid) lines.add(new Object[]{pid, region, (int) Math.min(q.value, Integer.MAX_VALUE), comment});
                }
            }
            p.month = expectedMonth;
        } else {
            LocalDate firstDate = sheet.rows().stream().map(r -> parseDate(r.get("As_Of_Date"))).filter(d -> d != null).findFirst().orElse(null);
            String expectedMonth = sheet.rows().stream().map(r -> parseMonth(r.get("Planning_Month"))).filter(m -> m != null).findFirst().orElse(null);
            for (RowData row : sheet.rows()) {
                UUID pid = addProductFact(row, lookup, codes, facts, seenProducts, p.violations);
                QtyParse q = parseQty(row.get("Available_Qty"));
                facts.add(new QtyCell(row.rowNo(), "Available_Qty", q.valid, q.value, q.reason, -1));
                LocalDate d = parseDate(row.get("As_Of_Date"));
                facts.add(new DateCell(row.rowNo(), d != null, d != null && d.equals(firstDate), d != null && d.isAfter(LocalDate.now())));
                RawCell pm = row.get("Planning_Month");
                if (pm != null && pm.value() != null && !pm.value().toString().isBlank()) {
                    String m = parseMonth(pm);
                    facts.add(new MonthCell(row.rowNo(), "Planning_Month", m != null, m, expectedMonth));
                }
                if (pid != null && q.valid && d != null) lines.add(new Object[]{pid, (int) Math.min(q.value, Integer.MAX_VALUE), d});
            }
            p.month = expectedMonth; // may be null: resolved below from the single open cycle
        }
        p.rowCount = sheet.rows().size();

        p.violations.addAll(rules.validateIngestion(facts, cfg));

        // Warning: active catalogue products absent from the file are treated as 0.
        for (MasterDataService.Product prod : master.activeProducts()) {
            if (!seenProducts.contains(prod.id())) {
                p.violations.add(Violation.warning("PRODUCT_NOT_IN_FILE", 0, "Product_name", "Active product '" + prod.name() + "' is not in the file (treated as 0)"));
            }
        }

        resolveCycle(req.type(), p);
        p.lines.put(forecast ? "forecast_line" : "inventory_line", lines);
        return p;
    }

    /**
     * Cycle checks that need the database (PRD 8.3): a forecast must target a cycle that accepts
     * forecasts; an inventory file must name an existing cycle, or there must be exactly one open cycle.
     * Adds ERROR findings and sets {@code p.month} for inventory files without a Planning_Month.
     */
    private void resolveCycle(DatasetType type, Prepared p) {
        boolean anyError = p.violations.stream().anyMatch(v -> v.getSeverity() == Violation.Severity.ERROR);
        if (type == DatasetType.FORECAST_UPLOAD) {
            if (p.month == null || anyError) return; // month problems are already reported by the rules
            var cycle = repo.findCycleByMonth(p.month);
            if (cycle.isEmpty() && !props.cycle().autoCreate()) {
                p.violations.add(Violation.error("MONTH_MISMATCH", 0, "Forecast_Month", "Cycle " + p.month + " does not exist"));
            } else if (cycle.isPresent() && !"FORECAST_DRAFT".equals(cycle.get().state())) {
                p.violations.add(Violation.error("MONTH_MISMATCH", 0, "Forecast_Month", "Cycle " + p.month + " no longer accepts forecast uploads (state " + cycle.get().state() + ")"));
            }
        } else {
            if (anyError) return;
            if (p.month != null) {
                if (repo.findCycleByMonth(p.month).isEmpty()) {
                    p.violations.add(Violation.error("CYCLE_NOT_FOUND", 0, "Planning_Month", "Cycle " + p.month + " does not exist"));
                }
                return;
            }
            var open = repo.openCycles();
            if (open.isEmpty()) {
                p.violations.add(Violation.error("CYCLE_NOT_FOUND", 0, "Planning_Month", "No open cycle exists; load a forecast first"));
            } else if (open.size() > 1) {
                p.violations.add(Violation.error("CYCLE_AMBIGUOUS", 0, "Planning_Month", "More than one cycle is open; Planning_Month is required"));
            } else {
                p.month = open.get(0).month();
            }
        }
    }

    /**
     * Identifies the product of a row, builds its {@link ProductRow} fact and returns the matched product id.
     *
     * <p>Without a {@code Product_code} the name is matched (normalisation + aliases) as before. With a code
     * (optional column, Phase 7) the code decides: an unknown code is reported like an unknown product, and a name
     * that matches a <em>different</em> product than the code is an error (PRODUCT_CODE_MISMATCH), because one of the
     * two upstream fields is wrong. The duplicate check runs on the product itself, so the same product listed once
     * by name and once by alias or code is also caught.
     *
     * @param row        the spreadsheet row
     * @param lookup     normalised name/alias to product id
     * @param codes      normalised code to product id
     * @param facts      rule facts (the ProductRow is added here)
     * @param seen       products present in the file (for the "not in file" warning)
     * @param violations findings decided in Java (the code mismatch)
     * @return the product id, or null if blank or unknown
     */
    private UUID addProductFact(RowData row, Map<String, UUID> lookup, Map<String, UUID> codes, List<Object> facts, Set<UUID> seen,
                                List<Violation> violations) {
        String raw = text(row.get("Product_name"));
        String norm = ProductNames.normalize(raw);
        UUID byName = norm.isEmpty() ? null : lookup.get(norm);
        String code = ProductNames.normalizeCode(text(row.get("Product_code")));
        UUID pid;
        String shown;
        boolean mismatch = false;
        if (code.isEmpty()) {
            pid = byName;
            shown = raw;
        } else {
            pid = codes.get(code);
            shown = raw == null ? "code " + code : raw + " (code " + code + ")";
            if (pid != null && byName != null && !pid.equals(byName)) {
                violations.add(Violation.error("PRODUCT_CODE_MISMATCH", row.rowNo(), "Product_code",
                    "Product_code " + code + " and Product_name '" + raw + "' name different products"));
                pid = null;
                mismatch = true; // already reported; not also "unknown"
            }
        }
        // Key for the duplicate rule: the product when known, otherwise the text, so unknown duplicates are still caught.
        String key = pid != null ? pid.toString() : code.isEmpty() ? norm : "code:" + code;
        facts.add(new ProductRow(row.rowNo(), shown, pid != null || mismatch, key));
        if (pid != null) seen.add(pid);
        return pid;
    }

    /** Result of parsing one quantity cell. */
    private record QtyParse(boolean valid, long value, String reason) {}

    /**
     * Parses a quantity: must be a whole number &gt;= 0. Numeric cells and integer-looking text are accepted.
     *
     * @param c the cell (may be null)
     * @return valid with the value, or invalid with a reason (blank, formula without value, not an integer, negative)
     */
    private static QtyParse parseQty(RawCell c) {
        if (c == null || (c.value() == null && !c.uncachedFormula())) return new QtyParse(false, 0, "blank");
        if (c.uncachedFormula()) return new QtyParse(false, 0, "formula has no stored value");
        Object v = c.value();
        double d;
        if (v instanceof Double dd) d = dd;
        else if (v instanceof String s) {
            try { d = Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return new QtyParse(false, 0, "not a number: '" + s + "'"); }
        } else return new QtyParse(false, 0, "not a number");
        if (d != Math.rint(d) || Double.isInfinite(d)) return new QtyParse(false, 0, "not a whole number");
        if (d < 0) return new QtyParse(false, 0, "negative");
        return new QtyParse(true, (long) d, null);
    }

    /** @return the cell as normalised {@code YYYY-MM}, or null if blank/invalid (a date cell is converted to its month) */
    private static String parseMonth(RawCell c) {
        if (c == null || c.value() == null) return null;
        Object v = c.value();
        if (v instanceof LocalDate d) return YearMonth.from(d).toString(); // Excel turned "2026-11" into a date
        String s = v.toString().trim();
        return MONTH.matcher(s).matches() ? s : null;
    }

    /** @return the cell as a date (a date cell, or ISO {@code YYYY-MM-DD} text), or null if blank/invalid */
    private static LocalDate parseDate(RawCell c) {
        if (c == null || c.value() == null) return null;
        if (c.value() instanceof LocalDate d) return d;
        try { return LocalDate.parse(c.value().toString().trim()); } catch (DateTimeParseException e) { return null; }
    }

    /** @return the cell as trimmed text (numbers rendered plainly), or null if empty */
    private static String text(RawCell c) {
        if (c == null || c.value() == null) return null;
        Object v = c.value();
        if (v instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return v.toString().trim();
    }

    /**
     * Average monthly sales over the six most recent months in {@code sales_history}.
     *
     * @return map {@code productId|REGION} to average; empty when there is no history
     */
    private Map<String, Double> avgSales6() {
        Map<String, Double> map = new HashMap<>();
        jdbc.sql("""
                SELECT product_id, region_code, AVG(qty) FROM sales_history
                WHERE month IN (SELECT DISTINCT month FROM sales_history ORDER BY month DESC LIMIT 6)
                GROUP BY product_id, region_code
                """).query((rs, n) -> map.put(rs.getObject(1, UUID.class) + "|" + rs.getString(2), rs.getDouble(3))).list();
        return map;
    }

    // ---------------------------------------------------------------- persistence

    /**
     * Writes everything for a VALID file inside the caller's transaction.
     *
     * @param keyHolder one-element array that receives the stored object key (so the caller can clean up on failure)
     */
    private IngestResult commit(IngestRequest req, String sha, Prepared p, UUID versionId, String[] keyHolder) {
        DatasetRepository.Cycle cycle = repo.lockOrCreateCycle(p.month);
        // Re-check under the lock: the state may have changed since validation.
        if (req.type() == DatasetType.FORECAST_UPLOAD && !"FORECAST_DRAFT".equals(cycle.state())) {
            throw new IllegalStateException("Cycle " + cycle.month() + " no longer accepts forecast uploads");
        }
        int no = repo.nextVersionNo(cycle.id(), req.type());
        String label = req.type().labelPrefix() + "-" + cycle.month() + "-v" + no;
        String key = "raw/" + req.type() + "/" + cycle.month() + "/v" + no + "/original.xlsx";
        files.put(key, req.bytes());
        keyHolder[0] = key;
        try {
            repo.insertVersion(versionId, cycle.id(), req.type(), no, label, req.channel(), req.filename(), key, sha, p.rowCount, req.actor());
            insertLines(req.type(), versionId, p.lines.values().iterator().next());

            // PRD 8.5: a forecast auto-activates only in FORECAST_DRAFT and only if the Admin setting allows it;
            // an inventory file becomes active in any state up to PLANNING.
            boolean activate = req.type() == DatasetType.FORECAST_UPLOAD
                ? "FORECAST_DRAFT".equals(cycle.state()) && params.current().values().autoActivateForecast()
                : INVENTORY_ACTIVATE_STATES.contains(cycle.state());
            if (activate) repo.activate(versionId);
            // A newly active forecast replaces the Forecaster's working copy (edits are snapshotted first);
            // a newly active inventory replaces the cycle's stock (reserves are kept).
            if (activate && req.type() == DatasetType.FORECAST_UPLOAD) workingCopy.loadFromVersion(cycle.id(), versionId, req.actor());
            if (activate && req.type() == DatasetType.INVENTORY_UPLOAD) stock.applyInventory(cycle.id(), versionId, req.actor());
            if (!activate && req.type() == DatasetType.INVENTORY_UPLOAD) {
                // Stored but not applied: the allocation is already approved or final (PRD 8.5).
                String text = "Inventory file '" + req.filename() + "' arrived after the allocation was approved (cycle " + cycle.month()
                    + " is " + cycle.state() + "). It was stored as " + label + " but not applied.";
                notifications.notifyRole("admin", null, "Inventory changed after approval", text, cycle.id());
                notifications.notifyRole("planner", null, "Inventory changed after approval", text, cycle.id());
            }
            if (!activate && req.type() == DatasetType.FORECAST_UPLOAD) {
                // Stored but not activated: already published, or auto-activate is switched off (PRD 8.5).
                notifications.notifyRole("forecaster", null, "New forecast file not activated",
                    "Forecast file '" + req.filename() + "' was stored as " + label + " but not activated (cycle " + cycle.month() + " is " + cycle.state() + ").", cycle.id());
            }

            UUID jobId = saveJob(req, sha, "VALID", versionId, p.violations);
            events.publish(cycle.id(), "DATASET");
            audit.record(req.actor(), "UPLOAD", "dataset_version", versionId.toString(),
                Map.of("label", label, "filename", String.valueOf(req.filename()), "channel", req.channel(), "activated", activate, "rows", p.rowCount));
            return new IngestResult(jobId, "VALID", versionId, label, activate, toFindings(p.violations));
        } catch (RuntimeException e) {
            files.delete(key); // do not leave an orphan file when the database part fails
            throw e;
        }
    }

    /** Batch-inserts the prepared forecast or inventory lines. */
    private void insertLines(DatasetType type, UUID versionId, List<Object[]> lines) {
        if (type == DatasetType.FORECAST_UPLOAD) {
            jdbcTemplate.batchUpdate("INSERT INTO forecast_line (version_id, product_id, region_code, qty, comment) VALUES (?,?,?,?,?)", lines, 1000,
                (ps, l) -> { ps.setObject(1, versionId); ps.setObject(2, l[0]); ps.setString(3, (String) l[1]); ps.setInt(4, (Integer) l[2]); ps.setString(5, (String) l[3]); });
        } else {
            jdbcTemplate.batchUpdate("INSERT INTO inventory_line (version_id, product_id, available_qty, as_of_date) VALUES (?,?,?,?)", lines, 1000,
                (ps, l) -> { ps.setObject(1, versionId); ps.setObject(2, l[0]); ps.setInt(3, (Integer) l[1]); ps.setObject(4, l[2]); });
        }
    }

    /** Records a rejection: job row + findings + audit entry, in one transaction. Never throws for bad files. */
    private IngestResult reject(IngestRequest req, String sha, List<Violation> violations) {
        return tx.execute(status -> {
            UUID jobId = saveJob(req, sha, "REJECTED", null, violations);
            audit.record(req.actor(), "UPLOAD_REJECTED", "ingestion_job", jobId.toString(),
                Map.of("filename", String.valueOf(req.filename()), "channel", req.channel(),
                    "codes", violations.stream().filter(v -> v.getSeverity() == Violation.Severity.ERROR).map(Violation::getCode).distinct().toList()));
            // Tell the owning role (and Admin) that a file was rejected (PRD FR-COM-06 "ingestion failed").
            String owner = req.type() == DatasetType.FORECAST_UPLOAD ? "forecaster" : "planner";
            String text = "File '" + req.filename() + "' was rejected: " + violations.stream().filter(v -> v.getSeverity() == Violation.Severity.ERROR)
                .map(Violation::getCode).distinct().toList();
            notifications.notifyRole(owner, null, "Ingestion failed", text, null);
            notifications.notifyRole("admin", null, "Ingestion failed", text, null);
            return new IngestResult(jobId, "REJECTED", null, null, false, toFindings(violations));
        });
    }

    /** Inserts the ingestion_job row and its findings; returns the job id. Must run inside a transaction. */
    private UUID saveJob(IngestRequest req, String sha, String status, UUID versionId, List<Violation> violations) {
        UUID jobId = UUID.randomUUID();
        long errors = violations.stream().filter(v -> v.getSeverity() == Violation.Severity.ERROR).count();
        jdbc.sql("""
                INSERT INTO ingestion_job (id, channel, source_account, dataset_type, original_filename, sha256, status,
                                           error_count, warning_count, version_id, created_by, finished_at)
                VALUES (:id, :ch, :acc, :t, :f, :h, :st, :e, :w, :v, :u, now())
                """)
            .param("id", jobId).param("ch", req.channel()).param("acc", req.account()).param("t", req.type().name())
            .param("f", req.filename()).param("h", sha).param("st", status).param("e", (int) errors)
            .param("w", (int) (violations.size() - errors)).param("v", versionId).param("u", req.actor()).update();
        jdbcTemplate.batchUpdate("INSERT INTO ingestion_error (job_id, row_no, column_name, code, severity, message) VALUES (?,?,?,?,?,?)",
            violations, 500, (ps, v) -> { ps.setObject(1, jobId); ps.setInt(2, v.getRowNo()); ps.setString(3, v.getColumn()); ps.setString(4, v.getCode()); ps.setString(5, v.getSeverity().name()); ps.setString(6, v.getMessage()); });
        return jobId;
    }

    private static List<Finding> toFindings(List<Violation> vs) {
        return vs.stream().map(v -> new Finding(v.getRowNo(), v.getColumn(), v.getCode(), v.getSeverity().name(), v.getMessage())).toList();
    }

    /** @return lower-case hex SHA-256 of the bytes */
    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
