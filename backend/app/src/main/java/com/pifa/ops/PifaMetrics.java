package com.pifa.ops;

import com.pifa.events.LiveEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Business and operational metrics, exposed at {@code /actuator/prometheus} on the internal management port
 * (architecture.md 14). Names below are what dashboards and alert rules (infra/helm) rely on; Prometheus
 * adds {@code _total}, {@code _seconds} etc. suffixes.
 *
 * <ul>
 *   <li>{@code pifa_ingestion_files_total{type,status,channel}} - files received, by outcome</li>
 *   <li>{@code pifa_ingestion_duration_seconds{type}} - time to validate and store a file</li>
 *   <li>{@code pifa_sftp_poll_errors_total} - SFTP polls that failed (alert: no successful poll for 10 min)</li>
 *   <li>{@code pifa_transitions_total{action}} - workflow transitions</li>
 *   <li>{@code pifa_allocation_duration_seconds} - allocation proposal runs</li>
 *   <li>{@code pifa_transfers_total{status}} - transfer decisions</li>
 *   <li>{@code pifa_outbox_pending} - e-mails waiting to be sent (alert when it keeps growing)</li>
 *   <li>{@code pifa_cycle_stage_overdue} - open cycles past their review/finalize deadline</li>
 *   <li>{@code pifa_audit_chain_ok} - 1 if the last audit-chain verification passed, 0 if broken, -1 unknown</li>
 *   <li>{@code pifa_live_connections} - browsers connected for live refresh on this instance</li>
 * </ul>
 */
@Component
public class PifaMetrics {

    private final MeterRegistry registry;

    public PifaMetrics(MeterRegistry registry, JdbcClient jdbc, LiveEvents events) {
        this.registry = registry;
        // Gauges are evaluated when Prometheus scrapes (every 30-60 s); each is one cheap query.
        Gauge.builder("pifa.outbox.pending", jdbc, j -> safe(() -> j.sql("SELECT COUNT(*) FROM outbox_event WHERE sent_at IS NULL AND attempts < 5").query(Long.class).single()))
            .description("E-mails waiting in the outbox").register(registry);
        Gauge.builder("pifa.cycle.stage.overdue", jdbc, j -> safe(() -> j.sql("""
                SELECT COUNT(*) FROM planning_cycle
                WHERE (state = 'REGIONAL_REVIEW' AND review_deadline < now()) OR (state = 'REGIONAL_FINALIZATION' AND finalize_deadline < now())
                """).query(Long.class).single())).description("Open cycles past their stage deadline").register(registry);
        Gauge.builder("pifa.audit.chain.ok", jdbc, j -> safe(() -> j.sql("SELECT CASE WHEN ok THEN 1 ELSE 0 END FROM system_status WHERE key = 'audit.chain'")
                .query(Long.class).optional().orElse(-1L))).description("1 = last audit-chain verification passed").register(registry);
        Gauge.builder("pifa.live.connections", events, LiveEvents::connections).description("Live-refresh streams on this instance").register(registry);
    }

    /** Runs a gauge query; a failing database gives NaN (shown as a gap) instead of breaking the scrape. */
    private static double safe(java.util.function.Supplier<Long> q) {
        try {
            return q.get();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /**
     * Records one ingested file.
     *
     * @param type    FORECAST_UPLOAD / INVENTORY_UPLOAD
     * @param status  VALID / REJECTED
     * @param channel SFTP / UI / SYNTHETIC
     * @param nanos   how long it took
     */
    public void ingestion(String type, String status, String channel, long nanos) {
        Counter.builder("pifa.ingestion.files").tag("type", type).tag("status", status).tag("channel", channel).register(registry).increment();
        Timer.builder("pifa.ingestion.duration").tag("type", type).register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Counts a failed SFTP poll. */
    public void sftpPollError() {
        Counter.builder("pifa.sftp.poll.errors").register(registry).increment();
    }

    /** @param action the workflow action, e.g. PUBLISH */
    public void transition(String action) {
        Counter.builder("pifa.transitions").tag("action", action).register(registry).increment();
    }

    /** @param nanos duration of an allocation proposal run */
    public void allocation(long nanos) {
        Timer.builder("pifa.allocation.duration").register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** @param status ACCEPTED / REJECTED / CANCELLED / REQUESTED */
    public void transfer(String status) {
        Counter.builder("pifa.transfers").tag("status", status).register(registry).increment();
    }
}
