package com.pifa.forecast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reads the forecast working copy for the dashboards (Forecaster grid, Regional Manager review grid,
 * Planner read-only view). Everything is read-only here; edits go through {@link ForecastEditService}.
 */
@Service
public class ForecastQueryService {

    /**
     * One region's cell of a product row.
     *
     * @param lineId     the plan_line id (needed to edit the cell)
     * @param uploaded   quantity as received in the file
     * @param published  Forecaster working / published value
     * @param revised    Regional Manager value, or null before publish
     * @param reason     the manager's reason for the revision, or null
     * @param rowVersion optimistic-lock version to quote when editing
     */
    public record Cell(UUID lineId, int uploaded, int published, Integer revised, String reason, long rowVersion) {}

    /**
     * One product row: its cell for every region.
     *
     * @param productId product id
     * @param product   product name
     * @param regions   region code to cell, in region display order (NORTH, EAST, WEST, SOUTH)
     */
    public record ProductRow(UUID productId, String product, Map<String, Cell> regions) {}

    /**
     * A page of rows.
     *
     * @param total total number of matching products (for the pager)
     * @param rows  the rows of this page
     */
    public record GridPage(int total, List<ProductRow> rows) {}

    private final JdbcClient jdbc;

    public ForecastQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns one page of the forecast grid, ordered by product name.
     *
     * @param cycleId the cycle
     * @param search  case-insensitive part of the product name, or null/blank for all
     * @param page    zero-based page number
     * @param size    rows per page (clamped to 1..500)
     * @return the page; empty if the cycle has no working copy yet
     */
    public GridPage grid(UUID cycleId, String search, int page, int size) {
        int s = Math.min(Math.max(size, 1), 500);
        String like = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        int total = jdbc.sql("""
                SELECT COUNT(*) FROM product p WHERE EXISTS (SELECT 1 FROM plan_line l WHERE l.cycle_id = :c AND l.product_id = p.id)
                AND (CAST(:q AS TEXT) IS NULL OR lower(p.product_name) LIKE :q)
                """).param("c", cycleId).param("q", like).query(Integer.class).single();
        List<UUID> ids = new ArrayList<>();
        Map<UUID, ProductRow> rows = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT p.id, p.product_name FROM product p WHERE EXISTS (SELECT 1 FROM plan_line l WHERE l.cycle_id = :c AND l.product_id = p.id)
                AND (CAST(:q AS TEXT) IS NULL OR lower(p.product_name) LIKE :q)
                ORDER BY p.product_name LIMIT :s OFFSET :o
                """).param("c", cycleId).param("q", like).param("s", s).param("o", Math.max(page, 0) * s)
            .query((rs, n) -> {
                UUID id = rs.getObject(1, UUID.class);
                ids.add(id);
                rows.put(id, new ProductRow(id, rs.getString(2), new LinkedHashMap<>()));
                return null;
            }).list();
        if (!ids.isEmpty()) {
            jdbc.sql("""
                    SELECT l.product_id, l.region_code, l.id, l.uploaded_qty, l.published_qty, l.revised_qty, l.revision_reason, l.row_version
                    FROM plan_line l JOIN region r ON r.code = l.region_code
                    WHERE l.cycle_id = :c AND l.product_id IN (:ids) ORDER BY r.display_order
                    """).param("c", cycleId).param("ids", ids)
                .query((rs, n) -> {
                    rows.get(rs.getObject(1, UUID.class)).regions().put(rs.getString(2),
                        new Cell(rs.getObject(3, UUID.class), rs.getInt(4), rs.getInt(5), (Integer) rs.getObject(6), rs.getString(7), rs.getLong(8)));
                    return null;
                }).list();
        }
        return new GridPage(total, new ArrayList<>(rows.values()));
    }

    /**
     * The edit history of one cell, newest first (who changed what, when and why).
     *
     * @param lineId the plan_line id
     * @return history rows as maps with keys field, oldValue, newValue, reason, actor, at
     */
    public List<Map<String, Object>> history(UUID lineId) {
        return jdbc.sql("""
                SELECT g.field, g.old_value, g.new_value, g.reason, g.actor, g.at
                FROM change_log g JOIN plan_line l ON l.cycle_id = g.cycle_id AND l.product_id = g.product_id AND l.region_code = g.region_code
                WHERE l.id = :id ORDER BY g.id DESC
                """).param("id", lineId)
            .query((rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", rs.getString(1));
                m.put("oldValue", rs.getObject(2));
                m.put("newValue", rs.getObject(3));
                m.put("reason", rs.getString(4));
                m.put("actor", rs.getString(5));
                m.put("at", rs.getObject(6, java.time.OffsetDateTime.class));
                return m;
            }).list();
    }
}
