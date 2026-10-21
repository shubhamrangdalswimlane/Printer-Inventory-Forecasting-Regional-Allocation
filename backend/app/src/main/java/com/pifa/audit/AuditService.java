package com.pifa.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only, hash-chained audit log (architecture.md section 8.3).
 *
 * <p>Every row stores {@code hash = sha256(prev_hash | at | actor | action | type | id | detail)}.
 * Changing or deleting an old row breaks every later hash, which a verification job can detect.
 * Writes run in the caller's transaction, so an audit entry exists if and only if the change committed.
 */
@Service
public class AuditService {

    /** 64 zeros: the "previous hash" of the very first row. */
    private static final String GENESIS = "0".repeat(64);
    /** Arbitrary constant key for the Postgres advisory lock that serialises audit writes. */
    private static final long LOCK_KEY = 7001L;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Appends one audit entry.
     *
     * <p>Takes a transaction-scoped advisory lock first so two concurrent writers cannot both read
     * the same "previous hash" and fork the chain; the lock is released automatically at commit.
     *
     * @param actor      who did it (Keycloak username, SFTP account or {@code system})
     * @param action     what happened, e.g. {@code UPLOAD}, {@code ACTIVATE}, {@code DELETE}
     * @param entityType kind of record affected, e.g. {@code dataset_version}
     * @param entityId   id of the record affected, or null
     * @param detail     extra facts (reason, filename, counts); serialised to JSON
     */
    @Transactional
    public void record(String actor, String action, String entityType, String entityId, Map<String, Object> detail) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", LOCK_KEY).query().singleRow();
        String prev = jdbc.sql("SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1")
            .query(String.class).optional().orElse(GENESIS);
        // Truncate to microseconds: that is what Postgres stores, so the hash input matches on re-read.
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String detailJson = toJson(detail);
        String hash = sha256(prev + "|" + at + "|" + actor + "|" + action + "|" + entityType + "|" + entityId + "|" + detailJson);
        jdbc.sql("""
                INSERT INTO audit_log (at, actor, action, entity_type, entity_id, detail, prev_hash, hash)
                VALUES (:at, :actor, :action, :type, :id, CAST(:detail AS jsonb), :prev, :hash)
                """)
            .param("at", at).param("actor", actor).param("action", action).param("type", entityType)
            .param("id", entityId).param("detail", detailJson).param("prev", prev).param("hash", hash).update();
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit detail is not serialisable", e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory in every JVM
        }
    }
}
