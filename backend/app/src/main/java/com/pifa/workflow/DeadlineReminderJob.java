package com.pifa.workflow;

import com.pifa.notify.NotificationService;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automated stage-deadline reminders (Phase 7 roadmap: "SLA and stage-deadline reporting, automated reminders").
 *
 * <p>Every hour (worker pods only) it looks at cycles waiting on the regions:
 * <ul>
 *   <li>REGIONAL_REVIEW with a review deadline: regions that have not submitted;</li>
 *   <li>REGIONAL_FINALIZATION with a finalize deadline: regions that have not finalized.</li>
 * </ul>
 * For each such region it sends at most two reminders per deadline: <b>DUE_SOON</b> once the deadline is less than
 * 24 hours away (to that region's Regional Managers), and <b>OVERDUE</b> once it has passed (to the region's
 * Regional Managers and to the Planner, who decides whether to proceed). The {@code deadline_reminder} table
 * records what was sent, so restarts and several worker pods never send duplicates; moving a deadline makes the
 * reminders for the new deadline due again.
 */
@Component
public class DeadlineReminderJob {

    private static final Logger log = LoggerFactory.getLogger(DeadlineReminderJob.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm z");

    /**
     * A region that still owes an action before a deadline.
     *
     * @param cycleId  the cycle
     * @param month    its month (for the message)
     * @param region   region code
     * @param stage    REVIEW or FINALIZE
     * @param deadline the deadline
     */
    record Pending(UUID cycleId, String month, String region, String stage, OffsetDateTime deadline) {}

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final ZoneId zone;
    @Value("${pifa.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    /**
     * @param zone time zone used to show deadlines in messages (default IST, where the business runs)
     */
    public DeadlineReminderJob(JdbcClient jdbc, NotificationService notifications, TransactionTemplate tx,
                               @Value("${pifa.reminders.zone:Asia/Kolkata}") String zone) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.tx = tx;
        this.zone = ZoneId.of(zone);
    }

    /** Hourly at minute 5. Never throws. */
    @Scheduled(cron = "${pifa.reminders.cron:0 5 * * * *}")
    public void scheduled() {
        if (!jobsEnabled) return;
        try {
            int n = runOnce(OffsetDateTime.now());
            if (n > 0) log.info("Sent {} deadline reminder(s)", n);
        } catch (RuntimeException e) {
            log.error("Deadline reminders failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Sends every reminder that is due at {@code now} and was not sent before.
     *
     * @param now the reference time (a parameter so tests can move the clock)
     * @return number of reminders sent
     */
    public int runOnce(OffsetDateTime now) {
        List<Pending> pending = jdbc.sql("""
                SELECT c.id, c.month, s.region_code, 'REVIEW', c.review_deadline
                  FROM planning_cycle c JOIN cycle_region_status s ON s.cycle_id = c.id
                 WHERE c.state = 'REGIONAL_REVIEW' AND c.review_deadline IS NOT NULL AND s.review_status <> 'SUBMITTED'
                UNION ALL
                SELECT c.id, c.month, s.region_code, 'FINALIZE', c.finalize_deadline
                  FROM planning_cycle c JOIN cycle_region_status s ON s.cycle_id = c.id
                 WHERE c.state = 'REGIONAL_FINALIZATION' AND c.finalize_deadline IS NOT NULL AND s.finalize_status <> 'FINALIZED'
                """)
            .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, OffsetDateTime.class))).list();
        int sent = 0;
        for (Pending p : pending) {
            String kind = !now.isBefore(p.deadline()) ? "OVERDUE" : now.isAfter(p.deadline().minusHours(24)) ? "DUE_SOON" : null;
            if (kind != null && send(p, kind)) sent++;
        }
        return sent;
    }

    /**
     * Claims the reminder row and sends the notifications in one transaction: if two workers race, the primary key
     * lets only one insert succeed, and only that one notifies.
     *
     * @return true if this call sent the reminder
     */
    private boolean send(Pending p, String kind) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int claimed = jdbc.sql("""
                    INSERT INTO deadline_reminder (cycle_id, region_code, stage, kind, deadline) VALUES (:c, :r, :s, :k, :d)
                    ON CONFLICT DO NOTHING
                    """).param("c", p.cycleId()).param("r", p.region()).param("s", p.stage()).param("k", kind).param("d", p.deadline()).update();
            if (claimed == 0) return false;
            String action = p.stage().equals("REVIEW") ? "submit your forecast review" : "finalize your allocation";
            String when = p.deadline().atZoneSameInstant(zone).format(WHEN);
            if (kind.equals("DUE_SOON")) {
                notifications.notifyRole("regional_manager", p.region(), "Reminder: " + p.month() + " deadline " + when,
                    "Please " + action + " for " + p.region() + " before " + when + ".", p.cycleId());
            } else {
                notifications.notifyRole("regional_manager", p.region(), "Overdue: " + p.month() + " " + p.region(),
                    "The deadline (" + when + ") has passed. Please " + action + " as soon as possible.", p.cycleId());
                notifications.notifyRole("planner", null, "Overdue: " + p.region() + " for " + p.month(),
                    p.region() + " has not " + (p.stage().equals("REVIEW") ? "submitted its review" : "finalized") + " by " + when + ".", p.cycleId());
            }
            return true;
        }));
    }
}
