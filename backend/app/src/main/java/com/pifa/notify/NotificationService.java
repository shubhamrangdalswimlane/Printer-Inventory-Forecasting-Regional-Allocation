package com.pifa.notify;

import com.pifa.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications plus the e-mail outbox (PRD FR-COM-06).
 *
 * <p>A notification is addressed to a <em>role</em>, optionally narrowed to one <em>region</em>
 * (for example "every Regional Manager of North"). Creating one also writes an outbox row in the
 * same transaction, so the e-mail is sent later and only if the business change committed.
 */
@Service
public class NotificationService {

    /** One notification as shown to a user. */
    public record NotificationRow(long id, String title, String body, UUID cycleId, OffsetDateTime createdAt, boolean unread) {}

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates a notification and its e-mail outbox entry.
     *
     * @param role    target role, lower case (e.g. {@code regional_manager})
     * @param region  region code to narrow the audience, or null for every holder of the role
     * @param title   short headline (also the e-mail subject)
     * @param body    details (also the e-mail body); may be null
     * @param cycleId the planning cycle the message is about; may be null
     */
    @Transactional
    public void notifyRole(String role, String region, String title, String body, UUID cycleId) {
        jdbc.sql("INSERT INTO notification (target_role, target_region, title, body, cycle_id) VALUES (:r, :g, :t, :b, :c)")
            .param("r", role).param("g", region).param("t", title).param("b", body).param("c", cycleId).update();
        jdbc.sql("INSERT INTO outbox_event (target_role, target_region, subject, body) VALUES (:r, :g, :t, :b)")
            .param("r", role).param("g", region).param("t", title).param("b", body).update();
    }

    /**
     * Lists the newest notifications visible to a user: those addressed to one of their roles (and,
     * when region-narrowed, to one of their regions). Admin sees everything, for oversight.
     *
     * @param user  the caller
     * @param limit maximum rows (newest first)
     * @return the notifications with an unread flag for this user
     */
    public List<NotificationRow> forUser(CurrentUser user, int limit) {
        List<String> roles = new ArrayList<>(user.roles());
        List<String> regions = new ArrayList<>(user.regions());
        // An empty IN () is invalid SQL, so a sentinel that matches nothing is added.
        if (roles.isEmpty()) roles.add("-");
        if (regions.isEmpty()) regions.add("-");
        return jdbc.sql("""
                SELECT n.id, n.title, n.body, n.cycle_id, n.created_at, (r.username IS NULL) AS unread
                FROM notification n LEFT JOIN notification_read r ON r.notification_id = n.id AND r.username = :u
                WHERE :admin OR (n.target_role IN (:roles) AND (n.target_region IS NULL OR n.target_region IN (:regions)))
                ORDER BY n.id DESC LIMIT :n
                """).param("u", user.username()).param("admin", user.has("admin")).param("roles", roles)
            .param("regions", regions).param("n", Math.min(Math.max(limit, 1), 200))
            .query((rs, i) -> new NotificationRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getObject(5, OffsetDateTime.class), rs.getBoolean(6))).list();
    }

    /**
     * Marks one notification as read for a user (idempotent).
     *
     * @param id   notification id
     * @param user the caller
     */
    public void markRead(long id, CurrentUser user) {
        jdbc.sql("INSERT INTO notification_read (notification_id, username) VALUES (:id, :u) ON CONFLICT DO NOTHING")
            .param("id", id).param("u", user.username()).update();
    }
}
