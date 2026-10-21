package com.pifa.ops;

import com.pifa.audit.AuditService;
import com.pifa.config.PifaProperties;
import com.pifa.dataset.DatasetService;
import com.pifa.security.CurrentUser;
import com.pifa.storage.FileStore;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Housekeeping jobs (Phase 5), run only on worker pods ({@code pifa.jobs.enabled=true}) and only by one of them
 * at a time (PostgreSQL advisory lock):
 * <ul>
 *   <li><b>Partition maintenance</b> (daily, and at start-up): creates the monthly partitions of
 *       {@code audit_log} and {@code change_log} for the next 3 months.</li>
 *   <li><b>Retention</b> (nightly, PRD 13 "data retention"):
 *     <ol>
 *       <li>soft-deleted versions older than {@code purge-days} (default 90) are purged (data and file removed,
 *           tombstone kept), if {@code auto-purge-deleted} is on;</li>
 *       <li>original files older than {@code file-years} (default 3) are removed from the object store for versions
 *           that are not active (the version row and its lines stay);</li>
 *       <li>audit and change-log months older than {@code audit-years} (default 7) are dropped, only if
 *           {@code drop-old-partitions} is explicitly switched on (export them first; see the runbook). The audit
 *           chain anchor is moved so the remaining chain still verifies.</li>
 *     </ol></li>
 * </ul>
 * Every action writes an audit entry as actor {@code system-retention}.
 */
@Component
public class OpsJobs {

    private static final Logger log = LoggerFactory.getLogger(OpsJobs.class);
    private static final long LOCK_KEY = 7003L;
    private static final CurrentUser RETENTION = new CurrentUser("system-retention", Set.of("admin"));

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final DatasetService datasets;
    private final FileStore files;
    private final AuditService audit;
    private final PifaProperties props;
    @Value("${pifa.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    public OpsJobs(JdbcClient jdbc, TransactionTemplate tx, DatasetService datasets, FileStore files, AuditService audit, PifaProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.datasets = datasets;
        this.files = files;
        this.audit = audit;
        this.props = props;
    }

    /** Creates future partitions right after start-up, so a fresh install never writes into the DEFAULT partition. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (jobsEnabled) maintainPartitions();
    }

    /** Daily at 01:15. */
    @Scheduled(cron = "${pifa.retention.partition-cron:0 15 1 * * *}")
    public void scheduledPartitions() {
        if (jobsEnabled) maintainPartitions();
    }

    /** Nightly at 03:00. */
    @Scheduled(cron = "${pifa.retention.cron:0 0 3 * * *}")
    public void scheduledRetention() {
        if (jobsEnabled) {
            try {
                retention();
            } catch (RuntimeException e) {
                log.error("Retention job failed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Creates the monthly partitions of audit_log and change_log for this month and the next 3 (idempotent).
     *
     * @return number of partitions created
     */
    public int maintainPartitions() {
        Integer created = tx.execute(s -> {
            if (!lock()) return 0;
            int n = 0;
            YearMonth m = YearMonth.now();
            for (int i = 0; i <= 3; i++, m = m.plusMonths(1)) {
                for (String table : List.of("audit_log", "change_log")) {
                    String name = table + "_" + m.toString().replace('-', '_');
                    boolean exists = jdbc.sql("SELECT to_regclass(:n) IS NOT NULL").param("n", name).query(Boolean.class).single();
                    if (exists) continue;
                    // Names come from a fixed list and a YearMonth, never from user input.
                    jdbc.sql("CREATE TABLE " + name + " PARTITION OF " + table + " FOR VALUES FROM ('" + m.atDay(1) + "') TO ('" + m.plusMonths(1).atDay(1) + "')").update();
                    n++;
                }
            }
            return n;
        });
        if (created != null && created > 0) log.info("Created {} partition(s)", created);
        return created == null ? 0 : created;
    }

    /**
     * Runs the three retention steps (see class doc).
     *
     * @return counts per step, for the log and the tests
     */
    public Map<String, Integer> retention() {
        Map<String, Integer> out = new LinkedHashMap<>();
        var r = props.retention();

        // 1. Soft-deleted versions past the purge period. Each purge is its own transaction (one bad row must not block the rest).
        int purged = 0;
        if (r.autoPurgeDeleted()) {
            List<UUID> due = jdbc.sql("SELECT id FROM dataset_version WHERE status = 'DELETED' AND deleted_at < now() - make_interval(days => :d)")
                .param("d", r.purgeDays()).query(UUID.class).list();
            for (UUID id : due) {
                try {
                    datasets.purge(id, RETENTION);
                    purged++;
                } catch (RuntimeException e) {
                    log.warn("Retention could not purge version {}: {}", id, e.getMessage());
                }
            }
        }
        out.put("purgedVersions", purged);

        // 2. Original files older than the file retention, for versions that are not active.
        int expired = 0;
        List<Object[]> old = jdbc.sql("SELECT id, object_key FROM dataset_version WHERE object_key IS NOT NULL AND NOT is_active AND created_at < now() - make_interval(years => :y)")
            .param("y", r.fileYears()).query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)}).list();
        for (Object[] row : old) {
            UUID id = (UUID) row[0];
            files.delete((String) row[1]);
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE dataset_version SET object_key = NULL WHERE id = :id").param("id", id).update();
                audit.record(RETENTION.username(), "FILE_EXPIRED", "dataset_version", id.toString(), Map.of("fileYears", r.fileYears()));
            });
            expired++;
        }
        out.put("expiredFiles", expired);

        // 3. Old audit / change-log months (only when explicitly enabled).
        int dropped = 0;
        if (r.dropOldPartitions()) {
            Integer d = tx.execute(s -> lock() ? dropOldPartitions(r.auditYears()) : 0);
            dropped = d == null ? 0 : d;
        }
        out.put("droppedPartitions", dropped);
        log.info("Retention run: {}", out);
        return out;
    }

    /**
     * Drops monthly partitions entirely older than the retention. Before dropping audit months, records the
     * new chain anchor (first remaining row and its prev_hash). Runs inside a transaction holding the lock.
     */
    private int dropOldPartitions(int years) {
        LocalDate cutoff = YearMonth.now().minusYears(years).atDay(1);
        int n = 0;
        for (String table : List.of("audit_log", "change_log")) {
            List<String> parts = jdbc.sql("""
                    SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
                    WHERE p.relname = :t AND c.relname ~ '_[0-9]{4}_[0-9]{2}$' ORDER BY c.relname
                    """).param("t", table).query(String.class).list();
            for (String part : parts) {
                YearMonth ym = YearMonth.parse(part.substring(part.length() - 7).replace('_', '-'));
                if (!ym.plusMonths(1).atDay(1).isAfter(cutoff)) {
                    if (table.equals("audit_log")) moveAnchor(ym.plusMonths(1).atDay(1));
                    jdbc.sql("DROP TABLE " + part).update(); // DDL: the row-level append-only trigger does not apply
                    audit.record(RETENTION.username(), "PARTITION_DROPPED", table, part, Map.of("auditYears", years));
                    n++;
                }
            }
        }
        return n;
    }

    /** Records the first audit row at or after {@code from} as the new start of the verifiable chain. */
    private void moveAnchor(LocalDate from) {
        jdbc.sql("SELECT id, prev_hash FROM audit_log WHERE at >= CAST(:f AS DATE) ORDER BY id LIMIT 1").param("f", from)
            .query((rs, i) -> {
                jdbc.sql("INSERT INTO audit_anchor (singleton, first_id, prev_hash, dropped_before) VALUES (TRUE, :id, :h, CAST(:f AS DATE)) "
                        + "ON CONFLICT (singleton) DO UPDATE SET first_id = EXCLUDED.first_id, prev_hash = EXCLUDED.prev_hash, dropped_before = EXCLUDED.dropped_before, created_at = now()")
                    .param("id", rs.getLong(1)).param("h", rs.getString(2)).param("f", from).update();
                return null;
            }).list();
    }

    /** Transaction-scoped advisory lock so only one worker replica runs a job at a time. */
    private boolean lock() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)").param("k", LOCK_KEY).query(Boolean.class).single();
    }
}
