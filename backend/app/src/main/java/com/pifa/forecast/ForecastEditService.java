package com.pifa.forecast;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.config.PifaProperties;
import com.pifa.forecast.EditPolicy.Field;
import com.pifa.parameters.RuleParameterService;
import com.pifa.rules.RulesFacade;
import com.pifa.rules.model.ForecastChange;
import com.pifa.rules.model.ForecastConfig;
import com.pifa.rules.model.Violation;
import com.pifa.security.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies cell edits to the working copy with every control of the PRD: role/region/state policy,
 * value limits, change control (reason above the threshold, by Drools rule BR-VA-05), optimistic
 * locking and a change-log entry for each change.
 *
 * <p>A request is <b>all-or-nothing</b>: if any row is stale (someone else changed it), the whole
 * batch is rolled back and a 409 lists the current state of the conflicting rows, so nothing is
 * silently overwritten (PRD FR-COM-05).
 */
@Service
public class ForecastEditService {

    /**
     * One requested edit.
     *
     * @param lineId     id of the {@code plan_line} row
     * @param field      {@code PUBLISHED} (Forecaster) or {@code REVISED} (Regional Manager)
     * @param value      new quantity (whole number, 0 to the configured maximum)
     * @param reason     explanation; mandatory for REVISED changes above the threshold
     * @param rowVersion the row version the client last read; must still be current
     */
    public record EditRequest(UUID lineId, String field, Integer value, String reason, long rowVersion) {}

    /** The new state of an edited row, returned so the client can continue without re-reading. */
    public record EditedLine(UUID lineId, long rowVersion, int publishedQty, Integer revisedQty, String revisionReason) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final RulesFacade rules;
    private final PifaProperties props;
    private final RuleParameterService params;
    private final com.pifa.events.LiveEvents events;

    public ForecastEditService(JdbcClient jdbc, TransactionTemplate tx, AuditService audit, RulesFacade rules, PifaProperties props,
                               RuleParameterService params, com.pifa.events.LiveEvents events) {
        this.params = params;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.rules = rules;
        this.props = props;
    }

    /** A plan_line row joined with its product name and cycle state, as read for an edit. */
    private record Row(UUID id, UUID productId, String product, String region, int uploaded, int published, Integer revised,
                       String reason, long rowVersion, String cycleState, String regionReview, Integer proposed, Integer approved) {}

    /**
     * Applies a batch of edits to one cycle.
     *
     * @param cycleId the cycle
     * @param edits   the edits (an empty list does nothing)
     * @param user    the caller
     * @return the new state of every row that actually changed (rows whose value was already equal are skipped)
     * @throws ApiException 400 bad field/value or REASON_REQUIRED, 403 role or region, 404 unknown row,
     *                      409 CYCLE_LOCKED / REGION_SUBMITTED / CONFLICT (stale row version)
     */
    public List<EditedLine> apply(UUID cycleId, List<EditRequest> edits, CurrentUser user) {
        return tx.execute(status -> {
            List<EditedLine> out = new ArrayList<>();
            List<Map<String, Object>> conflicts = new ArrayList<>();
            long maxQty = props.ingestion().maxQty();
            var pv = params.current().values();
            ForecastConfig cfg = new ForecastConfig(pv.changeThresholdPct(), pv.spikeFactor(), maxQty);

            // Pass 1: authorise and validate every edit before changing anything.
            List<Object[]> plan = new ArrayList<>(); // {Row, EditRequest, Field}
            for (EditRequest e : edits) {
                Row row = load(cycleId, e.lineId());
                Field field = parseField(e.field());
                EditPolicy.require(user, row.cycleState(), row.regionReview(), field, row.region());
                if (e.value() == null || e.value() < 0 || e.value() > maxQty) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "QTY_INVALID", "Quantity must be a whole number between 0 and " + maxQty);
                }
                if (field == Field.REVISED) {
                    // Change control: the Drools rule BR-VA-05 decides whether a reason is mandatory.
                    List<Violation> v = rules.validateForecast(List.of(new ForecastChange(row.product(), row.region(), row.published(), e.value(), e.reason())), cfg);
                    v.stream().filter(x -> x.getSeverity() == Violation.Severity.ERROR).findFirst().ifPresent(x -> {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", x.getMessage());
                    });
                }
                if (field == Field.APPROVED) {
                    // BR-AL-07: the proposal is only a starting point, but every deviation from it is logged with a reason.
                    if (row.proposed() == null) throw new ApiException(HttpStatus.CONFLICT, "NO_PROPOSAL", "Generate the allocation proposal first");
                    if (!e.value().equals(row.proposed()) && (e.reason() == null || e.reason().isBlank())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                            row.product() + " / " + row.region() + ": a reason is required when changing the proposed " + row.proposed() + " to " + e.value());
                    }
                }
                plan.add(new Object[]{row, e, field});
            }

            // Pass 2: apply with optimistic locking.
            for (Object[] p : plan) {
                Row row = (Row) p[0];
                EditRequest e = (EditRequest) p[1];
                Field field = (Field) p[2];
                Integer oldValue = switch (field) {
                    case PUBLISHED -> row.published();
                    case REVISED -> row.revised();
                    case APPROVED -> row.approved();
                };
                if (e.rowVersion() == row.rowVersion() && e.value().equals(oldValue) && (field != Field.REVISED || sameReason(row.reason(), e.reason()))) {
                    continue; // nothing to change
                }
                int updated = switch (field) {
                    case PUBLISHED -> jdbc.sql("UPDATE plan_line SET published_qty = :v, row_version = row_version + 1 WHERE id = :id AND row_version = :rv")
                        .param("v", e.value()).param("id", row.id()).param("rv", e.rowVersion()).update();
                    case REVISED -> jdbc.sql("UPDATE plan_line SET revised_qty = :v, revision_reason = :r, row_version = row_version + 1 WHERE id = :id AND row_version = :rv")
                        .param("v", e.value()).param("r", blankToNull(e.reason())).param("id", row.id()).param("rv", e.rowVersion()).update();
                    case APPROVED -> jdbc.sql("UPDATE plan_line SET approved_alloc_qty = :v, row_version = row_version + 1 WHERE id = :id AND row_version = :rv")
                        .param("v", e.value()).param("id", row.id()).param("rv", e.rowVersion()).update();
                };
                if (updated == 0) {
                    Row now = load(cycleId, row.id());
                    conflicts.add(Map.of("lineId", now.id(), "product", now.product(), "region", now.region(), "rowVersion", now.rowVersion(),
                        "publishedQty", now.published(), "revisedQty", now.revised() == null ? "" : now.revised()));
                    continue;
                }
                jdbc.sql("INSERT INTO change_log (cycle_id, product_id, region_code, field, old_value, new_value, reason, actor) VALUES (:c,:p,:r,:f,:o,:n,:why,:a)")
                    .param("c", cycleId).param("p", row.productId()).param("r", row.region()).param("f", field.name())
                    .param("o", oldValue).param("n", e.value()).param("why", blankToNull(e.reason())).param("a", user.username()).update();
                Row after = load(cycleId, row.id());
                out.add(new EditedLine(after.id(), after.rowVersion(), after.published(), after.revised(), after.reason()));
            }
            if (!conflicts.isEmpty()) {
                status.setRollbackOnly(); // undo this batch completely
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Some rows were changed by someone else; nothing was saved. Refresh and retry.",
                    Map.of("conflicts", conflicts));
            }
            if (!out.isEmpty()) {
                boolean allocation = plan.stream().anyMatch(x -> x[2] == Field.APPROVED);
                audit.record(user.username(), allocation ? "ALLOCATION_EDIT" : "FORECAST_EDIT", "planning_cycle", cycleId.toString(), Map.of("cells", out.size()));
                events.publish(cycleId, "EDIT");
            }
            return out;
        });
    }

    /** Loads one plan_line with the cycle state and its region's review status; 404 if it does not belong to the cycle. */
    private Row load(UUID cycleId, UUID lineId) {
        return jdbc.sql("""
                SELECT l.id, l.product_id, p.product_name, l.region_code, l.uploaded_qty, l.published_qty, l.revised_qty,
                       l.revision_reason, l.row_version, c.state, s.review_status, l.proposed_alloc_qty, l.approved_alloc_qty
                FROM plan_line l JOIN product p ON p.id = l.product_id JOIN planning_cycle c ON c.id = l.cycle_id
                LEFT JOIN cycle_region_status s ON s.cycle_id = l.cycle_id AND s.region_code = l.region_code
                WHERE l.id = :id AND l.cycle_id = :c
                """).param("id", lineId).param("c", cycleId)
            .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getInt(6), (Integer) rs.getObject(7), rs.getString(8), rs.getLong(9), rs.getString(10), rs.getString(11),
                (Integer) rs.getObject(12), (Integer) rs.getObject(13)))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Forecast line not found in this cycle"));
    }

    private static Field parseField(String f) {
        try {
            return Field.valueOf(f == null ? "" : f.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_FIELD", "field must be PUBLISHED, REVISED or APPROVED");
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static boolean sameReason(String a, String b) { return java.util.Objects.equals(blankToNull(a), blankToNull(b)); }
}
