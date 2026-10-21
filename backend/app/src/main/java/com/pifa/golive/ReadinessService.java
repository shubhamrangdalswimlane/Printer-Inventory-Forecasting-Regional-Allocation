package com.pifa.golive;

import com.pifa.admin.KeycloakUsersService;
import com.pifa.common.ApiException;
import com.pifa.config.PifaProperties;
import com.pifa.master.MasterDataService;
import com.pifa.parameters.RuleParameterService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Go-live readiness check (Phase 6b; {@code docs/Phase.md} section 5 "Go-live checklist").
 *
 * <p>Runs every checklist item the system can verify by itself - configuration (no synthetic generator, virus scanning,
 * S3 storage, strict SFTP host keys, mail), data (synthetic data purged, product master loaded, 24 months of real sales,
 * matching report clean, parameters reviewed, parallel run reconciled), integrity (audit chain verified recently) and
 * access (every role and every region has a real user in Keycloak). Items only people can confirm (pentest, DR drill,
 * training ...) are listed as {@code MANUAL} so the go/no-go meeting has the full list in one place.
 *
 * <p>Read-only: running it changes nothing.
 */
@Service
public class ReadinessService {

    /** PASS = ready; WARN = needs a human look; FAIL = blocks go-live; MANUAL = confirmed outside the system. */
    public enum Status { PASS, WARN, FAIL, MANUAL }

    /**
     * One checklist line.
     *
     * @param key    stable id, e.g. {@code synthetic.data}
     * @param item   go-live checklist number in Phase.md section 5 (0 = technical prerequisite not numbered there)
     * @param title  what is checked
     * @param status the outcome
     * @param detail what was found, or what to do
     */
    public record Check(String key, int item, String title, Status status, String detail) {}

    /**
     * The whole report.
     *
     * @param overall FAIL if any check fails, else WARN if any warns, else PASS (MANUAL items do not count)
     * @param checks  all checks in checklist order
     * @param at      when it ran
     */
    public record Report(Status overall, List<Check> checks, OffsetDateTime at) {}

    private final JdbcClient jdbc;
    private final PifaProperties props;
    private final MasterDataService master;
    private final RuleParameterService parameters;
    private final KeycloakUsersService users;
    private final String storageType;

    public ReadinessService(JdbcClient jdbc, PifaProperties props, MasterDataService master, RuleParameterService parameters,
                            KeycloakUsersService users, @Value("${pifa.storage.type:local}") String storageType) {
        this.jdbc = jdbc;
        this.props = props;
        this.master = master;
        this.parameters = parameters;
        this.users = users;
        this.storageType = storageType;
    }

    /**
     * Runs all checks.
     *
     * @return the report; never throws for a failing check (a check that cannot run reports WARN with the reason)
     */
    public Report run() {
        List<Check> c = new ArrayList<>();

        // ---- configuration (technical prerequisites)
        c.add(new Check("config.storage", 0, "Original files stored in S3", "s3".equals(storageType) ? Status.PASS : Status.FAIL,
            "pifa.storage.type = " + storageType));
        c.add(new Check("config.clamav", 0, "Virus scanning enabled", props.clamav().enabled() ? Status.PASS : Status.FAIL,
            props.clamav().enabled() ? "Every file is scanned over TLS" : "pifa.clamav.enabled is false"));
        boolean sftp = props.sftp().enabled();
        c.add(new Check("config.sftp", 7, "SFTP polling on with host-key checking",
            !sftp ? Status.WARN : props.sftp().strictHostKey() ? Status.PASS : Status.FAIL,
            !sftp ? "SFTP polling is off (manual uploads only)" : props.sftp().strictHostKey() ? "Host key verified against known_hosts" : "strict-host-key is false"));
        c.add(new Check("config.mail", 12, "E-mail notifications enabled", props.mail().enabled() ? Status.PASS : Status.WARN,
            props.mail().enabled() ? "Mail outbox is sending" : "pifa.mail.enabled is false: users get in-app notifications only"));

        // ---- synthetic data (item 9)
        c.add(new Check("synthetic.generator", 9, "Synthetic generator disabled", props.synthetic().enabled() ? Status.FAIL : Status.PASS,
            props.synthetic().enabled() ? "pifa.synthetic.enabled is true" : "Disabled"));
        long synthetic = count("SELECT (SELECT COUNT(*) FROM product WHERE source = 'SYNTHETIC') + (SELECT COUNT(*) FROM dataset_version WHERE source = 'SYNTHETIC') "
            + "+ (SELECT COUNT(*) FROM sales_history WHERE source = 'SYNTHETIC')");
        c.add(new Check("synthetic.data", 9, "Synthetic data purged", synthetic == 0 ? Status.PASS : Status.FAIL,
            synthetic == 0 ? "No synthetic rows" : synthetic + " synthetic rows remain: Admin, Synthetic data, Purge"));

        // ---- master data and matching (item 8)
        long products = count("SELECT COUNT(*) FROM product WHERE active AND source <> 'SYNTHETIC'");
        c.add(new Check("master.products", 8, "Product master loaded", products > 0 ? Status.PASS : Status.FAIL, products + " active real products"));
        c.add(statusCheck("golive.matching", 8, "Real-file matching report shows zero unknown products",
            "Run Admin, Go-live, Matching report on a real file from each upstream system", Status.FAIL));

        // ---- sales history (Phase 6a import)
        long months = count("SELECT COUNT(DISTINCT month) FROM sales_history WHERE source <> 'SYNTHETIC'");
        c.add(new Check("sales.history", 0, "24 months of real sales history",
            months >= 24 ? Status.PASS : months > 0 ? Status.WARN : Status.FAIL, months + " months imported"));

        // ---- rule parameters (item 10)
        RuleParameterService.Version p = parameters.current();
        c.add(new Check("parameters.reviewed", 10, "Rule parameters reviewed and signed off", p.version() > 1 ? Status.PASS : Status.WARN,
            p.version() > 1 ? "Version " + p.version() + " saved by " + p.changedBy() + ": " + p.reason()
                : "Still the seeded defaults (version 1): review with the business owner and save with the sign-off reference"));

        // ---- parallel run (item 11)
        c.add(statusCheck("golive.reconciliation", 11, "Parallel-run cycle reconciled",
            "Run Admin, Go-live, Reconciliation with the old process's final allocation", Status.WARN));

        // ---- integrity and operations
        c.add(statusCheck("audit.chain", 0, "Audit chain verified in the last 2 days", "The nightly verification has not run yet", Status.FAIL));
        if (sftp) c.add(statusCheck("sftp.poll", 7, "Last SFTP poll succeeded", "The worker has not polled yet", Status.FAIL));

        // ---- people (item 6)
        c.add(usersCheck());

        // ---- items only people can confirm
        c.add(manual(1, "All Must requirements accepted in UAT (docs/uat)"));
        c.add(manual(2, "Pentest: no open critical/high; ASVS L2 checklist complete"));
        c.add(manual(3, "Load test at 2x volume meets the targets (loadtest/k6-planning.js)"));
        c.add(manual(4, "DR restore drill passed: RPO 15 min, RTO 4 h (docs/runbooks/db-restore.md)"));
        c.add(manual(5, "Alerts routed to on-call and tested; runbooks published (docs/runbooks)"));
        c.add(manual(6, "MFA enforced for Admin and Planner; corporate federation tested"));
        c.add(manual(12, "Users trained (docs/training); support model and escalation contacts published"));
        c.add(manual(13, "Backup jobs green for 7 consecutive days"));
        c.add(manual(14, "Rollback plan rehearsed (previous image + DB restore point)"));

        Status overall = c.stream().anyMatch(x -> x.status() == Status.FAIL) ? Status.FAIL
            : c.stream().anyMatch(x -> x.status() == Status.WARN) ? Status.WARN : Status.PASS;
        return new Report(overall, c, OffsetDateTime.now());
    }

    /**
     * Turns a {@code system_status} row into a check. A row older than 2 days counts as stale (WARN) for the audit
     * chain and SFTP poll, whose jobs run nightly / every minute.
     *
     * @param key        system_status key
     * @param item       checklist number
     * @param title      check title
     * @param missingMsg detail when the row does not exist yet
     * @param whenBad    status to report when the row says not ok
     */
    private Check statusCheck(String key, int item, String title, String missingMsg, Status whenBad) {
        record Row(String value, Boolean ok, OffsetDateTime at) {}
        Optional<Row> row = jdbc.sql("SELECT value, ok, updated_at FROM system_status WHERE key = :k").param("k", key)
            .query((rs, n) -> new Row(rs.getString(1), (Boolean) rs.getObject(2), rs.getObject(3, OffsetDateTime.class))).optional();
        if (row.isEmpty()) return new Check(key, item, title, Status.WARN, missingMsg);
        Row r = row.get();
        boolean recurring = key.equals("audit.chain") || key.equals("sftp.poll");
        if (recurring && r.at().isBefore(OffsetDateTime.now().minusDays(2))) {
            return new Check(key, item, title, Status.WARN, "Last result is older than 2 days (" + r.at() + "): " + r.value());
        }
        return new Check(key, item, title, Boolean.TRUE.equals(r.ok()) ? Status.PASS : whenBad, r.value() + " (" + r.at() + ")");
    }

    /** Every role has an enabled user, and every active region has an enabled Regional Manager. */
    private Check usersCheck() {
        String title = "Real users for every role and region in Keycloak";
        List<Map<String, Object>> list;
        try {
            list = users.users();
        } catch (ApiException e) {
            return new Check("keycloak.users", 6, title, Status.WARN, e.getMessage());
        }
        List<String> missing = new ArrayList<>();
        for (String role : List.of("forecaster", "planner", "admin")) {
            if (list.stream().noneMatch(u -> enabled(u) && roles(u).contains(role))) missing.add("no " + role);
        }
        for (String region : master.activeRegionCodes()) {
            boolean covered = list.stream().anyMatch(u -> enabled(u) && roles(u).contains("regional_manager")
                && regions(u).stream().anyMatch(region::equalsIgnoreCase));
            if (!covered) missing.add("no Regional Manager for " + region);
        }
        return new Check("keycloak.users", 6, title, missing.isEmpty() ? Status.PASS : Status.FAIL,
            missing.isEmpty() ? list.size() + " users; every role and region covered" : String.join(", ", missing));
    }

    private static boolean enabled(Map<String, Object> u) { return Boolean.TRUE.equals(u.get("enabled")); }

    @SuppressWarnings("unchecked")
    private static List<String> roles(Map<String, Object> u) { return (List<String>) u.getOrDefault("roles", List.of()); }

    @SuppressWarnings("unchecked")
    private static List<String> regions(Map<String, Object> u) { return (List<String>) u.getOrDefault("regions", List.of()); }

    private static Check manual(int item, String title) {
        return new Check("manual." + item, item, title, Status.MANUAL, "Confirm at the go/no-go meeting");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
