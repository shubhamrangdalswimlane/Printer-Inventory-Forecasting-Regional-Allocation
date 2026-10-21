package com.pifa.forecast;

import com.pifa.audit.AuditService;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetType;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the editable working copy ({@code plan_line}) in step with the active forecast file.
 *
 * <p>Whenever a forecast upload becomes the active version of a cycle that is still in
 * FORECAST_DRAFT, the working copy is rebuilt from it. If the Forecaster had already edited the old
 * copy, those edits are first saved as an immutable {@code FORECAST_WORKING_SNAPSHOT} version, so no
 * edit is ever lost (PRD 8.5).
 */
@Service
public class WorkingCopyService {

    private final JdbcClient jdbc;
    private final DatasetRepository repo;
    private final AuditService audit;

    public WorkingCopyService(JdbcClient jdbc, DatasetRepository repo, AuditService audit) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.audit = audit;
    }

    /**
     * Rebuilds the working copy of a cycle from a forecast upload version.
     *
     * <p>Steps: (1) if the existing copy contains edits, snapshot it; (2) delete the old copy;
     * (3) insert one row per active product x active region, taking the quantity from the file and
     * using 0 for products or regions the file did not contain (PRD: missing = 0); (4) audit.
     * Must run inside the caller's transaction.
     *
     * @param cycleId   the cycle (must be in FORECAST_DRAFT; callers check this)
     * @param versionId the forecast upload version to load
     * @param actor     who triggered the load (recorded in the snapshot and audit log)
     */
    @Transactional
    public void loadFromVersion(UUID cycleId, UUID versionId, String actor) {
        boolean edited = jdbc.sql("SELECT EXISTS (SELECT 1 FROM plan_line WHERE cycle_id = :c AND published_qty <> uploaded_qty)")
            .param("c", cycleId).query(Boolean.class).single();
        if (edited) {
            UUID snap = UUID.randomUUID();
            int no = repo.nextVersionNo(cycleId, DatasetType.FORECAST_WORKING_SNAPSHOT);
            String month = repo.findCycle(cycleId).orElseThrow().month();
            int rows = jdbc.sql("SELECT COUNT(DISTINCT product_id) FROM plan_line WHERE cycle_id = :c").param("c", cycleId).query(Integer.class).single();
            repo.insertSnapshot(snap, cycleId, DatasetType.FORECAST_WORKING_SNAPSHOT, no, "FCW-" + month + "-v" + no, "ALL",
                repo.activeVersionId(cycleId, DatasetType.FORECAST_UPLOAD).orElse(null), rows, actor);
            jdbc.sql("INSERT INTO forecast_line (version_id, product_id, region_code, qty) SELECT :v, product_id, region_code, published_qty FROM plan_line WHERE cycle_id = :c")
                .param("v", snap).param("c", cycleId).update();
        }
        jdbc.sql("DELETE FROM plan_line WHERE cycle_id = :c").param("c", cycleId).update();
        int created = jdbc.sql("""
                INSERT INTO plan_line (id, cycle_id, product_id, region_code, uploaded_qty, published_qty)
                SELECT gen_random_uuid(), :c, p.id, r.code, COALESCE(f.qty, 0), COALESCE(f.qty, 0)
                FROM product p CROSS JOIN region r
                LEFT JOIN forecast_line f ON f.product_id = p.id AND f.region_code = r.code AND f.version_id = :v
                WHERE p.active AND r.active
                """).param("c", cycleId).param("v", versionId).update();
        audit.record(actor, "WORKING_COPY_LOADED", "planning_cycle", cycleId.toString(),
            Map.of("versionId", versionId.toString(), "lines", created, "snapshottedEdits", edited));
    }
}
