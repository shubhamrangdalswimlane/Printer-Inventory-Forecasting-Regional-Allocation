package com.pifa.admin;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cycle management for Admin (PRD FR-AD-08): create the next cycle and set default deadlines. Reopening FINAL is a workflow transition. */
@Service
public class CycleAdminService {

    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CycleAdminService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Creates a planning cycle in FORECAST_DRAFT.
     *
     * @param month            YYYY-MM
     * @param reviewDeadline   optional default review deadline
     * @param finalizeDeadline optional default finalization deadline
     * @param user             Admin (or Forecaster, PRD architecture table "create: Admin, Forecaster")
     * @return the new cycle id
     * @throws ApiException 400 bad month, 403 wrong role, 409 cycle exists
     */
    @Transactional
    public UUID create(String month, OffsetDateTime reviewDeadline, OffsetDateTime finalizeDeadline, CurrentUser user) {
        if (!(user.has("admin") || user.has("forecaster"))) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Admin or Forecaster only");
        if (month == null || !MONTH.matcher(month).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH", "Month must be YYYY-MM");
        UUID id = UUID.randomUUID();
        int n = jdbc.sql("INSERT INTO planning_cycle (id, month, review_deadline, finalize_deadline) VALUES (:id, :m, :r, :f) ON CONFLICT (month) DO NOTHING")
            .param("id", id).param("m", month).param("r", reviewDeadline).param("f", finalizeDeadline).update();
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "CYCLE_EXISTS", "Cycle " + month + " already exists");
        audit.record(user.username(), "CYCLE_CREATE", "planning_cycle", id.toString(), Map.of("month", month));
        return id;
    }

    /**
     * Sets the deadlines of a cycle (either may be null to clear it).
     *
     * @throws ApiException 403 not Admin, 404 unknown cycle, 409 cycle FINAL
     */
    @Transactional
    public void setDeadlines(UUID cycleId, OffsetDateTime reviewDeadline, OffsetDateTime finalizeDeadline, CurrentUser user) {
        if (!user.has("admin")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Admin only");
        String state = jdbc.sql("SELECT state FROM planning_cycle WHERE id = :c").param("c", cycleId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Cycle not found"));
        if ("FINAL".equals(state)) throw new ApiException(HttpStatus.CONFLICT, "CYCLE_LOCKED", "Cycle is FINAL");
        jdbc.sql("UPDATE planning_cycle SET review_deadline = :r, finalize_deadline = :f WHERE id = :c")
            .param("r", reviewDeadline).param("f", finalizeDeadline).param("c", cycleId).update();
        audit.record(user.username(), "CYCLE_DEADLINES", "planning_cycle", cycleId.toString(),
            Map.of("reviewDeadline", String.valueOf(reviewDeadline), "finalizeDeadline", String.valueOf(finalizeDeadline)));
    }
}
