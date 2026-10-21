package com.pifa.admin;

import com.pifa.events.LiveEvents;
import com.pifa.workflow.CycleWorkflowService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read-only numbers for the Admin dashboard (PRD FR-AD-01 overview, FR-AD-02 analytics, FR-AD-04 health).
 * Everything here is computed from the same tables the workflow writes, so it is always current.
 */
@Service
public class AdminInsightService {

    private final JdbcClient jdbc;
    private final CycleWorkflowService workflow;
    private final LiveEvents events;

    public AdminInsightService(JdbcClient jdbc, CycleWorkflowService workflow, LiveEvents events) {
        this.jdbc = jdbc;
        this.workflow = workflow;
        this.events = events;
    }

    /**
     * Overview of one cycle: state, per-region progress, deadlines and open issues, plus system health.
     *
     * @param cycleId the cycle
     * @return a map: {@code cycle} (detail), {@code issues} (counts and lists), {@code system} (health rows)
     */
    public Map<String, Object> overview(UUID cycleId) {
        var detail = workflow.detail(cycleId);
        var c = detail.cycle();
        Map<String, Object> issues = new LinkedHashMap<>();
        issues.put("rejectedFilesLast7Days", jdbc.sql("SELECT COUNT(*) FROM ingestion_job WHERE status = 'REJECTED' AND started_at > now() - interval '7 days'").query(Long.class).single());
        issues.put("pendingTransfers", jdbc.sql("SELECT COUNT(*) FROM transfer_request WHERE cycle_id = :c AND status = 'REQUESTED'").param("c", cycleId).query(Long.class).single());
        issues.put("regionsNotSubmitted", "REGIONAL_REVIEW".equals(c.state())
            ? detail.regions().stream().filter(r -> !"SUBMITTED".equals(r.reviewStatus())).map(CycleWorkflowService.RegionStatus::region).toList() : List.of());
        issues.put("regionsNotFinalized", "REGIONAL_FINALIZATION".equals(c.state())
            ? detail.regions().stream().filter(r -> !"FINALIZED".equals(r.finalizeStatus())).map(CycleWorkflowService.RegionStatus::region).toList() : List.of());
        issues.put("allocationStale", jdbc.sql("SELECT allocation_stale FROM planning_cycle WHERE id = :c").param("c", cycleId).query(Boolean.class).single());
        // An inventory file that arrived after approval is stored but not applied (PRD 8.5): flag it.
        issues.put("inventoryChangedAfterApproval", jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM dataset_version i, dataset_version a
                    WHERE i.cycle_id = :c AND a.cycle_id = :c AND i.type = 'INVENTORY_UPLOAD' AND a.type = 'ALLOCATION_APPROVED'
                      AND i.status = 'VALID' AND i.created_at > a.created_at)
                """).param("c", cycleId).query(Boolean.class).single());
        OffsetDateTime now = OffsetDateTime.now();
        issues.put("reviewOverdue", "REGIONAL_REVIEW".equals(c.state()) && c.reviewDeadline() != null && c.reviewDeadline().isBefore(now));
        issues.put("finalizeOverdue", "REGIONAL_FINALIZATION".equals(c.state()) && c.finalizeDeadline() != null && c.finalizeDeadline().isBefore(now));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cycle", detail);
        out.put("issues", issues);
        out.put("system", systemStatus());
        return out;
    }

    /** @return health rows (last SFTP poll, last audit verification ...) plus live connections on this instance */
    public Map<String, Object> systemStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value, ok, updated_at FROM system_status ORDER BY key").query((rs, n) -> {
            m.put(rs.getString(1), Map.of("value", String.valueOf(rs.getString(2)), "ok", rs.getObject(3) == null ? "unknown" : rs.getBoolean(3),
                "updatedAt", rs.getObject(4, OffsetDateTime.class).toString()));
            return null;
        }).list();
        m.put("liveConnections", events.connections());
        return m;
    }

    /**
     * Analytics across cycles (PRD FR-AD-02).
     *
     * @param fromMonth first cycle month (YYYY-MM), inclusive; null for no lower bound
     * @param toMonth   last cycle month, inclusive; null for no upper bound
     * @return one entry per cycle (oldest first) with: totals per stage and region, constrained count and
     *         share, fill rate per region, transfer flows, cycle time per stage (hours), and forecast accuracy
     *         against actual sales where actuals exist
     */
    public List<Map<String, Object>> analytics(String fromMonth, String toMonth) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object[]> cycles = jdbc.sql("SELECT id, month, state, created_at FROM planning_cycle WHERE (CAST(:f AS TEXT) IS NULL OR month >= :f) "
                + "AND (CAST(:t AS TEXT) IS NULL OR month <= :t) ORDER BY month")
            .param("f", fromMonth).param("t", toMonth)
            .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class)}).list();
        for (Object[] row : cycles) {
            UUID id = (UUID) row[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cycleId", id);
            m.put("month", row[1]);
            m.put("state", row[2]);

            // Totals per region and stage, and fill rate = final (or approved) / revised demand.
            Map<String, Map<String, Object>> regions = new LinkedHashMap<>();
            jdbc.sql("""
                    SELECT l.region_code, SUM(l.published_qty), SUM(COALESCE(l.revised_qty, l.published_qty)), SUM(l.approved_alloc_qty), SUM(l.final_alloc_qty)
                    FROM plan_line l JOIN region r ON r.code = l.region_code WHERE l.cycle_id = :c GROUP BY l.region_code, r.display_order ORDER BY r.display_order
                    """).param("c", id).query((rs, n) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        long revised = rs.getLong(3);
                        Long approved = (Long) rs.getObject(4), fin = (Long) rs.getObject(5);
                        r.put("published", rs.getLong(2));
                        r.put("revised", revised);
                        r.put("approved", approved);
                        r.put("final", fin);
                        Long allocated = fin != null ? fin : approved;
                        r.put("fillRatePct", allocated == null || revised == 0 ? null : Math.round(1000.0 * allocated / revised) / 10.0);
                        regions.put(rs.getString(1), r);
                        return null;
                    }).list();
            m.put("regions", regions);

            long products = jdbc.sql("SELECT COUNT(*) FROM plan_product_stock WHERE cycle_id = :c").param("c", id).query(Long.class).single();
            long constrained = jdbc.sql("SELECT COUNT(*) FROM plan_product_stock WHERE cycle_id = :c AND classification = 'CONSTRAINED'").param("c", id).query(Long.class).single();
            m.put("constrainedProducts", constrained);
            m.put("constrainedSharePct", products == 0 ? null : Math.round(1000.0 * constrained / products) / 10.0);

            m.put("transferFlows", jdbc.sql("SELECT from_region, to_region, SUM(qty), COUNT(*) FROM transfer_request WHERE cycle_id = :c AND status = 'ACCEPTED' GROUP BY 1, 2 ORDER BY 3 DESC")
                .param("c", id).query((rs, n) -> Map.of("from", rs.getString(1), "to", rs.getString(2), "units", rs.getLong(3), "transfers", rs.getLong(4))).list());

            m.put("stageHours", stageHours(id, (OffsetDateTime) row[3]));
            m.put("accuracy", accuracy(id, (String) row[1]));
            out.add(m);
        }
        return out;
    }

    /**
     * Hours spent in each stage, from the workflow events: draft (creation to publish), regional review
     * (publish to planning), planning (to approval), finalization (to FINAL). Null where the stage has not ended.
     */
    private Map<String, Object> stageHours(UUID cycleId, OffsetDateTime created) {
        Map<String, OffsetDateTime> first = new LinkedHashMap<>();
        jdbc.sql("SELECT action, MIN(at) FROM workflow_event WHERE cycle_id = :c GROUP BY action").param("c", cycleId)
            .query((rs, n) -> first.put(rs.getString(1), rs.getObject(2, OffsetDateTime.class))).list();
        OffsetDateTime published = first.get("PUBLISH");
        OffsetDateTime planning = earliest(first.get("ALL_SUBMITTED"), first.get("PROCEED_AFTER_DEADLINE"));
        OffsetDateTime approved = first.get("APPROVE_ALLOCATION");
        OffsetDateTime fin = first.get("ALL_FINALIZED");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("draft", hours(created, published));
        m.put("regionalReview", hours(published, planning));
        m.put("planning", hours(planning, approved));
        m.put("finalization", hours(approved, fin));
        m.put("total", hours(created, fin));
        return m;
    }

    /**
     * Forecast accuracy against actual sales of the cycle month (where the sales history contains it), as
     * weighted absolute percentage error per stage: sum |forecast - actual| / sum actual. Lower is better.
     *
     * @return {@code available=false} when there are no actuals for that month yet
     */
    private Map<String, Object> accuracy(UUID cycleId, String month) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object[] r = jdbc.sql("""
                SELECT SUM(s.qty), SUM(ABS(l.published_qty - s.qty)), SUM(ABS(COALESCE(l.revised_qty, l.published_qty) - s.qty)),
                       SUM(ABS(COALESCE(l.final_alloc_qty, l.approved_alloc_qty, 0) - s.qty)), COUNT(*)
                FROM plan_line l JOIN sales_history s ON s.product_id = l.product_id AND s.region_code = l.region_code AND s.month = :m
                WHERE l.cycle_id = :c
                """).param("m", month).param("c", cycleId)
            .query((rs, n) -> new Object[]{rs.getObject(1), rs.getObject(2), rs.getObject(3), rs.getObject(4), rs.getLong(5)}).single();
        long cells = (Long) r[4];
        if (cells == 0 || r[0] == null || ((Number) r[0]).longValue() == 0) {
            m.put("available", false);
            return m;
        }
        double actual = ((Number) r[0]).doubleValue();
        m.put("available", true);
        m.put("actualUnits", (long) actual);
        m.put("wapePublishedPct", Math.round(1000.0 * ((Number) r[1]).doubleValue() / actual) / 10.0);
        m.put("wapeRevisedPct", Math.round(1000.0 * ((Number) r[2]).doubleValue() / actual) / 10.0);
        m.put("wapeAllocationPct", Math.round(1000.0 * ((Number) r[3]).doubleValue() / actual) / 10.0);
        m.put("source", jdbc.sql("SELECT string_agg(DISTINCT source, ',') FROM sales_history WHERE month = :m").param("m", month).query(String.class).single());
        return m;
    }

    private static OffsetDateTime earliest(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static Double hours(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) return null;
        return Math.round(Duration.between(from, to).toMinutes() / 6.0) / 10.0;
    }
}
