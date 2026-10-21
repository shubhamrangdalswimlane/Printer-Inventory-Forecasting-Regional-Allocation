package com.pifa.workflow;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Removes a planning cycle and everything that hangs off it: lines, snapshots, working copy, change
 * log, workflow events, notifications and ingestion jobs. Used by the synthetic-data purge and by test
 * clean-up; there is deliberately no API for it, because real cycles must never be erased.
 */
public final class CyclePurger {

    private CyclePurger() {}

    /**
     * Deletes one cycle completely.
     *
     * <p>Snapshot lines are protected by a database trigger; this method lifts that protection for the
     * current transaction only ({@code set_config(..., true)}), so it <b>must be called inside a
     * transaction</b>, otherwise the protection is not lifted and the delete fails.
     *
     * @param jdbc    JDBC access (must be bound to the caller's transaction)
     * @param cycleId the cycle to delete
     */
    public static void purge(JdbcClient jdbc, UUID cycleId) {
        jdbc.sql("SELECT set_config('pifa.allow_snapshot_delete', 'on', true)").query().singleRow();
        String versions = "SELECT id FROM dataset_version WHERE cycle_id = :c";
        jdbc.sql("DELETE FROM ingestion_job WHERE version_id IN (" + versions + ")").param("c", cycleId).update();
        jdbc.sql("DELETE FROM forecast_line WHERE version_id IN (" + versions + ")").param("c", cycleId).update();
        jdbc.sql("DELETE FROM inventory_line WHERE version_id IN (" + versions + ")").param("c", cycleId).update();
        jdbc.sql("UPDATE dataset_version SET parent_version_id = NULL WHERE cycle_id = :c").param("c", cycleId).update();
        jdbc.sql("DELETE FROM dataset_version WHERE cycle_id = :c").param("c", cycleId).update();
        jdbc.sql("DELETE FROM plan_line WHERE cycle_id = :c").param("c", cycleId).update();
        jdbc.sql("DELETE FROM change_log WHERE cycle_id = :c").param("c", cycleId).update();
        jdbc.sql("DELETE FROM workflow_event WHERE cycle_id = :c").param("c", cycleId).update();
        jdbc.sql("DELETE FROM cycle_region_status WHERE cycle_id = :c").param("c", cycleId).update();
        jdbc.sql("DELETE FROM notification WHERE cycle_id = :c").param("c", cycleId).update();
        jdbc.sql("DELETE FROM planning_cycle WHERE id = :c").param("c", cycleId).update();
    }
}
