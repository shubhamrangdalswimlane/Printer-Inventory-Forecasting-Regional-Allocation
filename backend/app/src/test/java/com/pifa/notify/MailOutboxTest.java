package com.pifa.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

/**
 * Tests the e-mail outbox against a real SMTP server (Mailpit from docker-compose). Skipped unless
 * {@code PIFA_TEST_SMTP_HOST} (SMTP, port 1025) and {@code PIFA_TEST_MAILPIT_API} (HTTP base URL of
 * Mailpit, e.g. http://localhost:8025) are set.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "PIFA_TEST_SMTP_HOST", matches = ".+")
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
    "pifa.storage.dir=target/test-files",
    "pifa.mail.enabled=true",
    "spring.mail.host=${PIFA_TEST_SMTP_HOST:localhost}",
    "spring.mail.port=1025"
})
class MailOutboxTest {

    @MockBean JwtDecoder jwtDecoder;
    @Autowired NotificationService notifications;
    @Autowired MailOutboxPublisher publisher;
    @Autowired JdbcClient jdbc;

    /** A notification queues an outbox row; the publisher sends it and stamps sent_at; the mail arrives at the role+region address. */
    @Test
    void queuedNotificationIsDeliveredAndMarkedSent() throws Exception {
        String subject = "Outbox test " + UUID.randomUUID();
        notifications.notifyRole("regional_manager", "NORTH", subject, "Hello North", null);

        // The outbox is sent in batches of 100 and earlier tests may have queued many rows, so drain it.
        int sent = 0;
        for (int i = 0; i < 50; i++) {
            int n = publisher.sendPending();
            if (n == 0) break;
            sent += n;
        }
        assertThat(sent).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE subject = :s AND sent_at IS NOT NULL").param("s", subject).query(Long.class).single()).isEqualTo(1);

        HttpResponse<String> r = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(System.getenv("PIFA_TEST_MAILPIT_API") + "/api/v1/messages")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.body()).contains("[PIFA] " + subject).contains("regional_manager-north@example.test");

        // Nothing left to send for this row
        assertThat(publisher.sendPending()).isZero();
    }
}
