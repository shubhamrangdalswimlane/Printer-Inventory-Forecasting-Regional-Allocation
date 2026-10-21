package com.pifa.forecast;

import com.pifa.common.ApiException;
import com.pifa.security.CurrentUser;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Historical sales analysis for the Regional Manager (PRD FR-RM-03): for each product of a region,
 * the average monthly sales over the last 3, 6, 12 and 24 months, the same month last year, a 12-month
 * trend series for the sparkline, and the published and revised forecast to compare against.
 *
 * <p>"Last N months" means the N most recent calendar months present in the sales history, so the
 * numbers stay meaningful even if the data ends a month or two before today.
 */
@Service
public class SalesAnalysisService {

    /**
     * One product's analysis row.
     *
     * @param productId         product id
     * @param product           product name
     * @param avg3              average monthly sales of the last 3 months
     * @param avg6              average of the last 6 months
     * @param avg12             average of the last 12 months
     * @param avg24             average of the last 24 months
     * @param sameMonthLastYear sales in the cycle's month one year earlier, or null if not in the history
     * @param series            the last 12 monthly quantities, oldest first (for the sparkline)
     * @param published         published forecast quantity for this region
     * @param revised           revised forecast quantity for this region, or null before publish
     */
    public record Row(UUID productId, String product, double avg3, double avg6, double avg12, double avg24,
                      Integer sameMonthLastYear, List<Integer> series, int published, Integer revised) {}

    /**
     * A monthly sales point.
     *
     * @param month YYYY-MM
     * @param qty   units sold
     */
    public record Point(String month, int qty) {}

    private final JdbcClient jdbc;

    public SalesAnalysisService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Analysis of every product in a region for a cycle.
     *
     * @param cycleId the cycle (supplies the forecast numbers and the "same month last year")
     * @param region  region code
     * @param user    the caller; a Regional Manager may only ask for their own region(s)
     * @return one row per product that is in the cycle's working copy, ordered by product name
     * @throws ApiException 403 if a Regional Manager asks for a region that is not theirs, 404 for an unknown cycle
     */
    public List<Row> analysis(UUID cycleId, String region, CurrentUser user) {
        String reg = region.toUpperCase();
        requireRegionAccess(user, reg);
        String month = jdbc.sql("SELECT month FROM planning_cycle WHERE id = :id").param("id", cycleId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Cycle not found"));
        String lastYear = YearMonth.parse(month).minusYears(1).toString();

        // product -> (month -> qty), months sorted ascending
        Map<UUID, TreeMap<String, Integer>> sales = new HashMap<>();
        jdbc.sql("SELECT product_id, month, qty FROM sales_history WHERE region_code = :r").param("r", reg)
            .query((rs, n) -> sales.computeIfAbsent(rs.getObject(1, UUID.class), k -> new TreeMap<>()).put(rs.getString(2), rs.getInt(3))).list();
        // The latest month with any sales anchors every "last N months" window.
        String latest = sales.values().stream().filter(m -> !m.isEmpty()).map(TreeMap::lastKey).max(String::compareTo).orElse(null);

        List<Row> out = new ArrayList<>();
        jdbc.sql("""
                SELECT p.id, p.product_name, l.published_qty, l.revised_qty FROM plan_line l JOIN product p ON p.id = l.product_id
                WHERE l.cycle_id = :c AND l.region_code = :r ORDER BY p.product_name
                """).param("c", cycleId).param("r", reg)
            .query((rs, n) -> {
                UUID pid = rs.getObject(1, UUID.class);
                TreeMap<String, Integer> m = sales.getOrDefault(pid, new TreeMap<>());
                List<Integer> series = new ArrayList<>();
                if (latest != null) {
                    YearMonth end = YearMonth.parse(latest);
                    for (int i = 11; i >= 0; i--) series.add(m.getOrDefault(end.minusMonths(i).toString(), 0));
                }
                out.add(new Row(pid, rs.getString(2), avg(m, latest, 3), avg(m, latest, 6), avg(m, latest, 12), avg(m, latest, 24),
                    m.get(lastYear), series, rs.getInt(3), (Integer) rs.getObject(4)));
                return null;
            }).list();
        return out;
    }

    /**
     * The monthly sales series of one product in one region, for the drill-down chart.
     *
     * @param productId product id
     * @param region    region code
     * @param months    how many most recent months to return (1..60)
     * @param user      the caller (same region rule as {@link #analysis})
     * @return points, oldest first
     */
    public List<Point> history(UUID productId, String region, int months, CurrentUser user) {
        String reg = region.toUpperCase();
        requireRegionAccess(user, reg);
        List<Point> pts = new ArrayList<>(jdbc.sql("SELECT month, qty FROM sales_history WHERE product_id = :p AND region_code = :r ORDER BY month DESC LIMIT :n")
            .param("p", productId).param("r", reg).param("n", Math.min(Math.max(months, 1), 60))
            .query((rs, n) -> new Point(rs.getString(1), rs.getInt(2))).list());
        java.util.Collections.reverse(pts);
        return pts;
    }

    /** Average over the N calendar months ending at {@code latest}; months with no row count as 0. */
    static double avg(TreeMap<String, Integer> m, String latest, int n) {
        if (latest == null) return 0;
        YearMonth end = YearMonth.parse(latest);
        double sum = 0;
        for (int i = 0; i < n; i++) sum += m.getOrDefault(end.minusMonths(i).toString(), 0);
        return sum / n;
    }

    /** A Regional Manager may only look at their own regions; every other role may look at all. */
    private static void requireRegionAccess(CurrentUser user, String region) {
        boolean rmOnly = user.has("regional_manager") && !(user.has("forecaster") || user.has("planner") || user.has("admin"));
        if (rmOnly && !user.inRegion(region)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REGION_FORBIDDEN", "You may only view sales of your own region(s)");
        }
    }
}
