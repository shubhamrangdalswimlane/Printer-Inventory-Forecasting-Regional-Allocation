package com.pifa.workflow;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.config.PifaProperties;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetType;
import com.pifa.forecast.EditPolicy;
import com.pifa.forecast.ForecastValidationService;
import com.pifa.notify.NotificationService;
import com.pifa.rules.RulesFacade;
import com.pifa.rules.model.ForecastChange;
import com.pifa.rules.model.ForecastConfig;
import com.pifa.rules.model.PublishCheck;
import com.pifa.rules.model.Violation;
import com.pifa.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cycle state machine for the forecast stage (PRD section 7, architecture.md section 9).
 *
 * <pre>
 *  FORECAST_DRAFT --PUBLISH--> REGIONAL_REVIEW --(all regions SUBMIT_REGION | PROCEED)--> PLANNING
 *  REGIONAL_REVIEW --RECALL--> FORECAST_DRAFT        PLANNING --RETURN_REGIONS--> REGIONAL_REVIEW
 * </pre>
 *
 * <p>Every transition runs in <b>one transaction</b>: authorise by role/region, check the guard rules
 * (Drools {@code pifa.rules.workflow} / {@code forecast}), change the state, create the immutable
 * snapshot version, write a {@code workflow_event} and an audit entry, and queue notifications. The
 * cycle row is locked ({@code FOR UPDATE}) and carries a {@code row_version}, so two people acting at
 * once cannot both succeed.
 */
@Service
public class CycleWorkflowService {

    /**
     * A requested transition.
     *
     * @param action         PUBLISH, RECALL, SUBMIT_REGION, PROCEED or RETURN_REGIONS
     * @param region         region code for SUBMIT_REGION
     * @param regions        region codes for RETURN_REGIONS
     * @param comment        explanation; mandatory for RECALL (Admin), PROCEED and RETURN_REGIONS
     * @param rowVersion     optional: the cycle row version the client last saw; a mismatch gives 409
     * @param reviewDeadline optional: for PUBLISH, when regional review must be finished
     */
    public record TransitionRequest(String action, String region, List<String> regions, String comment, Long rowVersion, OffsetDateTime reviewDeadline) {}

    /** The cycle row. */
    public record CycleRow(UUID id, String month, String state, long rowVersion, OffsetDateTime reviewDeadline, OffsetDateTime finalizeDeadline) {}

    /** Progress of one region inside the cycle. */
    public record RegionStatus(String region, String reviewStatus, String finalizeStatus, String actor, OffsetDateTime updatedAt) {}

    /** A cycle with its per-region progress, for the status banner and the Admin/Forecaster trackers. */
    public record CycleDetail(CycleRow cycle, List<RegionStatus> regions) {}

    private final JdbcClient jdbc;
    private final DatasetRepository repo;
    private final AuditService audit;
    private final RulesFacade rules;
    private final ForecastValidationService validation;
    private final NotificationService notifications;
    private final PifaProperties props;

    public CycleWorkflowService(JdbcClient jdbc, DatasetRepository repo, AuditService audit, RulesFacade rules,
                                ForecastValidationService validation, NotificationService notifications, PifaProperties props) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.audit = audit;
        this.rules = rules;
        this.validation = validation;
        this.notifications = notifications;
        this.props = props;
    }

    // ---------------------------------------------------------------- reads

    /**
     * @param id cycle id
     * @return the cycle and the status of each active region
     * @throws ApiException 404 if the cycle does not exist
     */
    public CycleDetail detail(UUID id) {
        return new CycleDetail(find(id), regionStatuses(id));
    }

    /**
     * What the caller may do in this cycle right now (see {@link EditPolicy#permissions}).
     *
     * @param id   cycle id
     * @param user the caller
     * @return a map of capabilities for the UI
     */
    public Map<String, Object> permissions(UUID id, CurrentUser user) {
        CycleRow c = find(id);
        Map<String, String> reviews = new LinkedHashMap<>();
        regionStatuses(id).forEach(r -> reviews.put(r.region(), r.reviewStatus()));
        Map<String, Object> p = new LinkedHashMap<>(EditPolicy.permissions(user, c.state(), reviews));
        // The UI needs the change-control threshold to know when a reason field is required.
        p.put("changeThresholdPct", props.forecast().changeThresholdPct());
        return p;
    }

    // ---------------------------------------------------------------- transitions

    /**
     * Performs a transition.
     *
     * @param cycleId the cycle
     * @param req     the action and its parameters
     * @param user    the caller
     * @return the cycle after the transition
     * @throws ApiException 400 unknown action / missing parameter, 403 not allowed for this role or region,
     *                      409 wrong state or stale row version, 422 a guard rule blocks the action
     */
    @Transactional
    public CycleDetail transition(UUID cycleId, TransitionRequest req, CurrentUser user) {
        CycleRow cycle = lock(cycleId);
        if (req.rowVersion() != null && req.rowVersion() != cycle.rowVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "The cycle changed since you loaded it; refresh and retry.");
        }
        String action = req.action() == null ? "" : req.action().toUpperCase(Locale.ROOT);
        switch (action) {
            case "PUBLISH" -> publish(cycle, req, user);
            case "RECALL" -> recall(cycle, req, user);
            case "SUBMIT_REGION" -> submitRegion(cycle, req, user);
            case "PROCEED" -> proceed(cycle, req, user);
            case "RETURN_REGIONS" -> returnRegions(cycle, req, user);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ACTION", "Unknown action '" + req.action() + "'");
        }
        return detail(cycleId);
    }

    /** FORECAST_DRAFT -> REGIONAL_REVIEW: freeze the Forecaster's numbers and open the regions (BR-WF-01). */
    private void publish(CycleRow c, TransitionRequest req, CurrentUser user) {
        requireRole(user, "forecaster");
        requireState(c, "FORECAST_DRAFT");
        boolean hasActive = repo.activeVersionId(c.id(), DatasetType.FORECAST_UPLOAD).isPresent();
        long errors = validation.validate(c.id()).stream().filter(v -> v.getSeverity() == Violation.Severity.ERROR).count();
        guard(rules.checkPublish(new PublishCheck(hasActive, (int) errors)));

        UUID upload = repo.activeVersionId(c.id(), DatasetType.FORECAST_UPLOAD).orElseThrow();
        snapshot(c, DatasetType.FORECAST_PUBLISHED, "ALL", upload, "published_qty", null, "FCP", user);
        // The Regional Managers start from the published numbers; bumping row_version makes stale clients conflict.
        jdbc.sql("UPDATE plan_line SET revised_qty = published_qty, revision_reason = NULL, row_version = row_version + 1 WHERE cycle_id = :c").param("c", c.id()).update();
        for (String region : activeRegions()) {
            jdbc.sql("""
                    INSERT INTO cycle_region_status (cycle_id, region_code, review_status, actor) VALUES (:c, :r, 'OPEN', :a)
                    ON CONFLICT (cycle_id, region_code) DO UPDATE SET review_status = 'OPEN', actor = :a, updated_at = now()
                    """).param("c", c.id()).param("r", region).param("a", user.username()).update();
        }
        jdbc.sql("UPDATE planning_cycle SET review_deadline = :d WHERE id = :c").param("d", req.reviewDeadline()).param("c", c.id()).update();
        setState(c, "REGIONAL_REVIEW", "PUBLISH", null, user, req.comment());
        for (String region : activeRegions()) {
            notifications.notifyRole("regional_manager", region, "Forecast published for " + c.month(),
                "The forecast for " + c.month() + " is ready for your regional review." + deadlineText(req.reviewDeadline()), c.id());
        }
        notifications.notifyRole("planner", null, "Forecast published for " + c.month(), "Regional review has started.", c.id());
    }

    /** REGIONAL_REVIEW -> FORECAST_DRAFT: take the forecast back for correction. */
    private void recall(CycleRow c, TransitionRequest req, CurrentUser user) {
        requireState(c, "REGIONAL_REVIEW");
        boolean admin = user.has("admin");
        if (!admin && !user.has("forecaster")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the Forecaster or Admin may recall");
        boolean anySubmitted = regionStatuses(c.id()).stream().anyMatch(r -> "SUBMITTED".equals(r.reviewStatus()));
        if (!admin && anySubmitted) {
            throw new ApiException(HttpStatus.CONFLICT, "REGION_SUBMITTED", "A region has already submitted; only Admin can recall now");
        }
        if (admin) requireComment(req);
        jdbc.sql("DELETE FROM cycle_region_status WHERE cycle_id = :c").param("c", c.id()).update();
        jdbc.sql("UPDATE plan_line SET revised_qty = NULL, revision_reason = NULL, row_version = row_version + 1 WHERE cycle_id = :c").param("c", c.id()).update();
        setState(c, "FORECAST_DRAFT", "RECALL", null, user, req.comment());
        notifications.notifyRole("regional_manager", null, "Forecast recalled for " + c.month(), "The Forecaster recalled the forecast for correction.", c.id());
    }

    /** Region OPEN -> SUBMITTED; when the last region submits, the cycle moves to PLANNING. */
    private void submitRegion(CycleRow c, TransitionRequest req, CurrentUser user) {
        String region = req.region() == null ? "" : req.region().toUpperCase(Locale.ROOT);
        if (!activeRegions().contains(region)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REGION", "Unknown region '" + req.region() + "'");
        EditPolicy.require(user, c.state(), reviewStatus(c.id(), region), EditPolicy.Field.REVISED, region);

        // BR-VA-05 once more at submit time: every change above the threshold must carry a reason.
        List<ForecastChange> changes = new ArrayList<>();
        jdbc.sql("""
                SELECT p.product_name, l.published_qty, l.revised_qty, l.revision_reason FROM plan_line l JOIN product p ON p.id = l.product_id
                WHERE l.cycle_id = :c AND l.region_code = :r AND l.revised_qty IS NOT NULL AND l.revised_qty <> l.published_qty
                """).param("c", c.id()).param("r", region)
            .query((rs, n) -> changes.add(new ForecastChange(rs.getString(1), region, rs.getInt(2), rs.getInt(3), rs.getString(4)))).list();
        guard(rules.validateForecast(changes, new ForecastConfig(props.forecast().changeThresholdPct(), props.ingestion().spikeFactor(), props.ingestion().maxQty())));

        jdbc.sql("UPDATE cycle_region_status SET review_status = 'SUBMITTED', actor = :a, updated_at = now() WHERE cycle_id = :c AND region_code = :r")
            .param("a", user.username()).param("c", c.id()).param("r", region).update();
        UUID published = repo.latestVersionId(c.id(), DatasetType.FORECAST_PUBLISHED).orElse(null);
        snapshot(c, DatasetType.FORECAST_REVISED, region, published, "COALESCE(revised_qty, published_qty)", region, "FCR", user);
        eventOnly(c, "SUBMIT_REGION", region, user, null);
        notifications.notifyRole("planner", null, region + " submitted for " + c.month(), "Region " + region + " submitted its revised forecast.", c.id());
        notifications.notifyRole("forecaster", null, region + " submitted for " + c.month(), "Region " + region + " submitted its revised forecast.", c.id());

        boolean allIn = regionStatuses(c.id()).stream().allMatch(r -> "SUBMITTED".equals(r.reviewStatus()));
        if (allIn) {
            snapshot(c, DatasetType.FORECAST_REVISED, "ALL", published, "COALESCE(revised_qty, published_qty)", null, "FCR", user);
            setState(c, "PLANNING", "ALL_SUBMITTED", null, user, null);
            notifications.notifyRole("planner", null, "All regions submitted for " + c.month(), "The consolidated revised forecast is ready for planning.", c.id());
        }
    }

    /** REGIONAL_REVIEW -> PLANNING after the deadline, using published numbers for regions that did not submit (PRD FR-PL-07). */
    private void proceed(CycleRow c, TransitionRequest req, CurrentUser user) {
        requireRole(user, "planner");
        requireState(c, "REGIONAL_REVIEW");
        requireComment(req);
        if (c.reviewDeadline() == null || c.reviewDeadline().isAfter(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "DEADLINE_NOT_PASSED", "The review deadline has not passed (or none was set)");
        }
        UUID published = repo.latestVersionId(c.id(), DatasetType.FORECAST_PUBLISHED).orElse(null);
        // Regions that did not submit still hold revised = published (or their unsubmitted edits); the audit trail records this.
        snapshot(c, DatasetType.FORECAST_REVISED, "ALL", published, "COALESCE(revised_qty, published_qty)", null, "FCR", user);
        setState(c, "PLANNING", "PROCEED_AFTER_DEADLINE", null, user, req.comment());
    }

    /** PLANNING -> REGIONAL_REVIEW: send selected regions back for rework. */
    private void returnRegions(CycleRow c, TransitionRequest req, CurrentUser user) {
        requireRole(user, "planner");
        requireState(c, "PLANNING");
        requireComment(req);
        List<String> regions = req.regions() == null ? List.of() : req.regions().stream().map(r -> r.toUpperCase(Locale.ROOT)).toList();
        if (regions.isEmpty() || !activeRegions().containsAll(regions)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REGION", "Give at least one valid region to return");
        }
        for (String r : regions) {
            jdbc.sql("UPDATE cycle_region_status SET review_status = 'OPEN', actor = :a, updated_at = now() WHERE cycle_id = :c AND region_code = :r")
                .param("a", user.username()).param("c", c.id()).param("r", r).update();
        }
        setState(c, "REGIONAL_REVIEW", "RETURN_REGIONS", String.join(",", regions), user, req.comment());
        for (String r : regions) {
            notifications.notifyRole("regional_manager", r, "Region " + r + " returned for rework", req.comment(), c.id());
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Creates an immutable snapshot version from the working copy and returns nothing; see callers for the column to copy. */
    private void snapshot(CycleRow c, DatasetType type, String scope, UUID parent, String qtyExpression, String regionFilter, String prefix, CurrentUser user) {
        UUID id = UUID.randomUUID();
        int no = repo.nextVersionNo(c.id(), type);
        String where = regionFilter == null ? "" : " AND region_code = :r";
        var insert = jdbc.sql("INSERT INTO forecast_line (version_id, product_id, region_code, qty) SELECT :v, product_id, region_code, " + qtyExpression
            + " FROM plan_line WHERE cycle_id = :c" + where).param("v", id).param("c", c.id());
        if (regionFilter != null) insert = insert.param("r", regionFilter);
        // The version row must exist before its lines (foreign key), so it is inserted with a provisional row count first.
        repo.insertSnapshot(id, c.id(), type, no, prefix + "-" + c.month() + "-v" + no, scope, parent, 0, user.username());
        int rows = insert.update();
        jdbc.sql("UPDATE dataset_version SET row_count = :n WHERE id = :id").param("n", rows).param("id", id).update();
    }

    /** Changes the cycle state, bumps the row version, and records the event and the audit entry. */
    private void setState(CycleRow c, String to, String action, String region, CurrentUser user, String comment) {
        jdbc.sql("UPDATE planning_cycle SET state = :s, row_version = row_version + 1 WHERE id = :id").param("s", to).param("id", c.id()).update();
        jdbc.sql("INSERT INTO workflow_event (cycle_id, action, from_state, to_state, region_code, actor, comment) VALUES (:c,:a,:f,:t,:r,:u,:m)")
            .param("c", c.id()).param("a", action).param("f", c.state()).param("t", to).param("r", region).param("u", user.username()).param("m", comment).update();
        audit.record(user.username(), action, "planning_cycle", c.id().toString(), Map.of("from", c.state(), "to", to, "comment", String.valueOf(comment)));
    }

    /** Records an event that does not change the cycle state (a region submitting while others are still open). */
    private void eventOnly(CycleRow c, String action, String region, CurrentUser user, String comment) {
        jdbc.sql("INSERT INTO workflow_event (cycle_id, action, from_state, to_state, region_code, actor, comment) VALUES (:c,:a,:s,:s,:r,:u,:m)")
            .param("c", c.id()).param("a", action).param("s", c.state()).param("r", region).param("u", user.username()).param("m", comment).update();
        audit.record(user.username(), action, "planning_cycle", c.id().toString(), Map.of("region", String.valueOf(region)));
    }

    /** Throws 422 listing the messages if any guard finding is an ERROR. */
    private static void guard(List<Violation> found) {
        List<String> errors = found.stream().filter(v -> v.getSeverity() == Violation.Severity.ERROR).map(v -> v.getCode() + ": " + v.getMessage()).toList();
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GUARD_FAILED", String.join("; ", errors), Map.of("violations", errors));
        }
    }

    private static void requireRole(CurrentUser user, String role) {
        if (!user.has(role)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Requires role " + role);
    }

    private static void requireState(CycleRow c, String state) {
        if (!state.equals(c.state())) throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATE", "This action needs state " + state + " but the cycle is " + c.state());
    }

    private static void requireComment(TransitionRequest req) {
        if (req.comment() == null || req.comment().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "COMMENT_REQUIRED", "A comment is required for this action");
    }

    private static String deadlineText(OffsetDateTime d) { return d == null ? "" : " Please submit by " + d + "."; }

    private List<String> activeRegions() { return jdbc.sql("SELECT code FROM region WHERE active ORDER BY display_order").query(String.class).list(); }

    private String reviewStatus(UUID cycleId, String region) {
        return jdbc.sql("SELECT review_status FROM cycle_region_status WHERE cycle_id = :c AND region_code = :r").param("c", cycleId).param("r", region)
            .query(String.class).optional().orElse(null);
    }

    private List<RegionStatus> regionStatuses(UUID cycleId) {
        return jdbc.sql("""
                SELECT s.region_code, s.review_status, s.finalize_status, s.actor, s.updated_at
                FROM cycle_region_status s JOIN region r ON r.code = s.region_code WHERE s.cycle_id = :c ORDER BY r.display_order
                """).param("c", cycleId)
            .query((rs, n) -> new RegionStatus(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class))).list();
    }

    private CycleRow find(UUID id) {
        return jdbc.sql("SELECT id, month, state, row_version, review_deadline, finalize_deadline FROM planning_cycle WHERE id = :id").param("id", id)
            .query((rs, n) -> new CycleRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class))).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Cycle not found"));
    }

    /** Same as {@link #find} but locks the row until the transaction ends. */
    private CycleRow lock(UUID id) {
        return jdbc.sql("SELECT id, month, state, row_version, review_deadline, finalize_deadline FROM planning_cycle WHERE id = :id FOR UPDATE").param("id", id)
            .query((rs, n) -> new CycleRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class))).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Cycle not found"));
    }
}
