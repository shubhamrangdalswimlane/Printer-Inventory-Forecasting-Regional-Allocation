package com.pifa.allocation;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.events.LiveEvents;
import com.pifa.notify.NotificationService;
import com.pifa.parameters.RuleParameterService;
import com.pifa.rules.RulesFacade;
import com.pifa.rules.model.TransferCheck;
import com.pifa.rules.model.Violation;
import com.pifa.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers between regions during REGIONAL_FINALIZATION (PRD FR-RM-07, FR-RM-10, FR-PL-08, BR-TR-01..04).
 *
 * <p>A transfer moves N units of one product from a donor region to a receiving region; the product's
 * total never changes. Who decides:
 * <ul>
 *   <li>REQUEST (the receiver asks): the donor region's manager accepts or rejects;</li>
 *   <li>OFFER (the donor offers): the receiving region's manager accepts or rejects;</li>
 *   <li>DIRECT (one manager owns both regions): applied immediately;</li>
 *   <li>the Planner may decide any pending transfer on a region's behalf (escalation).</li>
 * </ul>
 * The Drools transfer rules are checked when the transfer is created <em>and again</em> when it is
 * accepted, because other transfers may have changed the numbers in between. Applying a transfer locks
 * both rows in a fixed order (region display order) so two transfers can never deadlock.
 */
@Service
public class TransferService {

    /** One transfer as listed in the inbox / monitor. */
    public record TransferRow(UUID id, UUID productId, String product, String fromRegion, String toRegion, int qty, String kind,
                              String status, String reason, String requestedBy, OffsetDateTime requestedAt, String decidedBy,
                              OffsetDateTime decidedAt, String decisionComment) {}

    /** Result of creating or deciding a transfer, with any rule warnings. */
    public record Outcome(TransferRow transfer, List<String> warnings) {}

    private final JdbcClient jdbc;
    private final RulesFacade rules;
    private final RuleParameterService params;
    private final AuditService audit;
    private final NotificationService notifications;
    private final LiveEvents events;
    private final com.pifa.ops.PifaMetrics metrics;

    public TransferService(JdbcClient jdbc, RulesFacade rules, RuleParameterService params, AuditService audit,
                           NotificationService notifications, LiveEvents events, com.pifa.ops.PifaMetrics metrics) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.rules = rules;
        this.params = params;
        this.audit = audit;
        this.notifications = notifications;
        this.events = events;
    }

    /**
     * Creates a transfer.
     *
     * @param cycleId    the cycle (must be in REGIONAL_FINALIZATION)
     * @param productId  the product
     * @param fromRegion donor region
     * @param toRegion   receiving region
     * @param qty        units to move
     * @param reason     why (optional but recommended; shown to the other manager and audited)
     * @param user       the caller: must manage the donor or the receiver (or both, for a direct rebalance)
     * @return the transfer (status REQUESTED, or ACCEPTED for a direct rebalance) and any warnings
     * @throws ApiException 403 not a manager of either region, 409 wrong state, 422 refused by the transfer rules
     */
    @Transactional
    public Outcome create(UUID cycleId, UUID productId, String fromRegion, String toRegion, int qty, String reason, CurrentUser user) {
        String from = fromRegion.toUpperCase(Locale.ROOT), to = toRegion.toUpperCase(Locale.ROOT);
        requireState(cycleId);
        boolean ownsFrom = user.has("regional_manager") && user.inRegion(from);
        boolean ownsTo = user.has("regional_manager") && user.inRegion(to);
        if (!ownsFrom && !ownsTo) throw new ApiException(HttpStatus.FORBIDDEN, "REGION_FORBIDDEN", "You must manage the donor or the receiving region");
        String kind = ownsFrom && ownsTo ? "DIRECT" : ownsTo ? "REQUEST" : "OFFER";

        List<Violation> found = check(cycleId, productId, from, to, qty);
        refuseOnError(found);

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO transfer_request (id, cycle_id, product_id, from_region, to_region, qty, kind, status, reason, requested_by)
                VALUES (:id, :c, :p, :f, :t, :q, :k, 'REQUESTED', :r, :u)
                """).param("id", id).param("c", cycleId).param("p", productId).param("f", from).param("t", to).param("q", qty)
            .param("k", kind).param("r", blank(reason)).param("u", user.username()).update();
        audit.record(user.username(), "TRANSFER_" + kind, "transfer_request", id.toString(), Map.of("from", from, "to", to, "qty", qty, "productId", productId.toString()));

        if ("DIRECT".equals(kind)) {
            apply(cycleId, id, user, "direct rebalance between own regions");
        } else {
            // Tell the region that has to decide.
            String decider = "REQUEST".equals(kind) ? from : to;
            notifications.notifyRole("regional_manager", decider, "Transfer " + ("REQUEST".equals(kind) ? "request" : "offer") + " for " + productName(productId),
                to + " " + ("REQUEST".equals(kind) ? "asks " + from + " for " : "is offered by " + from + " ") + qty + " units." + (reason == null ? "" : " Reason: " + reason), cycleId);
        }
        events.publish(cycleId, "TRANSFER");
        return new Outcome(get(id), warnings(found));
    }

    /**
     * Accepts a pending transfer and applies it (the rules are re-checked against the current numbers).
     *
     * @param transferId the transfer
     * @param comment    optional comment
     * @param user       the deciding region's manager, or the Planner
     * @return the transfer (ACCEPTED) and any warnings
     * @throws ApiException 403 not allowed to decide, 409 not pending / wrong state, 422 refused by the rules now
     */
    @Transactional
    public Outcome accept(UUID transferId, String comment, CurrentUser user) {
        TransferRow t = lockPending(transferId);
        UUID cycleId = cycleOf(transferId);
        requireState(cycleId);
        requireDecider(t, user);
        List<Violation> found = apply(cycleId, transferId, user, comment);
        notifyInitiator(t, cycleId, "accepted", user, comment);
        events.publish(cycleId, "TRANSFER");
        return new Outcome(get(transferId), warnings(found));
    }

    /**
     * Rejects a pending transfer; nothing moves.
     *
     * @param transferId the transfer
     * @param comment    why (recommended)
     * @param user       the deciding region's manager, or the Planner
     * @return the transfer (REJECTED)
     */
    @Transactional
    public Outcome reject(UUID transferId, String comment, CurrentUser user) {
        TransferRow t = lockPending(transferId);
        UUID cycleId = cycleOf(transferId);
        requireDecider(t, user);
        decide(transferId, "REJECTED", user, comment);
        notifyInitiator(t, cycleId, "rejected", user, comment);
        events.publish(cycleId, "TRANSFER");
        return new Outcome(get(transferId), List.of());
    }

    /**
     * Withdraws a pending transfer (only the person who created it, or the Planner).
     *
     * @param transferId the transfer
     * @param user       the caller
     * @return the transfer (CANCELLED)
     */
    @Transactional
    public Outcome cancel(UUID transferId, CurrentUser user) {
        TransferRow t = lockPending(transferId);
        if (!user.username().equals(t.requestedBy()) && !user.has("planner")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the creator or the Planner can cancel this transfer");
        }
        decide(transferId, "CANCELLED", user, null);
        events.publish(cycleOf(transferId), "TRANSFER");
        return new Outcome(get(transferId), List.of());
    }

    /**
     * Cancels every pending transfer of a cycle (used when the Planner reopens the allocation).
     *
     * @param cycleId the cycle
     * @param user    who triggered it
     * @param comment why
     * @return number of transfers cancelled
     */
    @Transactional
    public int cancelAllPending(UUID cycleId, CurrentUser user, String comment) {
        return jdbc.sql("UPDATE transfer_request SET status = 'CANCELLED', decided_by = :u, decided_at = now(), decision_comment = :m WHERE cycle_id = :c AND status = 'REQUESTED'")
            .param("u", user.username()).param("m", comment).param("c", cycleId).update();
    }

    /**
     * @param cycleId the cycle
     * @param status  REQUESTED, ACCEPTED, REJECTED, CANCELLED, or null for all
     * @return transfers, newest first
     */
    public List<TransferRow> list(UUID cycleId, String status) {
        return jdbc.sql(SELECT + " WHERE t.cycle_id = :c AND (CAST(:s AS TEXT) IS NULL OR t.status = :s) ORDER BY t.requested_at DESC")
            .param("c", cycleId).param("s", status == null ? null : status.toUpperCase(Locale.ROOT)).query(TransferService::map).list();
    }

    /** @return pending transfers in which the region is donor or receiver */
    public int pendingFor(UUID cycleId, String region) {
        return jdbc.sql("SELECT COUNT(*) FROM transfer_request WHERE cycle_id = :c AND status = 'REQUESTED' AND (from_region = :r OR to_region = :r)")
            .param("c", cycleId).param("r", region).query(Integer.class).single();
    }

    /** @return pending transfers of the whole cycle */
    public int pendingInCycle(UUID cycleId) {
        return jdbc.sql("SELECT COUNT(*) FROM transfer_request WHERE cycle_id = :c AND status = 'REQUESTED'").param("c", cycleId).query(Integer.class).single();
    }

    // ---------------------------------------------------------------- internals

    /**
     * Moves the units: locks both final-allocation rows in a fixed order, re-checks the rules against the
     * current numbers, updates both rows, logs both changes, and marks the transfer ACCEPTED.
     *
     * @return the rule findings (warnings only; errors throw)
     */
    private List<Violation> apply(UUID cycleId, UUID transferId, CurrentUser user, String comment) {
        TransferRow t = get(transferId);
        // Fixed lock order (region display order) prevents deadlocks between opposite transfers.
        jdbc.sql("SELECT l.id FROM plan_line l JOIN region r ON r.code = l.region_code WHERE l.cycle_id = :c AND l.product_id = :p "
                + "AND l.region_code IN (:f, :t) ORDER BY r.display_order FOR UPDATE OF l")
            .param("c", cycleId).param("p", t.productId()).param("f", t.fromRegion()).param("t", t.toRegion()).query(UUID.class).list();
        List<Violation> found = check(cycleId, t.productId(), t.fromRegion(), t.toRegion(), t.qty());
        refuseOnError(found);
        int donorBefore = finalQty(cycleId, t.productId(), t.fromRegion());
        int receiverBefore = finalQty(cycleId, t.productId(), t.toRegion());
        int totalBefore = productTotal(cycleId, t.productId());
        jdbc.sql("UPDATE plan_line SET final_alloc_qty = final_alloc_qty - :q, row_version = row_version + 1 WHERE cycle_id = :c AND product_id = :p AND region_code = :r")
            .param("q", t.qty()).param("c", cycleId).param("p", t.productId()).param("r", t.fromRegion()).update();
        jdbc.sql("UPDATE plan_line SET final_alloc_qty = final_alloc_qty + :q, row_version = row_version + 1 WHERE cycle_id = :c AND product_id = :p AND region_code = :r")
            .param("q", t.qty()).param("c", cycleId).param("p", t.productId()).param("r", t.toRegion()).update();
        // BR-TR-02 safety net on the real data: if the product total changed, the whole transaction rolls back.
        int totalAfter = productTotal(cycleId, t.productId());
        if (totalAfter != totalBefore) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSFER_REFUSED", "BR-TR-02: model total would change from " + totalBefore + " to " + totalAfter);
        }
        String why = "transfer " + transferId + (t.reason() == null ? "" : ": " + t.reason());
        log(cycleId, t.productId(), t.fromRegion(), donorBefore, donorBefore - t.qty(), why, user);
        log(cycleId, t.productId(), t.toRegion(), receiverBefore, receiverBefore + t.qty(), why, user);
        decide(transferId, "ACCEPTED", user, comment);
        return found;
    }

    /** Builds the {@link TransferCheck} from the current numbers and runs the Drools transfer rules. */
    private List<Violation> check(UUID cycleId, UUID productId, String from, String to, int qty) {
        int donor = finalQty(cycleId, productId, from);
        int receiver = finalQty(cycleId, productId, to);
        int receiverDemand = jdbc.sql("SELECT COALESCE(revised_qty, published_qty) FROM plan_line WHERE cycle_id = :c AND product_id = :p AND region_code = :r")
            .param("c", cycleId).param("p", productId).param("r", to).query(Integer.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Product or region not in this cycle"));
        int total = jdbc.sql("SELECT COALESCE(SUM(final_alloc_qty), 0) FROM plan_line WHERE cycle_id = :c AND product_id = :p")
            .param("c", cycleId).param("p", productId).query(Integer.class).single();
        // The model total after the move: every other region unchanged, the donor loses qty, the receiver gains qty.
        int others = total - donor - receiver;
        int after = others + (donor - qty) + (receiver + qty);
        TransferCheck tc = new TransferCheck(productId.toString(), productName(productId), from, to, qty, donor, receiver, receiverDemand,
            params.current().values().minRegionFloor(), finalized(cycleId, from), finalized(cycleId, to), total, after);
        return rules.validateTransfer(tc);
    }

    private static void refuseOnError(List<Violation> found) {
        List<String> errors = found.stream().filter(v -> v.getSeverity() == Violation.Severity.ERROR).map(v -> v.getCode() + ": " + v.getMessage()).toList();
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSFER_REFUSED", String.join("; ", errors), Map.of("violations", errors));
    }

    private static List<String> warnings(List<Violation> found) {
        return found.stream().filter(v -> v.getSeverity() == Violation.Severity.WARNING).map(v -> v.getCode() + ": " + v.getMessage()).toList();
    }

    /** The deciding party: the donor for a REQUEST, the receiver for an OFFER; the Planner always. */
    private static void requireDecider(TransferRow t, CurrentUser user) {
        if (user.has("planner")) return;
        String decider = "REQUEST".equals(t.kind()) ? t.fromRegion() : t.toRegion();
        if (!(user.has("regional_manager") && user.inRegion(decider))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the manager of " + decider + " (or the Planner) can decide this transfer");
        }
    }

    private void decide(UUID id, String status, CurrentUser user, String comment) {
        jdbc.sql("UPDATE transfer_request SET status = :s, decided_by = :u, decided_at = now(), decision_comment = :m WHERE id = :id")
            .param("s", status).param("u", user.username()).param("m", blank(comment)).param("id", id).update();
        audit.record(user.username(), "TRANSFER_" + status, "transfer_request", id.toString(), Map.of("comment", String.valueOf(comment)));
        metrics.transfer(status);
    }

    private void notifyInitiator(TransferRow t, UUID cycleId, String verb, CurrentUser user, String comment) {
        String initiatorRegion = "REQUEST".equals(t.kind()) ? t.toRegion() : t.fromRegion();
        notifications.notifyRole("regional_manager", initiatorRegion, "Transfer " + verb + ": " + t.product(),
            t.qty() + " units " + t.fromRegion() + " -> " + t.toRegion() + " were " + verb + " by " + user.username() + (comment == null || comment.isBlank() ? "" : " (" + comment + ")"), cycleId);
    }

    private void log(UUID cycleId, UUID productId, String region, int oldV, int newV, String reason, CurrentUser user) {
        jdbc.sql("INSERT INTO change_log (cycle_id, product_id, region_code, field, old_value, new_value, reason, actor) VALUES (:c, :p, :r, 'FINAL', :o, :n, :why, :a)")
            .param("c", cycleId).param("p", productId).param("r", region).param("o", oldV).param("n", newV).param("why", reason).param("a", user.username()).update();
    }

    private TransferRow lockPending(UUID id) {
        String status = jdbc.sql("SELECT status FROM transfer_request WHERE id = :id FOR UPDATE").param("id", id).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Transfer not found"));
        if (!"REQUESTED".equals(status)) throw new ApiException(HttpStatus.CONFLICT, "NOT_PENDING", "The transfer is already " + status);
        return get(id);
    }

    private void requireState(UUID cycleId) {
        String s = jdbc.sql("SELECT state FROM planning_cycle WHERE id = :c").param("c", cycleId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Cycle not found"));
        if (!"REGIONAL_FINALIZATION".equals(s)) throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATE", "Transfers are only possible in REGIONAL_FINALIZATION (now " + s + ")");
    }

    private int finalQty(UUID cycleId, UUID productId, String region) {
        return jdbc.sql("SELECT COALESCE(final_alloc_qty, 0) FROM plan_line WHERE cycle_id = :c AND product_id = :p AND region_code = :r")
            .param("c", cycleId).param("p", productId).param("r", region).query(Integer.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Product or region not in this cycle"));
    }

    private int productTotal(UUID cycleId, UUID productId) {
        return jdbc.sql("SELECT COALESCE(SUM(final_alloc_qty), 0) FROM plan_line WHERE cycle_id = :c AND product_id = :p")
            .param("c", cycleId).param("p", productId).query(Integer.class).single();
    }

    private boolean finalized(UUID cycleId, String region) {
        return jdbc.sql("SELECT finalize_status = 'FINALIZED' FROM cycle_region_status WHERE cycle_id = :c AND region_code = :r")
            .param("c", cycleId).param("r", region).query(Boolean.class).optional().orElse(false);
    }

    private String productName(UUID productId) {
        return jdbc.sql("SELECT product_name FROM product WHERE id = :p").param("p", productId).query(String.class).optional().orElse(productId.toString());
    }

    private UUID cycleOf(UUID transferId) {
        return jdbc.sql("SELECT cycle_id FROM transfer_request WHERE id = :id").param("id", transferId).query(UUID.class).single();
    }

    private TransferRow get(UUID id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query(TransferService::map).single();
    }

    private static final String SELECT = """
        SELECT t.id, t.product_id, p.product_name, t.from_region, t.to_region, t.qty, t.kind, t.status, t.reason, t.requested_by,
               t.requested_at, t.decided_by, t.decided_at, t.decision_comment
        FROM transfer_request t JOIN product p ON p.id = t.product_id
        """;

    private static TransferRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TransferRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, OffsetDateTime.class),
            rs.getString(12), rs.getObject(13, OffsetDateTime.class), rs.getString(14));
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
