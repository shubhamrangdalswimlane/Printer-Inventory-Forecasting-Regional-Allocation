package com.pifa;

import com.pifa.workflow.CyclePurger;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Test helper: removes everything belonging to given cycle months so tests can be re-run on a dirty database. */
public final class TestData {

    private TestData() {}

    /**
     * Deletes each cycle completely (lines, snapshots, working copy, workflow history) in one transaction.
     * Audit rows are never deleted: the audit log is append-only by design.
     *
     * @param tx     transaction template (the purge must run inside a transaction)
     * @param jdbc   JDBC access
     * @param months cycle months such as {@code 2031-01}
     */
    public static void wipeCycles(TransactionTemplate tx, JdbcClient jdbc, String... months) {
        tx.executeWithoutResult(status -> {
            for (String m : months) {
                jdbc.sql("SELECT id FROM planning_cycle WHERE month = :m").param("m", m).query(UUID.class).optional()
                    .ifPresent(id -> CyclePurger.purge(jdbc, id));
            }
        });
    }
}
