package com.pifa.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pifa.TestData;
import com.pifa.admin.AdminInsightService;
import com.pifa.allocation.AllocationService;
import com.pifa.allocation.TransferService;
import com.pifa.common.ApiException;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetType;
import com.pifa.security.CurrentUser;
import com.pifa.synthetic.SyntheticDataService;
import com.pifa.workflow.CycleWorkflowService.TransitionRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 4 end to end against PostgreSQL: approved allocation -> transfers -> finalization -> FINAL,
 * reopen paths, traceability, and the Admin overview/analytics over the result.
 * The PRD worked example is planted on one product: 25/25/25/25, East gives North 5 -> 30/20/25/25, total 100.
 */
@SpringBootTest
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FinalizationFlowTest {

    private static final String MONTH = "2034-03";
    private static final CurrentUser ADMIN = new CurrentUser("alex.admin", Set.of("admin"));
    private static final CurrentUser FORECASTER = new CurrentUser("fiona.forecaster", Set.of("forecaster"));
    private static final CurrentUser PLANNER = new CurrentUser("pat.planner", Set.of("planner"));
    private static final CurrentUser N = new CurrentUser("anita.north", Set.of("regional_manager"), Set.of("NORTH"));
    private static final CurrentUser E = new CurrentUser("evan.east", Set.of("regional_manager"), Set.of("EAST"));
    private static final CurrentUser W = new CurrentUser("wendy.west", Set.of("regional_manager"), Set.of("WEST"));
    private static final CurrentUser S = new CurrentUser("sam.south", Set.of("regional_manager"), Set.of("SOUTH"));
    private static final CurrentUser EAST_WEST = new CurrentUser("ewa.multi", Set.of("regional_manager"), Set.of("EAST", "WEST"));

    @MockBean JwtDecoder jwtDecoder;
    @Autowired SyntheticDataService synthetic;
    @Autowired CycleWorkflowService workflow;
    @Autowired AllocationService allocation;
    @Autowired TransferService transfers;
    @Autowired AdminInsightService insight;
    @Autowired DatasetRepository repo;
    @Autowired JdbcClient jdbc;

    private static UUID modelA;
    private static UUID other;

    @BeforeAll
    static void clean(@Autowired SyntheticDataService synthetic, @Autowired JdbcClient jdbc, @Autowired TransactionTemplate tx) {
        TestData.wipeCycles(tx, jdbc, MONTH);
        synthetic.purgeAll(ADMIN);
    }

    /** Cycle to REGIONAL_FINALIZATION with Model A approved at 25/25/25/25. */
    @Test @Order(1)
    void reachFinalization() {
        synthetic.generate(MONTH, 3, ADMIN);
        UUID c = cycle();
        workflow.transition(c, t("PUBLISH", null), FORECASTER);
        for (var u : List.of(N, E, W, S)) workflow.transition(c, t("SUBMIT_REGION", u.regions().iterator().next()), u);
        List<UUID> products = jdbc.sql("SELECT DISTINCT l.product_id, p.product_name FROM plan_line l JOIN product p ON p.id = l.product_id WHERE l.cycle_id = :c ORDER BY p.product_name")
            .param("c", c).query((rs, n) -> rs.getObject(1, UUID.class)).list();
        modelA = products.get(0);
        other = products.get(1);
        Map.of("NORTH", 40, "EAST", 35, "WEST", 30, "SOUTH", 25).forEach((r, q) -> jdbc.sql("UPDATE plan_line SET revised_qty = :q WHERE cycle_id = :c AND product_id = :p AND region_code = :r")
            .param("q", q).param("c", c).param("p", modelA).param("r", r).update());
        jdbc.sql("UPDATE plan_product_stock SET available_qty = 100, reserve_qty = 0 WHERE cycle_id = :c AND product_id = :p").param("c", c).param("p", modelA).update();
        // Give the second product plenty of stock so East has units to rebalance later.
        jdbc.sql("UPDATE plan_product_stock SET available_qty = 100000 WHERE cycle_id = :c AND product_id = :p").param("c", c).param("p", other).update();
        allocation.propose(c, true, PLANNER);
        var d = workflow.transition(c, t("APPROVE_ALLOCATION", null), PLANNER);
        assertThat(d.cycle().state()).isEqualTo("REGIONAL_FINALIZATION");
        assertThat(finals(c, modelA)).containsExactly(25, 25, 25, 25);
    }

    /** PRD worked example: North requests 5 from East; only East (or the Planner) can accept; result 30/20/25/25, total still 100. */
    @Test @Order(2)
    void workedExampleTransfer() {
        UUID c = cycle();
        var out = transfers.create(c, modelA, "EAST", "NORTH", 5, "key account", N);
        assertThat(out.transfer().kind()).isEqualTo("REQUEST");
        assertThat(out.transfer().status()).isEqualTo("REQUESTED");
        UUID id = out.transfer().id();
        assertThatThrownBy(() -> transfers.accept(id, null, N)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        transfers.accept(id, "ok", E);
        assertThat(finals(c, modelA)).containsExactly(30, 20, 25, 25);
        assertThat(finals(c, modelA).stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
        assertThatThrownBy(() -> transfers.accept(id, null, E)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_PENDING"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM change_log WHERE cycle_id = :c AND field = 'FINAL' AND reason LIKE :r").param("c", c).param("r", "transfer " + id + "%")
            .query(Long.class).single()).isEqualTo(2);

        // "Move now": North's manager adds 3 units to North and reduces East at once, without East accepting.
        var now = transfers.create(c, modelA, "EAST", "NORTH", 3, "walk-in order", N, true);
        assertThat(now.transfer().kind()).isEqualTo("DIRECT");
        assertThat(now.transfer().status()).isEqualTo("ACCEPTED");
        assertThat(finals(c, modelA)).containsExactly(33, 17, 25, 25);
        assertThat(finals(c, modelA).stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
        // East's manager is told; the rules still bind (East cannot give more than it has).
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE cycle_id = :c AND target_region = 'EAST' AND title LIKE 'Allocation changed:%'")
            .param("c", c).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> transfers.create(c, modelA, "EAST", "NORTH", 18, null, N, true)).isInstanceOf(ApiException.class);
        // A manager of neither region cannot move units at all.
        assertThatThrownBy(() -> transfers.create(c, modelA, "EAST", "NORTH", 1, null, W, true))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        // Put the 3 units back (North reduces itself, East adds), so the tests that follow start from 30/20/25/25.
        transfers.create(c, modelA, "NORTH", "EAST", 3, "undo", N, true);
        assertThat(finals(c, modelA)).containsExactly(30, 20, 25, 25);
    }

    /** The transfer rules refuse what the donor cannot spare, and touching a finalized region (BR-TR-01, BR-TR-03). */
    @Test @Order(3)
    void transferRulesAreEnforced() {
        UUID c = cycle();
        assertThatThrownBy(() -> transfers.create(c, modelA, "WEST", "NORTH", 26, null, W))
            .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.getStatus().value()).isEqualTo(422); assertThat(e.getMessage()).contains("BR-TR-01"); });
        assertThatThrownBy(() -> transfers.create(c, modelA, "WEST", "NORTH", 1, null, S))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REGION_FORBIDDEN"));
        workflow.transition(c, t("FINALIZE_REGION", "SOUTH"), S);
        assertThatThrownBy(() -> transfers.create(c, modelA, "SOUTH", "NORTH", 1, null, N))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("BR-TR-03"));
        // Un-finalize and finalize again is allowed while the cycle is not FINAL
        workflow.transition(c, t("UNFINALIZE_REGION", "SOUTH"), S);
        workflow.transition(c, t("FINALIZE_REGION", "SOUTH"), S);
        assertThatThrownBy(() -> workflow.transition(c, t("FINALIZE_REGION", "SOUTH"), N))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REGION_FORBIDDEN"));
    }

    /** A pending transfer blocks finalizing (BR-WF-03); the Planner can decide on a region's behalf; the creator can cancel. */
    @Test @Order(4)
    void pendingTransfersBlockFinalizeAndPlannerEscalates() {
        UUID c = cycle();
        UUID pending = transfers.create(c, modelA, "NORTH", "WEST", 2, "please", W).transfer().id();
        assertThatThrownBy(() -> workflow.transition(c, t("FINALIZE_REGION", "NORTH"), N))
            .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.getStatus().value()).isEqualTo(422); assertThat(e.getMessage()).contains("BR-WF-03"); });
        transfers.reject(pending, "escalated: keep North", PLANNER);
        assertThat(finals(c, modelA)).containsExactly(30, 20, 25, 25);

        UUID offer = transfers.create(c, modelA, "EAST", "WEST", 1, null, E).transfer().id();
        assertThatThrownBy(() -> transfers.cancel(offer, W)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        transfers.cancel(offer, E);
        workflow.transition(c, t("FINALIZE_REGION", "NORTH"), N);
    }

    /** A manager of two regions rebalances directly between them (FR-RM-10): applied at once, no acceptance step. */
    @Test @Order(5)
    void directRebalanceBetweenOwnRegions() {
        UUID c = cycle();
        List<Integer> before = finals(c, other);
        var out = transfers.create(c, other, "EAST", "WEST", 1, "rebalance", EAST_WEST);
        assertThat(out.transfer().kind()).isEqualTo("DIRECT");
        assertThat(out.transfer().status()).isEqualTo("ACCEPTED");
        List<Integer> after = finals(c, other);
        assertThat(after.get(1)).isEqualTo(before.get(1) - 1);
        assertThat(after.get(2)).isEqualTo(before.get(2) + 1);
    }

    /** When the last region finalizes the cycle becomes FINAL with an immutable ALLOCATION_FINAL snapshot holding the worked example. */
    @Test @Order(6)
    void lastFinalizeMakesCycleFinal() {
        UUID c = cycle();
        workflow.transition(c, t("FINALIZE_REGION", "EAST"), E);
        assertThat(workflow.detail(c).cycle().state()).isEqualTo("REGIONAL_FINALIZATION");
        var d = workflow.transition(c, t("FINALIZE_REGION", "WEST"), W);
        assertThat(d.cycle().state()).isEqualTo("FINAL");
        UUID fin = repo.latestVersionId(c, DatasetType.ALLOCATION_FINAL).orElseThrow();
        List<Integer> snap = jdbc.sql("SELECT a.qty FROM allocation_line a JOIN region r ON r.code = a.region_code WHERE a.version_id = :v AND a.product_id = :p ORDER BY r.display_order")
            .param("v", fin).param("p", modelA).query(Integer.class).list();
        assertThat(snap).containsExactly(30, 20, 25, 25);
        assertThatThrownBy(() -> transfers.create(c, modelA, "EAST", "NORTH", 1, null, N)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("WRONG_STATE"));
    }

    /**
     * Traceability (Phase 4 exit criterion): from a final number back to the source file version, the rules and
     * parameters that proposed it, the Planner's edits and the transfers - each with user, time and reason.
     */
    @Test @Order(7)
    void finalNumberIsTraceable() {
        UUID c = cycle();
        UUID fin = repo.latestVersionId(c, DatasetType.ALLOCATION_FINAL).orElseThrow();
        LinkRow f = link(fin);
        LinkRow approved = link(f.parent());
        assertThat(jdbc.sql("SELECT type FROM dataset_version WHERE id = :v").param("v", f.parent()).query(String.class).single()).isEqualTo("ALLOCATION_APPROVED");
        assertThat(jdbc.sql("SELECT type || '/' || scope FROM dataset_version WHERE id = :v").param("v", approved.parent()).query(String.class).single()).isEqualTo("FORECAST_REVISED/ALL");
        UUID published = link(approved.parent()).parent();
        UUID forecastFile = link(published).parent();
        assertThat(jdbc.sql("SELECT type || '/' || source FROM dataset_version WHERE id = :v").param("v", forecastFile).query(String.class).single()).isEqualTo("FORECAST_UPLOAD/SYNTHETIC");
        assertThat(jdbc.sql("SELECT type FROM dataset_version WHERE id = :v").param("v", f.inventory()).query(String.class).single()).isEqualTo("INVENTORY_UPLOAD");
        assertThat(f.ruleset()).isNotBlank();
        assertThat(f.params()).isNotNull();
        // The transfer that moved North from 25 to 30, with who and why
        assertThat(jdbc.sql("SELECT actor || '|' || old_value || '->' || new_value || '|' || reason FROM change_log WHERE cycle_id = :c AND product_id = :p AND region_code = 'NORTH' AND field = 'FINAL'")
            .param("c", c).param("p", modelA).query(String.class).list().stream().filter(s -> s.endsWith("key account")).toList())
            .singleElement().satisfies(s -> assertThat(s).startsWith("evan.east|25->30|transfer "));
    }

    /** Admin reopens FINAL (reason required, high-severity audit); the Planner can then reopen the allocation, cancelling pending transfers. */
    @Test @Order(8)
    void reopenPaths() {
        UUID c = cycle();
        assertThatThrownBy(() -> workflow.transition(c, new TransitionRequest("REOPEN_CYCLE", null, null, " ", null, null), ADMIN))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("COMMENT_REQUIRED"));
        assertThatThrownBy(() -> workflow.transition(c, new TransitionRequest("REOPEN_CYCLE", null, null, "fix", null, null), PLANNER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        var d = workflow.transition(c, new TransitionRequest("REOPEN_CYCLE", null, null, "wrong stock figure for Model A", null, null), ADMIN);
        assertThat(d.cycle().state()).isEqualTo("REGIONAL_FINALIZATION");
        assertThat(d.regions()).allMatch(r -> r.finalizeStatus().equals("OPEN"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'SECURITY_HIGH_SEVERITY' AND entity_id = :c").param("c", c.toString()).query(Long.class).single()).isEqualTo(1);

        transfers.create(c, modelA, "NORTH", "EAST", 1, null, E); // pending
        var p = workflow.transition(c, new TransitionRequest("REOPEN_ALLOCATION", null, null, "re-plan after stock fix", null, null), PLANNER);
        assertThat(p.cycle().state()).isEqualTo("PLANNING");
        assertThat(transfers.pendingInCycle(c)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM plan_line WHERE cycle_id = :c AND final_alloc_qty IS NOT NULL").param("c", c).query(Long.class).single()).isZero();
    }

    /** Admin overview and analytics read the same data: transfer flows, stage times, fill rate. */
    @Test @Order(9)
    void adminOverviewAndAnalytics() {
        UUID c = cycle();
        Map<String, Object> o = insight.overview(c);
        assertThat(o).containsKeys("cycle", "issues", "system");
        List<Map<String, Object>> a = insight.analytics(MONTH, MONTH);
        assertThat(a).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flows = (List<Map<String, Object>>) a.get(0).get("transferFlows");
        // 5 units by accepted request + 3 moved directly (then 3 moved back the other way) in workedExampleTransfer
        assertThat(flows).anyMatch(f -> f.get("from").equals("EAST") && f.get("to").equals("NORTH") && ((Number) f.get("units")).intValue() == 8);
        assertThat(flows).anyMatch(f -> f.get("from").equals("NORTH") && f.get("to").equals("EAST") && ((Number) f.get("units")).intValue() == 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> hours = (Map<String, Object>) a.get(0).get("stageHours");
        assertThat(hours.get("regionalReview")).isNotNull();
        assertThat(hours.get("finalization")).isNotNull();
        assertThat(a.get(0).get("constrainedProducts")).isNotNull();
    }

    // ---------------------------------------------------------------- helpers

    private record LinkRow(UUID parent, UUID inventory, String ruleset, Integer params) {}

    private UUID cycle() { return repo.findCycleByMonth(MONTH).orElseThrow().id(); }

    private static TransitionRequest t(String action, String region) { return new TransitionRequest(action, region, null, null, null, null); }

    private List<Integer> finals(UUID c, UUID product) {
        return jdbc.sql("SELECT l.final_alloc_qty FROM plan_line l JOIN region r ON r.code = l.region_code WHERE l.cycle_id = :c AND l.product_id = :p ORDER BY r.display_order")
            .param("c", c).param("p", product).query(Integer.class).list();
    }

    /** The traceability columns of a version: parent, inventory version, ruleset and parameter versions. */
    private LinkRow link(UUID version) {
        return jdbc.sql("SELECT parent_version_id, inventory_version_id, ruleset_version, parameter_version FROM dataset_version WHERE id = :v").param("v", version)
            .query((rs, n) -> new LinkRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), (Integer) rs.getObject(4))).single();
    }
}
