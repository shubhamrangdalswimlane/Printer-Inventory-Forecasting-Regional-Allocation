package com.pifa.notify;

import com.pifa.config.PifaProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the e-mails queued in {@code outbox_event} (the transactional outbox).
 *
 * <p>Active only when {@code pifa.mail.enabled=true}. Each run picks unsent rows (at most 5 attempts
 * each), sends them one by one and stamps {@code sent_at}; a failure increments {@code attempts} and the
 * row is retried on the next run. The recipient address is built from a template (development only; real
 * recipients will be resolved from Keycloak users later).
 */
@Component
@ConditionalOnProperty(name = "pifa.mail.enabled", havingValue = "true")
public class MailOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(MailOutboxPublisher.class);

    private record Pending(long id, String role, String region, String subject, String body) {}

    private final JdbcClient jdbc;
    private final JavaMailSender mail;
    private final PifaProperties props;
    @org.springframework.beans.factory.annotation.Value("${pifa.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    private final org.springframework.transaction.support.TransactionTemplate tx;

    public MailOutboxPublisher(JdbcClient jdbc, JavaMailSender mail, PifaProperties props, org.springframework.transaction.support.TransactionTemplate tx) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.mail = mail;
        this.props = props;
    }

    /** Scheduled entry point (every 30 s). Never throws. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledSend() {
        if (!jobsEnabled) return; // only worker pods send mail
        try {
            sendPending();
        } catch (RuntimeException e) {
            log.error("Mail outbox run failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Sends all unsent outbox rows once.
     *
     * @return number of mails sent successfully in this run
     */
    public int sendPending() {
        // One transaction per batch. FOR UPDATE SKIP LOCKED: with two worker replicas, each claims different rows,
        // so no mail is sent twice and neither waits for the other.
        Integer result = tx.execute(status -> sendBatch());
        return result == null ? 0 : result;
    }

    /** Claims up to 100 unsent rows (locked until commit), sends them, stamps them. Runs inside a transaction. */
    private int sendBatch() {
        List<Pending> rows = jdbc.sql("SELECT id, target_role, target_region, subject, body FROM outbox_event WHERE sent_at IS NULL AND attempts < 5 "
                + "ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED")
            .query((rs, n) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).list();
        int sent = 0;
        for (Pending p : rows) {
            try {
                String target = p.region() == null ? p.role() : p.role() + "-" + p.region().toLowerCase();
                SimpleMailMessage m = new SimpleMailMessage();
                m.setFrom(props.mail().from());
                m.setTo(props.mail().addressTemplate().replace("{target}", target));
                m.setSubject("[PIFA] " + p.subject());
                m.setText(p.body() == null ? p.subject() : p.body());
                mail.send(m);
                jdbc.sql("UPDATE outbox_event SET sent_at = now(), attempts = attempts + 1 WHERE id = :id").param("id", p.id()).update();
                sent++;
            } catch (RuntimeException e) {
                log.warn("Mail {} failed: {}", p.id(), e.getMessage());
                jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1 WHERE id = :id").param("id", p.id()).update();
            }
        }
        return sent;
    }
}
