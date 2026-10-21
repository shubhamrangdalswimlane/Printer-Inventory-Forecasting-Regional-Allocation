package com.pifa.golive;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.ingestion.ExcelReader;
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
 * Product matching report (Phase 6a; go-live checklist item 8: "real-file matching report shows zero unknown products").
 *
 * <p>An Admin uploads a real file from an upstream system - a forecast, inventory, sales or allocation workbook - and
 * gets, for every distinct {@code Product_name} in it, whether it matches the product master exactly, through an
 * alias, only an inactive product, or not at all. Unknown names come with up to three suggested master products
 * ({@link NameMatcher}), so the Admin can add the right alias. Nothing is stored except the result summary in
 * {@code system_status} (key {@code golive.matching}), which the go-live readiness check reads, and an audit row.
 *
 * <p>The file is never ingested: this is a dry run of the name-matching step only.
 */
@Service
public class MatchingReportService {

    /** Worksheets tried in order: the four templates that carry a {@code Product_name} column. */
    static final List<String> SHEETS = List.of("Forecast", "Inventory", "Sales", "Allocation");

    /** How a name matched. INACTIVE behaves like UNKNOWN at ingestion (the file would be rejected). */
    public enum Match { EXACT, ALIAS, INACTIVE, UNKNOWN }

    /**
     * One distinct name that did not match an active product.
     *
     * @param name        the name as first written in the file
     * @param match       INACTIVE or UNKNOWN
     * @param occurrences how many rows carry it
     * @param rows        the first few spreadsheet row numbers, to find it in the file
     * @param suggestions closest master products, best first
     */
    public record Problem(String name, Match match, int occurrences, List<Integer> rows, List<NameMatcher.Suggestion> suggestions) {}

    /**
     * The report.
     *
     * @param fileName      the uploaded file name
     * @param distinctNames distinct normalised names in the file
     * @param exact         names matching a product name
     * @param alias         names matching through an alias
     * @param problems      names that would make ingestion fail (inactive or unknown), most frequent first
     * @param ok            true when there are no problems
     */
    public record Report(String fileName, int distinctNames, int exact, int alias, List<Problem> problems, boolean ok) {}

    private final JdbcClient jdbc;
    private final VirusScanner scanner;
    private final AuditService audit;

    public MatchingReportService(JdbcClient jdbc, VirusScanner scanner, AuditService audit) {
        this.jdbc = jdbc;
        this.scanner = scanner;
        this.audit = audit;
    }

    /**
     * Builds the matching report for one file.
     *
     * @param bytes    the .xlsx file
     * @param fileName its name (only echoed back and recorded)
     * @param user     the caller; must be Admin
     * @return the report
     * @throws ApiException 403 for non-Admins; 422 if the file is not a readable workbook with a Product_name column
     *                      or is infected; 503 if the virus scanner is unavailable
     */
    public Report report(byte[] bytes, String fileName, CurrentUser user) {
        if (!user.has("admin")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only an Admin can run the matching report");
        GoLiveFiles.scan(scanner, bytes);
        ExcelReader.SheetData sheet = GoLiveFiles.read(bytes, SHEETS, List.of("Product_name"), Set.of("Product_name"), 200_000);

        // Master lookups by normalised text.
        Map<String, Boolean> productActive = new HashMap<>();
        jdbc.sql("SELECT normalized_name, active FROM product").query((rs, n) -> productActive.put(rs.getString(1), rs.getBoolean(2))).list();
        Set<String> activeAliases = new HashSet<>(jdbc.sql(
            "SELECT a.normalized_alias FROM product_alias a JOIN product p ON p.id = a.product_id WHERE p.active").query(String.class).list());
        Map<UUID, String> activeProducts = new HashMap<>();
        jdbc.sql("SELECT id, product_name FROM product WHERE active").query((rs, n) -> activeProducts.put(rs.getObject(1, UUID.class), rs.getString(2))).list();

        // Group the file's rows by normalised name, keeping the first spelling and the first row numbers.
        record Seen(String firstSpelling, List<Integer> rows) {}
        Map<String, Seen> names = new LinkedHashMap<>();
        for (ExcelReader.RowData r : sheet.rows()) {
            String raw = GoLiveFiles.text(r.get("Product_name"));
            if (raw == null) continue;
            names.computeIfAbsent(ProductNames.normalize(raw), k -> new Seen(raw, new ArrayList<>())).rows().add(r.rowNo());
        }

        NameMatcher matcher = new NameMatcher(activeProducts);
        int exact = 0, alias = 0;
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<String, Seen> e : names.entrySet()) {
            Boolean active = productActive.get(e.getKey());
            Match m = Boolean.TRUE.equals(active) ? Match.EXACT
                : activeAliases.contains(e.getKey()) ? Match.ALIAS
                : active != null ? Match.INACTIVE : Match.UNKNOWN;
            if (m == Match.EXACT) exact++;
            else if (m == Match.ALIAS) alias++;
            else {
                List<Integer> rows = e.getValue().rows();
                problems.add(new Problem(e.getValue().firstSpelling(), m, rows.size(), List.copyOf(rows.subList(0, Math.min(5, rows.size()))),
                    m == Match.UNKNOWN ? matcher.suggest(e.getValue().firstSpelling(), 3) : List.of()));
            }
        }
        problems.sort((a, b) -> Integer.compare(b.occurrences(), a.occurrences()));

        Report report = new Report(fileName, names.size(), exact, alias, problems, problems.isEmpty());
        String summary = fileName + ": " + names.size() + " names, " + problems.size() + " not matched";
        jdbc.sql("INSERT INTO system_status (key, value, ok, updated_at) VALUES ('golive.matching', :v, :ok, now()) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, ok = EXCLUDED.ok, updated_at = now()")
            .param("v", summary).param("ok", report.ok()).update();
        audit.record(user.username(), "MATCHING_REPORT", "file", fileName,
            Map.of("distinct", names.size(), "exact", exact, "alias", alias, "problems", problems.size()));
        return report;
    }
}
