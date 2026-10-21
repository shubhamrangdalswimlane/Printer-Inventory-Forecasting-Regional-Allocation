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
 * Changing or deleting an old row breaks every later hash, which {@link AuditChainVerifier} detects;
 * the database also refuses UPDATE and DELETE on the table outright. Writes run in the caller's
 * transaction, so an audit entry exists if and only if the change committed.
 *
 * <p>Hash version 2: the detail is first normalised by PostgreSQL ({@code CAST(... AS jsonb)::text}),
 * exactly as it will be read back later, so the hash can be recomputed from the stored row.
 */
@Service
public class AuditService {

    /** 64 zeros: the "previous hash" of the very first row. */
    static final String GENESIS = "0".repeat(64);
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
        // Normalise the JSON the way PostgreSQL stores it, so the hash can be recomputed later.
        String detailJson = jdbc.sql("SELECT CAST(:d AS jsonb)::text").param("d", toJson(detail)).query(String.class).single();
        String hash = hash(prev, at, actor, action, entityType, entityId, detailJson);
        jdbc.sql("""
                INSERT INTO audit_log (at, actor, action, entity_type, entity_id, detail, prev_hash, hash, hash_version)
                VALUES (:at, :actor, :action, :type, :id, CAST(:detail AS jsonb), :prev, :hash, 2)
                """)
            .param("at", at).param("actor", actor).param("action", action).param("type", entityType)
            .param("id", entityId).param("detail", detailJson).param("prev", prev).param("hash", hash).update();
    }

    /**
     * The hash formula (version 2), shared with the verifier.
     *
     * @return lower-case hex SHA-256 of {@code prev|at|actor|action|type|id|detail}
     */
    static String hash(String prev, OffsetDateTime at, String actor, String action, String entityType, String entityId, String detailJson) {
        return sha256(prev + "|" + at.withOffsetSameInstant(ZoneOffset.UTC) + "|" + actor + "|" + action + "|" + entityType + "|" + entityId + "|" + detailJson);
    }

    /**
     * Serialises the detail, adding the request context when the change came through HTTP
     * (architecture.md 8.3: request id, client IP, user agent), so an audit row can be matched to logs.
     */
    private String toJson(Map<String, Object> detail) {
        Map<String, Object> d = new java.util.LinkedHashMap<>(detail == null ? Map.of() : detail);
        String requestId = org.slf4j.MDC.get("requestId");
        if (requestId != null) d.putIfAbsent("requestId", requestId);
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
            var req = sra.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            d.putIfAbsent("ip", forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr());
            String ua = req.getHeader("User-Agent");
            if (ua != null) d.putIfAbsent("userAgent", ua.length() > 200 ? ua.substring(0, 200) : ua);
        }
        try {
            return json.writeValueAsString(d);
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
