package com.pifa.dataset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL for planning cycles and dataset versions. Services contain the rules; this class only
 * reads and writes rows. Methods that change data expect to run inside the caller's transaction.
 */
@Repository
public class DatasetRepository {

    /** A planning cycle row. */
    public record Cycle(UUID id, String month, String state) {}

    /**
     * A dataset version row.
     *
     * @param cycleMonth month of the owning cycle, e.g. 2026-11 (joined for display)
     * @param active     true if this is the single active version of its cycle and type
     */
    public record VersionRow(UUID id, UUID cycleId, String cycleMonth, DatasetType type, int versionNo, String label,
                             String source, String status, boolean active, String originalFilename, String objectKey,
                             String sha256, int rowCount, String createdBy, OffsetDateTime createdAt,
                             String deletedBy, OffsetDateTime deletedAt, String deleteReason) {}

    private static final String VERSION_SELECT = """
        SELECT v.id, v.cycle_id, c.month, v.type, v.version_no, v.label, v.source, v.status, v.is_active,
               v.original_filename, v.object_key, v.sha256, v.row_count, v.created_by, v.created_at,
               v.deleted_by, v.deleted_at, v.delete_reason
        FROM dataset_version v JOIN planning_cycle c ON c.id = v.cycle_id
        """;

    private final JdbcClient jdbc;

    public DatasetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static VersionRow map(ResultSet rs, int n) throws SQLException {
        return new VersionRow(rs.getObject("id", UUID.class), rs.getObject("cycle_id", UUID.class), rs.getString("month"),
            DatasetType.valueOf(rs.getString("type")), rs.getInt("version_no"), rs.getString("label"),
            rs.getString("source"), rs.getString("status"), rs.getBoolean("is_active"), rs.getString("original_filename"),
            rs.getString("object_key"), rs.getString("sha256"), rs.getInt("row_count"), rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getString("deleted_by"),
            rs.getObject("deleted_at", OffsetDateTime.class), rs.getString("delete_reason"));
    }

    /** @param month YYYY-MM @return the cycle, if one exists */
    public Optional<Cycle> findCycleByMonth(String month) {
        return jdbc.sql("SELECT id, month, state FROM planning_cycle WHERE month = :m").param("m", month)
            .query((rs, n) -> new Cycle(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional();
    }

    /** @param id cycle id @return the cycle, if it exists */
    public Optional<Cycle> findCycle(UUID id) {
        return jdbc.sql("SELECT id, month, state FROM planning_cycle WHERE id = :id").param("id", id)
            .query((rs, n) -> new Cycle(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional();
    }

    /** @return all cycles, newest month first */
    public List<Cycle> listCycles() {
        return jdbc.sql("SELECT id, month, state FROM planning_cycle ORDER BY month DESC")
            .query((rs, n) -> new Cycle(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
    }

    /** @return cycles that are not FINAL (inventory files without a Planning_Month attach to the only open cycle) */
    public List<Cycle> openCycles() {
        return jdbc.sql("SELECT id, month, state FROM planning_cycle WHERE state <> 'FINAL' ORDER BY month")
            .query((rs, n) -> new Cycle(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
    }

    /**
     * Returns the cycle for a month, creating it if needed, and locks the row until the transaction
     * ends so concurrent uploads for the same cycle number their versions one after another.
     *
     * @param month YYYY-MM
     * @return the locked cycle
     */
    public Cycle lockOrCreateCycle(String month) {
        jdbc.sql("INSERT INTO planning_cycle (id, month) VALUES (:id, :m) ON CONFLICT (month) DO NOTHING")
            .param("id", UUID.randomUUID()).param("m", month).update();
        return jdbc.sql("SELECT id, month, state FROM planning_cycle WHERE month = :m FOR UPDATE").param("m", month)
            .query((rs, n) -> new Cycle(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).single();
    }

    /**
     * @param cycleId the cycle
     * @param type    dataset type
     * @return the next version number (highest existing + 1, starting at 1)
     */
    public int nextVersionNo(UUID cycleId, DatasetType type) {
        return jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM dataset_version WHERE cycle_id = :c AND type = :t")
            .param("c", cycleId).param("t", type.name()).query(Integer.class).single();
    }

    /** Inserts a new version row with status VALID (not yet active). */
    public void insertVersion(UUID id, UUID cycleId, DatasetType type, int versionNo, String label, String source,
                              String filename, String objectKey, String sha256, int rowCount, String createdBy) {
        jdbc.sql("""
                INSERT INTO dataset_version (id, cycle_id, type, version_no, label, source, status, original_filename,
                                             object_key, sha256, row_count, created_by)
                VALUES (:id, :c, :t, :n, :l, :s, 'VALID', :f, :k, :h, :r, :u)
                """)
            .param("id", id).param("c", cycleId).param("t", type.name()).param("n", versionNo).param("l", label)
            .param("s", source).param("f", filename).param("k", objectKey).param("h", sha256).param("r", rowCount)
            .param("u", createdBy).update();
    }

    /**
     * Makes a version the single active one for its cycle and type: the previous active version
     * becomes SUPERSEDED first (the partial unique index allows only one active row at a time).
     *
     * @param versionId the version to activate
     */
    public void activate(UUID versionId) {
        jdbc.sql("""
                UPDATE dataset_version SET is_active = FALSE, status = 'SUPERSEDED'
                WHERE is_active AND (cycle_id, type) = (SELECT cycle_id, type FROM dataset_version WHERE id = :id)
                """).param("id", versionId).update();
        jdbc.sql("UPDATE dataset_version SET is_active = TRUE, status = 'ACTIVE' WHERE id = :id").param("id", versionId).update();
    }

    /** @param id version id @return the version, if it exists */
    public Optional<VersionRow> findVersion(UUID id) {
        return jdbc.sql(VERSION_SELECT + " WHERE v.id = :id").param("id", id).query(DatasetRepository::map).optional();
    }

    /**
     * Lists versions, newest first.
     *
     * @param type           restrict to one type, or null for all
     * @param cycleId        restrict to one cycle, or null for all
     * @param includeDeleted true to include DELETED and PURGED versions (Admin only; callers enforce that)
     * @return the matching versions
     */
    public List<VersionRow> list(DatasetType type, UUID cycleId, boolean includeDeleted) {
        StringBuilder sql = new StringBuilder(VERSION_SELECT).append(" WHERE 1=1");
        List<Object[]> params = new ArrayList<>();
        if (type != null) { sql.append(" AND v.type = :type"); params.add(new Object[]{"type", type.name()}); }
        if (cycleId != null) { sql.append(" AND v.cycle_id = :cycle"); params.add(new Object[]{"cycle", cycleId}); }
        if (!includeDeleted) sql.append(" AND v.status NOT IN ('DELETED','PURGED')");
        sql.append(" ORDER BY c.month DESC, v.type, v.version_no DESC");
        var spec = jdbc.sql(sql.toString());
        for (Object[] p : params) spec = spec.param((String) p[0], p[1]);
        return spec.query(DatasetRepository::map).list();
    }

    /** @param versionId a snapshot version @return true if any later snapshot names it as its parent (it is "referenced") */
    public boolean isReferenced(UUID versionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dataset_version WHERE parent_version_id = :id)")
            .param("id", versionId).query(Boolean.class).single();
    }

    /** Marks a version DELETED (soft delete) and records who, when and why. */
    public void softDelete(UUID id, String user, String reason) {
        jdbc.sql("UPDATE dataset_version SET status = 'DELETED', deleted_by = :u, deleted_at = now(), delete_reason = :r WHERE id = :id")
            .param("u", user).param("r", reason).param("id", id).update();
    }

    /** Reverses a soft delete: the version returns to VALID (never active). */
    public void undelete(UUID id) {
        jdbc.sql("UPDATE dataset_version SET status = 'VALID', deleted_by = NULL, deleted_at = NULL, delete_reason = NULL WHERE id = :id")
            .param("id", id).update();
    }

    /** Hard-removes the lines and file reference of a version but keeps the row as a PURGED tombstone. */
    public void purge(UUID id) {
        jdbc.sql("DELETE FROM forecast_line WHERE version_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM inventory_line WHERE version_id = :id").param("id", id).update();
        jdbc.sql("UPDATE dataset_version SET status = 'PURGED', object_key = NULL WHERE id = :id").param("id", id).update();
    }
}
