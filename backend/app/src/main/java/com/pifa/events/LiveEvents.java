package com.pifa.events;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live refresh for the dashboards (Phase 4): when something changes in a cycle, every open browser gets a
 * small Server-Sent Event and reloads the affected panels, so a Regional Manager sees a transfer request
 * arrive without pressing refresh.
 *
 * <p>Fan-out uses PostgreSQL {@code LISTEN/NOTIFY}:
 * <ul>
 *   <li>{@link #publish} runs {@code pg_notify} inside the caller's transaction, so PostgreSQL delivers the
 *       event only if the change commits (never for a rolled-back change);</li>
 *   <li>every API instance LISTENs on one dedicated connection and forwards events to its own browsers, so it
 *       works with several replicas behind a load balancer without any extra infrastructure.</li>
 * </ul>
 * Events carry no business data, only {@code {"cycleId": ..., "type": ...}}; clients refetch through the
 * normal, authorised API.
 */
@Component
public class LiveEvents {

    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);
    private static final String CHANNEL = "pifa_events";

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private Thread listener;

    public LiveEvents(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * Announces a change. Inside a transaction the event is delivered only on commit.
     *
     * @param cycleId the cycle that changed (may be null for global changes)
     * @param type    what changed, e.g. TRANSITION, EDIT, TRANSFER, ALLOCATION, DATASET
     */
    public void publish(UUID cycleId, String type) {
        String payload = "{\"cycleId\":" + (cycleId == null ? "null" : "\"" + cycleId + "\"") + ",\"type\":\"" + type.replaceAll("[^A-Z_]", "") + "\"}";
        jdbc.sql("SELECT pg_notify(:ch, :p)").param("ch", CHANNEL).param("p", payload).query().singleRow();
    }

    /**
     * Opens a stream for one browser.
     *
     * @return the emitter Spring keeps open; it is removed when the browser disconnects or after 30 minutes
     *         (the client reconnects automatically)
     */
    public SseEmitter subscribe() {
        SseEmitter e = new SseEmitter(30 * 60 * 1000L);
        emitters.add(e);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(x -> emitters.remove(e));
        try {
            e.send(SseEmitter.event().name("hello").data("{}"));
        } catch (IOException ex) {
            emitters.remove(e);
        }
        return e;
    }

    /** @return number of connected browsers on this instance (for monitoring) */
    public int connections() { return emitters.size(); }

    /** Sends one event to every connected browser of this instance; dead connections are dropped. */
    void broadcast(String payload) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("change").data(payload));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    /** Every 25 s a comment line keeps idle connections open through proxies and load balancers. */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    /** Starts the background thread that LISTENs for notifications; it reconnects if the connection drops. */
    @PostConstruct
    void start() {
        listener = new Thread(this::listenLoop, "pifa-live-events");
        listener.setDaemon(true);
        listener.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (listener != null) listener.interrupt();
        emitters.forEach(SseEmitter::complete);
    }

    private void listenLoop() {
        while (running) {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("LISTEN " + CHANNEL);
                PGConnection pg = c.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] batch = pg.getNotifications(10_000); // waits up to 10 s
                    if (batch != null) for (PGNotification n : batch) broadcast(n.getParameter());
                }
            } catch (Exception e) {
                if (!running) return;
                log.warn("Live-event listener lost its connection, retrying in 5 s: {}", e.getMessage());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
