package com.pifa;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Test helper: removes everything belonging to given cycle months so tests can be re-run on a dirty database. */
public final class TestData {

    private TestData() {}

    /**
     * Deletes ingestion jobs, lines, versions and the cycle row for each month.
     * (Audit rows are never deleted: the audit log is append-only by design.)
     *
     * @param jdbc   JDBC access
     * @param months cycle months such as {@code 2031-01}
     */
    public static void wipeCycles(JdbcClient jdbc, String... months) {
        for (String m : months) {
            String versions = "SELECT v.id FROM dataset_version v JOIN planning_cycle c ON c.id = v.cycle_id WHERE c.month = :m";
            jdbc.sql("DELETE FROM ingestion_job WHERE version_id IN (" + versions + ")").param("m", m).update();
            jdbc.sql("DELETE FROM forecast_line WHERE version_id IN (" + versions + ")").param("m", m).update();
            jdbc.sql("DELETE FROM inventory_line WHERE version_id IN (" + versions + ")").param("m", m).update();
            jdbc.sql("DELETE FROM dataset_version WHERE cycle_id IN (SELECT id FROM planning_cycle WHERE month = :m)").param("m", m).update();
            jdbc.sql("DELETE FROM planning_cycle WHERE month = :m").param("m", m).update();
        }
    }
}
