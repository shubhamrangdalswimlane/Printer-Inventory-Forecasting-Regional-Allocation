package com.pifa.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pifa.TestData;
import com.pifa.common.ApiException;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetType;
import com.pifa.forecast.ForecastEditService;
import com.pifa.forecast.ForecastEditService.EditRequest;
import com.pifa.ingestion.IngestionService;
import com.pifa.ingestion.IngestionService.IngestRequest;
import com.pifa.notify.NotificationService;
import com.pifa.parameters.RuleParameterService;
import com.pifa.security.CurrentUser;
import com.pifa.synthetic.SyntheticDataService;
import com.pifa.workflow.CycleWorkflowService;
import com.pifa.workflow.CycleWorkflowService.TransitionRequest;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
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
 * Phase 3 end to end against a real PostgreSQL: a cycle is taken to PLANNING, then the Planner works the
 * allocation. The PRD worked example (Model A, 100 units, demand 40/35/30/25) is planted on one product
 * so the numbers can be asserted exactly. Tests share state and run in order.
 */
@SpringBootTest
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AllocationFlowTest {

    private static final String MONTH = "2034-02";
    private static final CurrentUser ADMIN = new CurrentUser("alex.admin", Set.of("admin"));
    private static final CurrentUser FORECASTER = new CurrentUser("fiona.forecaster", Set.of("forecaster"));
    private static final CurrentUser PLANNER = new CurrentUser("pat.planner", Set.of("planner"));
    private static final CurrentUser RM_NORTH = new CurrentUser("anita.north", Set.of("regional_manager"), Set.of("NORTH"));
    private static final Map<String, CurrentUser> RMS = Map.of(
        "NORTH", RM_NORTH,
        "EAST", new CurrentUser("evan.east", Set.of("regional_manager"), Set.of("EAST")),
        "WEST", new CurrentUser("wendy.west", Set.of("regional_manager"), Set.of("WEST")),
        "SOUTH", new CurrentUser("sam.south", Set.of("regional_manager"), Set.of("SOUTH")));

    @MockBean JwtDecoder jwtDecoder;
    @Autowired SyntheticDataService synthetic;
    @Autowired CycleWorkflowService workflow;
    @Autowired AllocationService allocation;
    @Autowired StockService stock;
    @Autowired ForecastEditService edits;
    @Autowired RuleParameterService params;
    @Autowired IngestionService ingestion;
    @Autowired NotificationService notifications;
    @Autowired DatasetRepository repo;
    @Autowired JdbcClient jdbc;

    /** The product that carries the worked example. */
    private static UUID modelA;

    @BeforeAll
    static void clean(@Autowired SyntheticDataService synthetic, @Autowired JdbcClient jdbc, @Autowired TransactionTemplate tx) {
        TestData.wipeCycles(tx, jdbc, MONTH);
        synthetic.purgeAll(ADMIN);
    }

    /** Leave the global rule parameters as the PRD defaults for every other test. */
    @AfterAll
    static void restoreDefaults(@Autowired RuleParameterService params) {
        var cur = params.current().values();
        if (!cur.regionWeights().values().stream().allMatch(w -> w == 25) || cur.capAtDemand()) {
            params.save(new RuleParameterService.Values(new LinkedHashMap<>(Map.of("NORTH", 25, "EAST", 25, "WEST", 25, "SOUTH", 25)),
                20, 0, 3.0, false, true), null, "restore defaults after AllocationFlowTest", ADMIN);
        }
    }

    /** Upload, publish, four submissions: the cycle reaches PLANNING with stock applied from the inventory file. */
    @Test @Order(1)
    void cycleReachesPlanningWithStock() {
        synthetic.generate(MONTH, 1, ADMIN);
        UUID c = cycle();
        workflow.transition(c, new TransitionRequest("PUBLISH", null, null, null, null, null), FORECASTER);
        for (String r : List.of("NORTH", "EAST", "WEST", "SOUTH")) workflow.transition(c, new TransitionRequest("SUBMIT_REGION", r, null, null, null, null), RMS.get(r));
        assertThat(workflow.detail(c).cycle().state()).isEqualTo("PLANNING");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM plan_product_stock WHERE cycle_id = :c").param("c", c).query(Long.class).single()).isEqualTo(25);

        // Plant the PRD worked example on the first product: demand 40/35/30/25, 100 units, no reserve.
        modelA = jdbc.sql("SELECT l.product_id FROM plan_line l JOIN product p ON p.id = l.product_id WHERE l.cycle_id = :c ORDER BY p.product_name LIMIT 1")
            .param("c", c).query(UUID.class).single();
        Map<String, Integer> demand = Map.of("NORTH", 40, "EAST", 35, "WEST", 30, "SOUTH", 25);
        demand.forEach((r, q) -> jdbc.sql("UPDATE plan_line SET revised_qty = :q WHERE cycle_id = :c AND product_id = :p AND region_code = :r")
            .param("q", q).param("c", c).param("p", modelA).param("r", r).update());
        jdbc.sql("UPDATE plan_product_stock SET available_qty = 100, reserve_qty = 0 WHERE cycle_id = :c AND product_id = :p").param("c", c).param("p", modelA).update();
    }

    /** Only the Planner may run it; the proposal gives 25/25/25/25 for the worked example and records versions. */
    @Test @Order(2)
    void workedExampleProposal() {
        UUID c = cycle();
        assertThatThrownBy(() -> allocation.propose(c, true, FORECASTER)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        var r = allocation.propose(c, true, PLANNER);
        assertThat(r.products()).isEqualTo(25);
        assertThat(r.parameterVersion()).isEqualTo(params.current().version());
        assertThat(alloc(c, "approved_alloc_qty")).containsExactly(25, 25, 25, 25);
        assertThat(alloc(c, "proposed_alloc_qty")).containsExactly(25, 25, 25, 25);
        assertThat(jdbc.sql("SELECT classification || ' | ' || allocation_explanation FROM plan_product_stock WHERE cycle_id = :c AND product_id = :p")
            .param("c", c).param("p", modelA).query(String.class).single()).isEqualTo("CONSTRAINED | Constrained: weighted baseline 25/25/25/25");
        // Every product's approved total respects its allocatable stock
        assertThat(allocation.errorCount(c, "APPROVED")).isZero();
    }

    /** 102 units: the two rounding units go to North and East (largest unmet demand). */
    @Test @Order(3)
    void roundingRemainder() {
        UUID c = cycle();
        jdbc.sql("UPDATE plan_product_stock SET available_qty = 102 WHERE cycle_id = :c AND product_id = :p").param("c", c).param("p", modelA).update();
        allocation.propose(c, true, PLANNER);
        assertThat(alloc(c, "approved_alloc_qty")).containsExactly(26, 26, 25, 25);
        jdbc.sql("UPDATE plan_product_stock SET available_qty = 100 WHERE cycle_id = :c AND product_id = :p").param("c", c).param("p", modelA).update();
        allocation.propose(c, true, PLANNER);
    }

    /** Planner edits: a deviation needs a reason; a total above stock is an ERROR that blocks approval (BR-VA-01 / BR-WF-02). */
    @Test @Order(4)
    void overAllocationCannotBeApproved() {
        UUID c = cycle();
        Line north = line(c, "NORTH");
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(north.id, "APPROVED", 60, null, north.rowVersion)), PLANNER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REASON_REQUIRED"));
        edits.apply(c, List.of(new EditRequest(north.id, "APPROVED", 60, "key account launch", north.rowVersion)), PLANNER);

        assertThat(allocation.validate(c, "APPROVED")).anyMatch(v -> v.getCode().equals("BR-VA-01") && v.getSubject().equals(modelA.toString()));
        assertThatThrownBy(() -> workflow.transition(c, new TransitionRequest("APPROVE_ALLOCATION", null, null, null, null, null), PLANNER))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(422);
                assertThat(e.getMessage()).contains("BR-WF-02");
            });
        // Put it back within stock
        Line again = line(c, "NORTH");
        edits.apply(c, List.of(new EditRequest(again.id, "APPROVED", 25, null, again.rowVersion)), PLANNER);
        assertThat(allocation.errorCount(c, "APPROVED")).isZero();
        // The Regional Manager cannot touch the approved column
        Line n2 = line(c, "NORTH");
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(n2.id, "APPROVED", 20, "x", n2.rowVersion)), RM_NORTH))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    /** A rule-parameter change (weights 30/30/20/20) changes the next proposal without a deployment, and is audited. */
    @Test @Order(5)
    void parameterChangeAffectsNextProposal() {
        UUID c = cycle();
        assertThatThrownBy(() -> params.save(new RuleParameterService.Values(Map.of("NORTH", 30, "EAST", 30, "WEST", 20, "SOUTH", 10), 20, 0, 3.0, false, true), null, "x", ADMIN))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("total 100"));
        assertThatThrownBy(() -> params.save(new RuleParameterService.Values(Map.of("NORTH", 30, "EAST", 30, "WEST", 20, "SOUTH", 20), 20, 0, 3.0, false, true), null, "x", PLANNER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));

        var saved = params.save(new RuleParameterService.Values(new LinkedHashMap<>(Map.of("NORTH", 30, "EAST", 30, "WEST", 20, "SOUTH", 20)), 20, 0, 3.0, false, true),
            null, "pilot: weight by historical share", ADMIN);
        var r = allocation.propose(c, true, PLANNER);
        assertThat(r.parameterVersion()).isEqualTo(saved.version());
        assertThat(alloc(c, "approved_alloc_qty")).containsExactly(30, 30, 20, 20);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'RULE_PARAMETERS_CHANGED' AND entity_id = :v").param("v", String.valueOf(saved.version()))
            .query(Long.class).single()).isEqualTo(1);
    }

    /** A reserve lowers the allocatable stock and marks the proposal stale; preview shows the diff without saving. */
    @Test @Order(6)
    void reserveMakesProposalStaleAndPreviewShowsDiff() {
        UUID c = cycle();
        long rv = jdbc.sql("SELECT row_version FROM plan_product_stock WHERE cycle_id = :c AND product_id = :p").param("c", c).param("p", modelA).query(Long.class).single();
        assertThatThrownBy(() -> stock.setReserve(c, modelA, 10, " ", rv, PLANNER)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REASON_REQUIRED"));
        stock.setReserve(c, modelA, 10, "damaged units", rv, PLANNER);
        assertThatThrownBy(() -> stock.setReserve(c, modelA, 12, "again", rv, PLANNER)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        assertThat(allocation.summary(c)).containsEntry("stale", true);

        var preview = allocation.propose(c, false, PLANNER);
        assertThat(preview.saved()).isFalse();
        assertThat(preview.changes()).anyMatch(ch -> ch.productId().equals(modelA));
        assertThat(alloc(c, "approved_alloc_qty")).containsExactly(30, 30, 20, 20); // unchanged by a preview

        allocation.propose(c, true, PLANNER);
        assertThat(alloc(c, "approved_alloc_qty")).containsExactly(27, 27, 18, 18); // 90 x 30/30/20/20
        assertThat(allocation.summary(c)).containsEntry("stale", false);
    }

    /** A new inventory version during PLANNING is applied, marks the proposal stale and tells the Planner (FR-PL-09). */
    @Test @Order(7)
    void newInventoryDuringPlanningMarksStale() {
        UUID c = cycle();
        byte[] inv = synthetic.buildPair(MONTH, 7)[1];
        var res = ingestion.ingest(new IngestRequest(inv, "INVENTORY_new.xlsx", DatasetType.INVENTORY_UPLOAD, "UI", "pat.planner", "pat.planner"));
        assertThat(res.status()).isEqualTo("VALID");
        assertThat(res.activated()).isTrue();
        assertThat(allocation.summary(c)).containsEntry("stale", true);
        assertThat(notifications.forUser(PLANNER, 50)).anyMatch(n -> n.title().startsWith("Inventory changed"));
        // Reserve survives the new inventory
        assertThat(jdbc.sql("SELECT reserve_qty FROM plan_product_stock WHERE cycle_id = :c AND product_id = :p").param("c", c).param("p", modelA)
            .query(Integer.class).single()).isEqualTo(10);
        allocation.propose(c, true, PLANNER);
    }

    /** Approval: immutable ALLOCATION_APPROVED snapshot stamped with ruleset, parameter and inventory versions; finalization opens. */
    @Test @Order(8)
    void approveCreatesStampedSnapshot() {
        UUID c = cycle();
        var d = workflow.transition(c, new TransitionRequest("APPROVE_ALLOCATION", null, null, "approved for November", null, null), PLANNER);
        assertThat(d.cycle().state()).isEqualTo("REGIONAL_FINALIZATION");
        assertThat(d.regions()).allMatch(r -> r.finalizeStatus().equals("OPEN"));
        record Snap(UUID id, String ruleset, Integer paramVersion, UUID inventory, int rows) {}
        Snap s = jdbc.sql("SELECT id, ruleset_version, parameter_version, inventory_version_id, row_count FROM dataset_version WHERE cycle_id = :c AND type = 'ALLOCATION_APPROVED'")
            .param("c", c).query((rs, n) -> new Snap(rs.getObject(1, UUID.class), rs.getString(2), (Integer) rs.getObject(3), rs.getObject(4, UUID.class), rs.getInt(5))).single();
        assertThat(s.ruleset()).isEqualTo(com.pifa.rules.RulesFacade.RULESET_VERSION);
        assertThat(s.paramVersion()).isEqualTo(params.current().version());
        assertThat(s.inventory()).isEqualTo(repo.activeVersionId(c, DatasetType.INVENTORY_UPLOAD).orElseThrow());
        assertThat(s.rows()).isEqualTo(100);
        assertThat(alloc(c, "final_alloc_qty")).containsExactly(alloc(c, "approved_alloc_qty").toArray(Integer[]::new));
        // The inventory version behind an approved allocation can no longer be deleted
        assertThat(repo.isReferenced(s.inventory())).isTrue();
        // Snapshot lines are immutable
        assertThatThrownBy(() -> jdbc.sql("UPDATE allocation_line SET qty = 0 WHERE version_id = :v").param("v", s.id()).update()).hasMessageContaining("immutable");
        // No more Planner edits
        Line l = line(c, "NORTH");
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(l.id, "APPROVED", 1, "x", l.rowVersion)), PLANNER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CYCLE_LOCKED"));
    }

    /** Exports: all regions for the Planner; own region only for a Regional Manager. */
    @Test @Order(9)
    void exports() throws Exception {
        UUID c = cycle();
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(allocation.export(c, "approved", null, PLANNER)))) {
            assertThat(wb.getSheet("Allocation").getLastRowNum()).isEqualTo(25);
            assertThat(wb.getSheet("Allocation").getRow(0).getCell(1).getStringCellValue()).isEqualTo("North demand");
        }
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(allocation.export(c, "final", "NORTH", RM_NORTH)))) {
            assertThat(wb.getSheet("Allocation").getRow(0).getLastCellNum()).isEqualTo((short) 4); // product, demand, allocation, total
        }
        assertThatThrownBy(() -> allocation.export(c, "final", "EAST", RM_NORTH)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    // ---------------------------------------------------------------- helpers

    private record Line(UUID id, long rowVersion) {}

    private UUID cycle() { return repo.findCycleByMonth(MONTH).orElseThrow().id(); }

    private Line line(UUID c, String region) {
        return jdbc.sql("SELECT id, row_version FROM plan_line WHERE cycle_id = :c AND product_id = :p AND region_code = :r")
            .param("c", c).param("p", modelA).param("r", region).query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getLong(2))).single();
    }

    /** Model A's values of one column in region order N, E, W, S. */
    private List<Integer> alloc(UUID c, String column) {
        return jdbc.sql("SELECT l." + column + " FROM plan_line l JOIN region r ON r.code = l.region_code WHERE l.cycle_id = :c AND l.product_id = :p ORDER BY r.display_order")
            .param("c", c).param("p", modelA).query(Integer.class).list();
    }
}
