package com.pifa.audit;

import com.pifa.notify.NotificationService;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Verifies the audit log's hash chain (architecture.md 8.3, Phase 4 "nightly audit hash-chain verification
 * job with alert").
 *
 * <p>For every row in id order: its {@code prev_hash} must equal the previous row's {@code hash}; for
 * hash-version-2 rows the hash is also recomputed from the stored values. The first mismatch means the
 * table was tampered with (or restored inconsistently). The result is stored in {@code system_status}
 * (key {@code audit.chain}) for the Admin dashboard; a break also notifies Admin and logs an ERROR, which
 * the alerting picks up.
 */
@Component
public class AuditChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(AuditChainVerifier.class);

    /**
     * Outcome of a verification.
     *
     * @param ok         true if the whole chain is intact
     * @param rows       rows checked
     * @param recomputed rows whose hash was fully recomputed (hash version 2)
     * @param brokenAtId id of the first bad row, or null
     * @param message    human-readable summary
     */
    public record Result(boolean ok, long rows, long recomputed, Long brokenAtId, String message) {}

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    @org.springframework.beans.factory.annotation.Value("${pifa.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    public AuditChainVerifier(JdbcClient jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    /** Nightly at 02:30 (server time). Never throws. */
    @Scheduled(cron = "${pifa.audit.verify-cron:0 30 2 * * *}")
    public void nightly() {
        if (!jobsEnabled) return; // only worker pods run the nightly verification
        try {
            verify();
        } catch (RuntimeException e) {
            log.error("Audit chain verification could not run: {}", e.getMessage(), e);
        }
    }

    /**
     * Walks the whole chain (streaming, constant memory) and records the result.
     *
     * @return the result
     */
    public Result verify() {
        // Normally the chain starts at the genesis hash. After old months were dropped under the retention policy,
        // it starts at the recorded anchor: the first remaining row and the prev_hash it carried.
        record Anchor(long firstId, String prevHash) {}
        Anchor anchor = jdbc.sql("SELECT first_id, prev_hash FROM audit_anchor").query((rs, n) -> new Anchor(rs.getLong(1), rs.getString(2))).optional().orElse(null);
        AtomicReference<String> prev = new AtomicReference<>(anchor == null ? AuditService.GENESIS : anchor.prevHash());
        String anchorQuery = anchor == null ? "" : " WHERE id >= " + anchor.firstId();
        AtomicLong rows = new AtomicLong();
        AtomicLong recomputed = new AtomicLong();
        AtomicReference<Long> broken = new AtomicReference<>();
        AtomicReference<String> why = new AtomicReference<>();
        jdbc.sql("SELECT id, at, actor, action, entity_type, entity_id, detail::text, prev_hash, hash, hash_version FROM audit_log" + anchorQuery + " ORDER BY id")
            .query(rs -> {
                if (broken.get() != null) return;
                rows.incrementAndGet();
                long id = rs.getLong(1);
                String prevHash = rs.getString(8);
                String hash = rs.getString(9);
                if (!prevHash.equals(prev.get())) {
                    broken.set(id);
                    why.set("row " + id + ": prev_hash does not match the previous row (a row was deleted or altered)");
                    return;
                }
                if (rs.getInt(10) >= 2) {
                    recomputed.incrementAndGet();
                    String expected = AuditService.hash(prevHash, rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7));
                    if (!expected.equals(hash)) {
                        broken.set(id);
                        why.set("row " + id + ": content does not match its hash (the row was altered)");
                        return;
                    }
                }
                prev.set(hash);
            });
        boolean ok = broken.get() == null;
        String msg = ok ? "Audit chain intact: " + rows.get() + " rows (" + recomputed.get() + " fully recomputed)" : why.get();
        jdbc.sql("INSERT INTO system_status (key, value, ok, updated_at) VALUES ('audit.chain', :v, :ok, now()) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, ok = EXCLUDED.ok, updated_at = now()")
            .param("v", msg).param("ok", ok).update();
        if (!ok) {
            log.error("AUDIT CHAIN BROKEN: {}", msg);
            notifications.notifyRole("admin", null, "Audit log integrity check FAILED", msg, null);
        } else {
            log.info(msg);
        }
        return new Result(ok, rows.get(), recomputed.get(), broken.get(), msg);
    }
}
