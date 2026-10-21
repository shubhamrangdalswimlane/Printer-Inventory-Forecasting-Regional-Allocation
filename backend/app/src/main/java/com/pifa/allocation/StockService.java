package com.pifa.allocation;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.notify.NotificationService;
import com.pifa.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock side of planning (PRD FR-PL-02, BR-AL-01): copies the active inventory version into the
 * cycle's {@code plan_product_stock} and lets the Planner hold back a reserve per model.
 * {@code allocatable = available - reserve} (never below 0) is computed by the database.
 */
@Service
public class StockService {

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationService notifications;
    private final com.pifa.events.LiveEvents events;

    public StockService(JdbcClient jdbc, AuditService audit, NotificationService notifications, com.pifa.events.LiveEvents events) {
        this.events = events;
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
    }

    /**
     * Applies an inventory version to a cycle.
     *
     * <p>Every product of the working copy or of the file gets a stock row; products missing from the
     * file get 0 available. Reserves the Planner already entered are kept. If a proposal already exists
     * while the cycle is in PLANNING, the proposal is marked stale and the Planner is told to re-run it
     * (PRD FR-PL-09). Must run inside the caller's transaction.
     *
     * @param cycleId   the cycle
     * @param versionId the inventory version just activated
     * @param actor     who triggered it (audit)
     */
    @Transactional
    public void applyInventory(UUID cycleId, UUID versionId, String actor) {
        int rows = jdbc.sql("""
                INSERT INTO plan_product_stock (cycle_id, product_id, inventory_version_id, available_qty)
                SELECT :c, p.id, :v, COALESCE(i.available_qty, 0)
                FROM product p LEFT JOIN inventory_line i ON i.product_id = p.id AND i.version_id = :v
                WHERE p.id IN (SELECT product_id FROM plan_line WHERE cycle_id = :c UNION SELECT product_id FROM inventory_line WHERE version_id = :v)
                ON CONFLICT (cycle_id, product_id) DO UPDATE
                    SET available_qty = EXCLUDED.available_qty, inventory_version_id = EXCLUDED.inventory_version_id,
                        row_version = plan_product_stock.row_version + 1
                """).param("c", cycleId).param("v", versionId).update();
        // Rows of products that are in neither the working copy nor the new file: nothing available any more.
        jdbc.sql("UPDATE plan_product_stock SET available_qty = 0, inventory_version_id = :v, row_version = row_version + 1 WHERE cycle_id = :c AND inventory_version_id IS DISTINCT FROM :v")
            .param("c", cycleId).param("v", versionId).update();

        boolean stale = jdbc.sql("UPDATE planning_cycle SET allocation_stale = TRUE WHERE id = :c AND state = 'PLANNING' AND proposal_at IS NOT NULL")
            .param("c", cycleId).update() > 0;
        if (stale) {
            notifications.notifyRole("planner", null, "Inventory changed - re-run allocation",
                "A new inventory version became active while planning. Preview the differences and re-run the allocation.", cycleId);
        }
        audit.record(actor, "STOCK_APPLIED", "planning_cycle", cycleId.toString(), Map.of("inventoryVersionId", versionId.toString(), "rows", rows, "proposalStale", stale));
    }

    /**
     * Sets the reserve of one product (Planner, cycle in PLANNING, reason mandatory; PRD 9.1).
     *
     * @param cycleId    the cycle
     * @param productId  the product
     * @param reserveQty units to hold back (0 or more; above available just means nothing is allocatable)
     * @param reason     why (mandatory, audited)
     * @param rowVersion the stock row version the client read (optimistic lock)
     * @param user       the caller
     * @return the new row version
     * @throws ApiException 400 bad value/no reason, 403 not Planner, 404 no stock row, 409 wrong state or stale row
     */
    @Transactional
    public long setReserve(UUID cycleId, UUID productId, int reserveQty, String reason, long rowVersion, CurrentUser user) {
        if (!user.has("planner")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the Planner may set reserves");
        if (reserveQty < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "QTY_INVALID", "Reserve cannot be negative");
        if (reason == null || reason.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason is required for a reserve");
        String state = jdbc.sql("SELECT state FROM planning_cycle WHERE id = :c FOR UPDATE").param("c", cycleId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Cycle not found"));
        if (!"PLANNING".equals(state)) throw new ApiException(HttpStatus.CONFLICT, "CYCLE_LOCKED", "Reserves can only be changed in PLANNING (now " + state + ")");
        Integer old = jdbc.sql("SELECT reserve_qty FROM plan_product_stock WHERE cycle_id = :c AND product_id = :p").param("c", cycleId).param("p", productId)
            .query(Integer.class).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No stock row for this product; is an inventory version active?"));
        int n = jdbc.sql("UPDATE plan_product_stock SET reserve_qty = :q, reserve_reason = :r, row_version = row_version + 1 WHERE cycle_id = :c AND product_id = :p AND row_version = :v")
            .param("q", reserveQty).param("r", reason.trim()).param("c", cycleId).param("p", productId).param("v", rowVersion).update();
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "The stock row was changed by someone else; refresh and retry.");
        jdbc.sql("UPDATE planning_cycle SET allocation_stale = TRUE WHERE id = :c AND proposal_at IS NOT NULL").param("c", cycleId).update();
        jdbc.sql("INSERT INTO change_log (cycle_id, product_id, region_code, field, old_value, new_value, reason, actor) VALUES (:c, :p, 'ALL', 'RESERVE', :o, :n, :r, :a)")
            .param("c", cycleId).param("p", productId).param("o", old).param("n", reserveQty).param("r", reason.trim()).param("a", user.username()).update();
        events.publish(cycleId, "ALLOCATION");
        audit.record(user.username(), "RESERVE_EDIT", "plan_product_stock", cycleId + "/" + productId, Map.of("old", old, "new", reserveQty, "reason", reason.trim()));
        return rowVersion + 1;
    }
}
