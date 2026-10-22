package com.pifa.parameters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.master.MasterDataService;
import com.pifa.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Admin-editable numbers the rules use (PRD FR-AD-06, architecture.md ADR-004): rule <em>logic</em>
 * lives in DRL files and changes only through a release; these <em>numbers</em> change at runtime.
 *
 * <p>Parameters are versioned: every save creates a new version with an effective date, and the
 * version in force is the newest one whose effective date has been reached. Proposals and snapshots
 * record the version they used, so old results stay explainable after a change.
 */
@Service
public class RuleParameterService {

    /**
     * The parameter values.
     *
     * @param regionWeights        constrained-split weight per region code, in percent; must total 100 (default 25 each)
     * @param changeThresholdPct   a Regional Manager change above this percent needs a reason (default 20)
     * @param minRegionFloor       units a donor region must keep in a transfer (default 0)
     * @param spikeFactor          forecast above this multiple of 6-month sales raises a warning (default 3)
     * @param capAtDemand          cap constrained regions at their demand (default off)
     * @param autoActivateForecast a new valid forecast upload becomes active automatically while in draft (default on)
     * @param weightMode           how constrained stock is split (Phase 7): {@code FIXED} uses {@code regionWeights} for every
     *                             product; {@code FORECAST_SHARE} splits each product's stock in proportion to the regions'
     *                             forecast ("assign as per forecast"); {@code SALES_SHARE} uses each product's share of sales
     *                             per region over the last {@code shareMonths} months. Both fall back to {@code regionWeights}
     *                             for a product with no forecast / no sales. The Planner may override the mode for one run.
     * @param shareMonths          look-back window for SALES_SHARE, 1..36 months (default 12)
     */
    public record Values(Map<String, Integer> regionWeights, double changeThresholdPct, int minRegionFloor,
                         double spikeFactor, boolean capAtDemand, boolean autoActivateForecast, String weightMode, int shareMonths) {

        /** Fills the Phase 7 fields for versions saved before they existed (null mode = FIXED, 0 months = 12). */
        public Values {
            if (weightMode == null || weightMode.isBlank()) weightMode = "FIXED";
            if (shareMonths == 0) shareMonths = 12;
        }

        /** Values with fixed weights (the behaviour before Phase 7). */
        public Values(Map<String, Integer> regionWeights, double changeThresholdPct, int minRegionFloor,
                      double spikeFactor, boolean capAtDemand, boolean autoActivateForecast) {
            this(regionWeights, changeThresholdPct, minRegionFloor, spikeFactor, capAtDemand, autoActivateForecast, "FIXED", 12);
        }

        /** @return true when constrained products are split by their own historical sales share */
        public boolean salesShare() { return "SALES_SHARE".equals(weightMode); }

        /** @return true when constrained products are split in proportion to the regions' forecast */
        public boolean forecastShare() { return "FORECAST_SHARE".equals(weightMode); }
    }

    /**
     * One stored version.
     *
     * @param version       version number
     * @param values        the parameter values
     * @param effectiveFrom when the version takes effect
     * @param changedBy     who saved it
     * @param reason        why
     * @param createdAt     when it was saved
     */
    public record Version(int version, Values values, OffsetDateTime effectiveFrom, String changedBy, String reason, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final MasterDataService master;

    public RuleParameterService(JdbcClient jdbc, ObjectMapper json, AuditService audit, MasterDataService master) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.master = master;
    }

    /** @return the version in force now (the newest one whose effective date has passed) */
    public Version current() {
        return jdbc.sql("SELECT version, params::text, effective_from, changed_by, reason, created_at FROM rule_parameter_set "
                + "WHERE effective_from <= now() ORDER BY version DESC LIMIT 1")
            .query((rs, n) -> map(rs.getInt(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class)))
            .single();
    }

    /** @return every version, newest first (including future-dated ones) */
    public List<Version> history() {
        return jdbc.sql("SELECT version, params::text, effective_from, changed_by, reason, created_at FROM rule_parameter_set ORDER BY version DESC")
            .query((rs, n) -> map(rs.getInt(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class)))
            .list();
    }

    /**
     * Saves a new version (Admin only).
     *
     * @param values        the new values (all of them; a version is always complete)
     * @param effectiveFrom when it takes effect; null means now
     * @param reason        mandatory explanation (audited)
     * @param user          the caller
     * @return the stored version
     * @throws ApiException 403 not Admin; 400 invalid values or missing reason
     */
    @Transactional
    public Version save(Values values, OffsetDateTime effectiveFrom, String reason, CurrentUser user) {
        if (!user.has("admin")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Admin may change rule parameters");
        if (reason == null || reason.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason is required");
        validate(values);
        // Serialise the version number: concurrent saves must not get the same number.
        jdbc.sql("LOCK TABLE rule_parameter_set IN SHARE ROW EXCLUSIVE MODE").update();
        int next = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM rule_parameter_set").query(Integer.class).single();
        OffsetDateTime from = effectiveFrom != null ? effectiveFrom : OffsetDateTime.now();
        jdbc.sql("INSERT INTO rule_parameter_set (version, params, effective_from, changed_by, reason) VALUES (:v, CAST(:p AS jsonb), :f, :u, :r)")
            .param("v", next).param("p", toJson(values)).param("f", from).param("u", user.username()).param("r", reason.trim()).update();
        audit.record(user.username(), "RULE_PARAMETERS_CHANGED", "rule_parameter_set", String.valueOf(next),
            Map.of("values", values, "effectiveFrom", from.toString(), "reason", reason.trim()));
        return history().get(0);
    }

    /**
     * Checks a set of values: weights for exactly the active regions, each 0..100, totalling 100; the
     * other numbers within sensible bounds.
     *
     * @throws ApiException 400 with the first problem found
     */
    void validate(Values v) {
        List<String> regions = master.activeRegionCodes();
        if (v.regionWeights() == null || !new HashSet<>(regions).equals(v.regionWeights().keySet())) {
            throw bad("Weights must be given for exactly these regions: " + regions);
        }
        int total = 0;
        for (var e : v.regionWeights().entrySet()) {
            if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 100) throw bad("Weight of " + e.getKey() + " must be 0..100");
            total += e.getValue();
        }
        if (total != 100) throw bad("Weights must total 100 (now " + total + ")");
        if (v.changeThresholdPct() < 0 || v.changeThresholdPct() > 1000) throw bad("Change threshold must be 0..1000 %");
        if (v.minRegionFloor() < 0) throw bad("Minimum region floor cannot be negative");
        if (v.spikeFactor() <= 0) throw bad("Spike factor must be greater than 0");
        if (!List.of("FIXED", "FORECAST_SHARE", "SALES_SHARE").contains(v.weightMode())) throw bad("Weight mode must be FIXED, FORECAST_SHARE or SALES_SHARE");
        if (v.shareMonths() < 1 || v.shareMonths() > 36) throw bad("Sales-share window must be 1..36 months");
    }

    private static ApiException bad(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETERS", msg); }

    private Version map(int version, String params, OffsetDateTime from, String by, String reason, OffsetDateTime at) {
        try {
            Values v = json.readValue(params, Values.class);
            // Keep the weights in region display order for stable output.
            Map<String, Integer> ordered = new LinkedHashMap<>();
            for (String r : master.activeRegionCodes()) if (v.regionWeights().containsKey(r)) ordered.put(r, v.regionWeights().get(r));
            v.regionWeights().forEach(ordered::putIfAbsent);
            return new Version(version, new Values(ordered, v.changeThresholdPct(), v.minRegionFloor(), v.spikeFactor(), v.capAtDemand(), v.autoActivateForecast(),
                v.weightMode(), v.shareMonths()), from, by, reason, at);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt rule parameters version " + version, e);
        }
    }

    private String toJson(Values v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
