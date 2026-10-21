package com.pifa.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pifa.TestData;
import com.pifa.common.ApiException;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetType;
import com.pifa.forecast.ForecastEditService;
import com.pifa.forecast.ForecastEditService.EditRequest;
import com.pifa.forecast.SalesAnalysisService;
import com.pifa.notify.NotificationService;
import com.pifa.security.CurrentUser;
import com.pifa.synthetic.SyntheticDataService;
import com.pifa.workflow.CycleWorkflowService.CycleDetail;
import com.pifa.workflow.CycleWorkflowService.TransitionRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end test of the Phase 2 forecast workflow against a real PostgreSQL:
 * upload -> working copy -> Forecaster edits -> publish -> regional edits with change control ->
 * four submissions -> PLANNING -> return a region -> resubmit; plus recall, optimistic locking,
 * snapshot immutability, working-copy snapshots and sales analysis.
 * Tests run in order and share state, following one story.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ForecastWorkflowTest {

    private static final String MONTH = "2033-03";
    private static final String MONTH_NO_FORECAST = "2033-04";
    private static final String MONTH_RECALL = "2033-05";
    private static final String MONTH_RELOAD = "2033-06";

    private static final CurrentUser ADMIN = new CurrentUser("alex.admin", Set.of("admin"));
    private static final CurrentUser FORECASTER = new CurrentUser("fiona.forecaster", Set.of("forecaster"));
    private static final CurrentUser PLANNER = new CurrentUser("pat.planner", Set.of("planner"));
    private static final CurrentUser RM_NORTH = new CurrentUser("anita.north", Set.of("regional_manager"), Set.of("NORTH"));
    private static final CurrentUser RM_EAST = new CurrentUser("evan.east", Set.of("regional_manager"), Set.of("EAST"));
    private static final CurrentUser RM_WEST = new CurrentUser("wendy.west", Set.of("regional_manager"), Set.of("WEST"));
    private static final CurrentUser RM_SOUTH = new CurrentUser("sam.south", Set.of("regional_manager"), Set.of("SOUTH"));

    @MockBean JwtDecoder jwtDecoder;
    @Autowired MockMvc mvc;
    @Autowired SyntheticDataService synthetic;
    @Autowired CycleWorkflowService workflow;
    @Autowired ForecastEditService edits;
    @Autowired SalesAnalysisService sales;
    @Autowired NotificationService notifications;
    @Autowired DatasetRepository repo;
    @Autowired JdbcClient jdbc;

    /** Start clean: remove the cycles this test uses (and synthetic leftovers) from earlier runs. */
    @BeforeAll
    static void clean(@Autowired SyntheticDataService synthetic, @Autowired JdbcClient jdbc, @Autowired TransactionTemplate tx) {
        TestData.wipeCycles(tx, jdbc, MONTH, MONTH_NO_FORECAST, MONTH_RECALL, MONTH_RELOAD);
        synthetic.purgeAll(ADMIN);
    }

    /** A synthetic upload becomes the active forecast and fills the working copy: 25 products x 4 regions, cycle in FORECAST_DRAFT. */
    @Test @Order(1)
    void uploadFillsWorkingCopy() {
        synthetic.generate(MONTH, 1, ADMIN);
        UUID c = cycle(MONTH);
        assertThat(count("SELECT COUNT(*) FROM plan_line WHERE cycle_id = :c", c)).isEqualTo(100);
        assertThat(workflow.detail(c).cycle().state()).isEqualTo("FORECAST_DRAFT");
        // plan_line starts equal to the file: published = uploaded
        assertThat(count("SELECT COUNT(*) FROM plan_line WHERE cycle_id = :c AND published_qty <> uploaded_qty", c)).isZero();
    }

    /** The Forecaster edits a cell (logged); a second edit quoting the old row version conflicts and changes nothing. */
    @Test @Order(2)
    void forecasterEditIsLoggedAndStaleEditConflicts() {
        UUID c = cycle(MONTH);
        Line l = bigLine(c, "NORTH");
        int newValue = l.published + 7;
        var done = edits.apply(c, List.of(new EditRequest(l.id, "PUBLISHED", newValue, null, l.rowVersion)), FORECASTER);
        assertThat(done).hasSize(1);
        assertThat(done.get(0).rowVersion()).isEqualTo(l.rowVersion + 1);
        assertThat(done.get(0).publishedQty()).isEqualTo(newValue);

        // Same row version again = stale: must be a 409 and must not overwrite.
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(l.id, "PUBLISHED", newValue + 100, null, l.rowVersion)), FORECASTER))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(409);
                assertThat(e.getCode()).isEqualTo("CONFLICT");
                assertThat(e.getExtra()).containsKey("conflicts");
            });
        assertThat(refresh(l.id).published).isEqualTo(newValue);

        // change_log keeps who / old / new
        var log = jdbc.sql("SELECT actor, old_value, new_value, field FROM change_log WHERE cycle_id = :c").param("c", c)
            .query((rs, n) -> rs.getString(1) + ":" + rs.getInt(2) + ":" + rs.getInt(3) + ":" + rs.getString(4)).list();
        assertThat(log).containsExactly("fiona.forecaster:" + l.published + ":" + newValue + ":PUBLISHED");

        // Wrong role / wrong state
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(l.id, "REVISED", 1, "x", refresh(l.id).rowVersion)), FORECASTER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(l.id, "REVISED", 1, "x", refresh(l.id).rowVersion)), RM_NORTH))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CYCLE_LOCKED"));
    }

    /** Publishing a cycle that has no active forecast is blocked by guard rule BR-WF-01 (HTTP 422). */
    @Test @Order(3)
    void publishWithoutForecastIsBlockedByGuard() {
        jdbc.sql("INSERT INTO planning_cycle (id, month) VALUES (:id, :m)").param("id", UUID.randomUUID()).param("m", MONTH_NO_FORECAST).update();
        UUID empty = cycle(MONTH_NO_FORECAST);
        assertThatThrownBy(() -> workflow.transition(empty, publish(), FORECASTER))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(422);
                assertThat(e.getMessage()).contains("BR-WF-01");
            });
    }

    /** Only the Forecaster can publish; publishing freezes numbers into a snapshot, copies them to revised, opens all four regions and notifies the managers. */
    @Test @Order(4)
    void publishCreatesSnapshotAndOpensRegions() {
        UUID c = cycle(MONTH);
        assertThatThrownBy(() -> workflow.transition(c, publish(), PLANNER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));

        CycleDetail d = workflow.transition(c, publish(), FORECASTER);
        assertThat(d.cycle().state()).isEqualTo("REGIONAL_REVIEW");
        assertThat(d.regions()).extracting(CycleWorkflowService.RegionStatus::region).containsExactly("NORTH", "EAST", "WEST", "SOUTH");
        assertThat(d.regions()).allMatch(r -> r.reviewStatus().equals("OPEN"));

        UUID published = repo.latestVersionId(c, DatasetType.FORECAST_PUBLISHED).orElseThrow();
        assertThat(count("SELECT COUNT(*) FROM forecast_line WHERE version_id = :c", published)).isEqualTo(100);
        // The snapshot points back to the forecast upload it came from (which protects that upload from deletion)
        assertThat(jdbc.sql("SELECT parent_version_id FROM dataset_version WHERE id = :v").param("v", published).query(UUID.class).single()).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM plan_line WHERE cycle_id = :c AND revised_qty <> published_qty", c)).isZero();
        // The snapshot holds the Forecaster's edit from test 2
        assertThat(count("SELECT COUNT(*) FROM forecast_line f JOIN plan_line l ON l.product_id = f.product_id AND l.region_code = f.region_code AND l.cycle_id = :c "
            + "WHERE f.version_id = :v AND f.qty = l.published_qty", c, published)).isEqualTo(100);

        assertThat(notifications.forUser(RM_NORTH, 50)).anyMatch(n -> n.title().startsWith("Forecast published"));
        assertThat(notifications.forUser(RM_NORTH, 50)).noneMatch(n -> n.title().contains("EAST"));
        // The Forecaster can no longer edit
        Line l = bigLine(c, "NORTH");
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(l.id, "PUBLISHED", 1, null, l.rowVersion)), FORECASTER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CYCLE_LOCKED"));
    }

    /** Regional Manager change control: small change needs no reason, large change needs one (Drools BR-VA-05); other regions are forbidden, also over HTTP. */
    @Test @Order(5)
    void regionalEditsFollowChangeControlAndRegionScope() throws Exception {
        UUID c = cycle(MONTH);
        Line l = bigLine(c, "NORTH");
        int within = (int) Math.floor(l.published * 1.1);          // +10%: no reason needed
        int beyond = l.published * 2;                                // +100%: reason needed

        var ok = edits.apply(c, List.of(new EditRequest(l.id, "REVISED", within, null, l.rowVersion)), RM_NORTH);
        assertThat(ok.get(0).revisedQty()).isEqualTo(within);

        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(l.id, "REVISED", beyond, null, refresh(l.id).rowVersion)), RM_NORTH))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(400);
                assertThat(e.getCode()).isEqualTo("REASON_REQUIRED");
            });
        var withReason = edits.apply(c, List.of(new EditRequest(l.id, "REVISED", beyond, "regional promotion", refresh(l.id).rowVersion)), RM_NORTH);
        assertThat(withReason.get(0).revisionReason()).isEqualTo("regional promotion");

        // Service level: North may not touch EAST
        Line east = bigLine(c, "EAST");
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(east.id, "REVISED", east.published, null, east.rowVersion)), RM_NORTH))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REGION_FORBIDDEN"));
        // HTTP level with a real JWT-shaped principal: 403 for another region
        String body = "{\"edits\":[{\"lineId\":\"" + east.id + "\",\"field\":\"REVISED\",\"value\":" + east.published + ",\"reason\":null,\"rowVersion\":" + east.rowVersion + "}]}";
        mvc.perform(patch("/api/v1/cycles/" + c + "/forecast/lines").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt(j -> j.claim("preferred_username", "anita.north").claim("regions", List.of("NORTH")))
                    .authorities(new SimpleGrantedAuthority("ROLE_REGIONAL_MANAGER"))))
            .andExpect(status().isForbidden());

        assertThat(count("SELECT COUNT(*) FROM change_log WHERE cycle_id = :c AND field = 'REVISED' AND actor = 'anita.north'", c)).isEqualTo(2);
    }

    /** Four regions submit one by one; each creates a regional snapshot; the last one moves the cycle to PLANNING with a consolidated snapshot. */
    @Test @Order(6)
    void submissionsMoveCycleToPlanning() {
        UUID c = cycle(MONTH);
        // East may not submit West's region
        assertThatThrownBy(() -> workflow.transition(c, submit("WEST"), RM_EAST))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REGION_FORBIDDEN"));

        workflow.transition(c, submit("NORTH"), RM_NORTH);
        assertThat(reviewOf(c, "NORTH")).isEqualTo("SUBMITTED");
        assertThat(count("SELECT COUNT(*) FROM dataset_version WHERE cycle_id = :c AND type = 'FORECAST_REVISED' AND scope = 'NORTH'", c)).isEqualTo(1);
        // Once submitted, North's cells are locked
        Line l = bigLine(c, "NORTH");
        assertThatThrownBy(() -> edits.apply(c, List.of(new EditRequest(l.id, "REVISED", 1, "late", l.rowVersion)), RM_NORTH))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REGION_SUBMITTED"));
        // Submitting twice is refused
        assertThatThrownBy(() -> workflow.transition(c, submit("NORTH"), RM_NORTH))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REGION_SUBMITTED"));

        workflow.transition(c, submit("EAST"), RM_EAST);
        workflow.transition(c, submit("WEST"), RM_WEST);
        assertThat(workflow.detail(c).cycle().state()).isEqualTo("REGIONAL_REVIEW");

        CycleDetail d = workflow.transition(c, submit("SOUTH"), RM_SOUTH);
        assertThat(d.cycle().state()).isEqualTo("PLANNING");
        UUID consolidated = jdbc.sql("SELECT id FROM dataset_version WHERE cycle_id = :c AND type = 'FORECAST_REVISED' AND scope = 'ALL'").param("c", c).query(UUID.class).single();
        assertThat(count("SELECT COUNT(*) FROM forecast_line WHERE version_id = :c", consolidated)).isEqualTo(100);
        // The consolidated snapshot contains North's revised value (the 2x with reason from test 5)
        assertThat(count("SELECT COUNT(*) FROM forecast_line f JOIN plan_line p ON p.product_id = f.product_id AND p.region_code = f.region_code AND p.cycle_id = :c "
            + "WHERE f.version_id = :v AND f.qty = p.revised_qty", c, consolidated)).isEqualTo(100);
        assertThat(notifications.forUser(PLANNER, 50)).anyMatch(n -> n.title().startsWith("All regions submitted"));
        // PUBLISH + 4 x SUBMIT_REGION + ALL_SUBMITTED
        assertThat(count("SELECT COUNT(*) FROM workflow_event WHERE cycle_id = :c", c)).isEqualTo(6);
    }

    /** The Planner returns one region with a comment; it reopens, can be edited and resubmitted, and the cycle returns to PLANNING with a second consolidated snapshot. */
    @Test @Order(7)
    void plannerReturnsRegionAndItResubmits() {
        UUID c = cycle(MONTH);
        assertThatThrownBy(() -> workflow.transition(c, new TransitionRequest("RETURN_REGIONS", null, List.of("SOUTH"), " ", null, null), PLANNER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("COMMENT_REQUIRED"));
        assertThatThrownBy(() -> workflow.transition(c, new TransitionRequest("RETURN_REGIONS", null, List.of("SOUTH"), "recheck", null, null), FORECASTER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));

        CycleDetail d = workflow.transition(c, new TransitionRequest("RETURN_REGIONS", null, List.of("SOUTH"), "numbers look too high", null, null), PLANNER);
        assertThat(d.cycle().state()).isEqualTo("REGIONAL_REVIEW");
        assertThat(reviewOf(c, "SOUTH")).isEqualTo("OPEN");
        assertThat(reviewOf(c, "NORTH")).isEqualTo("SUBMITTED");
        assertThat(notifications.forUser(RM_SOUTH, 50)).anyMatch(n -> n.title().contains("returned"));

        Line l = bigLine(c, "SOUTH");
        edits.apply(c, List.of(new EditRequest(l.id, "REVISED", l.published, null, l.rowVersion)), RM_SOUTH);
        assertThat(workflow.transition(c, submit("SOUTH"), RM_SOUTH).cycle().state()).isEqualTo("PLANNING");
        assertThat(count("SELECT COUNT(*) FROM dataset_version WHERE cycle_id = :c AND type = 'FORECAST_REVISED' AND scope = 'ALL'", c)).isEqualTo(2);
    }

    /** Recall rules: Forecaster may recall before any submit; after a submit only Admin, with a comment. Recall clears the regional numbers. */
    @Test @Order(8)
    void recallRules() {
        synthetic.generate(MONTH_RECALL, 1, ADMIN);
        UUID c = cycle(MONTH_RECALL);
        workflow.transition(c, publish(), FORECASTER);
        assertThat(workflow.transition(c, new TransitionRequest("RECALL", null, null, null, null, null), FORECASTER).cycle().state()).isEqualTo("FORECAST_DRAFT");
        assertThat(count("SELECT COUNT(*) FROM cycle_region_status WHERE cycle_id = :c", c)).isZero();
        assertThat(count("SELECT COUNT(*) FROM plan_line WHERE cycle_id = :c AND revised_qty IS NOT NULL", c)).isZero();

        workflow.transition(c, publish(), FORECASTER);
        assertThat(repo.list(DatasetType.FORECAST_PUBLISHED, c, false)).hasSize(2); // a new immutable snapshot per publish
        workflow.transition(c, submit("NORTH"), RM_NORTH);
        assertThatThrownBy(() -> workflow.transition(c, new TransitionRequest("RECALL", null, null, null, null, null), FORECASTER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REGION_SUBMITTED"));
        assertThatThrownBy(() -> workflow.transition(c, new TransitionRequest("RECALL", null, null, " ", null, null), ADMIN))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("COMMENT_REQUIRED"));
        assertThat(workflow.transition(c, new TransitionRequest("RECALL", null, null, "wrong baseline", null, null), ADMIN).cycle().state()).isEqualTo("FORECAST_DRAFT");
    }

    /** A stale cycle row version on a transition is a 409 (two people acting at once cannot both succeed). */
    @Test @Order(9)
    void staleCycleVersionIsRejected() {
        UUID c = cycle(MONTH_RECALL);
        long v = workflow.detail(c).cycle().rowVersion();
        assertThatThrownBy(() -> workflow.transition(c, new TransitionRequest("PUBLISH", null, null, null, v - 1, null), FORECASTER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }

    /** Lines of snapshot versions can be neither updated nor deleted (database trigger). */
    @Test @Order(10)
    void snapshotLinesAreImmutable() {
        UUID c = cycle(MONTH);
        UUID published = repo.latestVersionId(c, DatasetType.FORECAST_PUBLISHED).orElseThrow();
        assertThatThrownBy(() -> jdbc.sql("UPDATE forecast_line SET qty = 0 WHERE version_id = :v").param("v", published).update())
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM forecast_line WHERE version_id = :v").param("v", published).update())
            .hasMessageContaining("immutable");
    }

    /** A new upload replacing an edited working copy first saves the edits as a FORECAST_WORKING_SNAPSHOT, then reloads from the file. */
    @Test @Order(11)
    void newUploadSnapshotsEditsAndReloads() {
        synthetic.generate(MONTH_RELOAD, 1, ADMIN);
        UUID c = cycle(MONTH_RELOAD);
        Line l = bigLine(c, "WEST");
        int edited = l.published + 11;
        edits.apply(c, List.of(new EditRequest(l.id, "PUBLISHED", edited, null, l.rowVersion)), FORECASTER);

        synthetic.generate(MONTH_RELOAD, 2, ADMIN); // different content -> new version v2 auto-activated
        UUID snap = repo.latestVersionId(c, DatasetType.FORECAST_WORKING_SNAPSHOT).orElseThrow();
        // The snapshot kept the Forecaster's edit for that product and region
        assertThat(jdbc.sql("SELECT qty FROM forecast_line WHERE version_id = :v AND region_code = 'WEST' AND product_id = :p")
            .param("v", snap).param("p", l.productId).query(Integer.class).single()).isEqualTo(edited);
        assertThat(count("SELECT COUNT(*) FROM plan_line WHERE cycle_id = :c AND published_qty <> uploaded_qty", c)).as("working copy reloaded from the new file").isZero();
    }

    /** Sales analysis: averages equal a manual calculation, and a Regional Manager may only see their own region. */
    @Test @Order(12)
    void salesAnalysisMatchesManualAverage() {
        UUID c = cycle(MONTH);
        var rows = sales.analysis(c, "NORTH", RM_NORTH);
        assertThat(rows).hasSize(25);
        var first = rows.get(0);
        String latest = jdbc.sql("SELECT MAX(month) FROM sales_history WHERE region_code = 'NORTH'").query(String.class).single();
        double manual3 = jdbc.sql("SELECT AVG(qty) FROM (SELECT qty FROM sales_history WHERE product_id = :p AND region_code = 'NORTH' ORDER BY month DESC LIMIT 3) t")
            .param("p", first.productId()).query(Double.class).single();
        assertThat(first.avg3()).isEqualTo(manual3, org.assertj.core.data.Offset.offset(0.001));
        assertThat(first.series()).hasSize(12);
        assertThat(latest).isNotNull();
        assertThatThrownBy(() -> sales.analysis(c, "EAST", RM_NORTH)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThat(sales.analysis(c, "EAST", PLANNER)).hasSize(25);
        assertThat(sales.history(first.productId(), "NORTH", 24, RM_NORTH)).hasSize(24);
    }

    // ---------------------------------------------------------------- helpers

    private record Line(UUID id, UUID productId, int published, long rowVersion) {}

    private UUID cycle(String month) { return repo.findCycleByMonth(month).orElseThrow().id(); }

    /** The first line of a region with a published quantity of at least 50, so percentage maths is meaningful. */
    private Line bigLine(UUID cycle, String region) {
        return jdbc.sql("SELECT l.id, l.product_id, l.published_qty, l.row_version FROM plan_line l JOIN product p ON p.id = l.product_id "
                + "WHERE l.cycle_id = :c AND l.region_code = :r AND l.published_qty >= 50 ORDER BY p.product_name LIMIT 1")
            .param("c", cycle).param("r", region)
            .query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getLong(4))).single();
    }

    private Line refresh(UUID lineId) {
        return jdbc.sql("SELECT id, product_id, published_qty, row_version FROM plan_line WHERE id = :id").param("id", lineId)
            .query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getLong(4))).single();
    }

    private String reviewOf(UUID cycle, String region) {
        return jdbc.sql("SELECT review_status FROM cycle_region_status WHERE cycle_id = :c AND region_code = :r").param("c", cycle).param("r", region).query(String.class).single();
    }

    private long count(String sql, UUID id) { return jdbc.sql(sql).param("c", id).param("v", id).query(Long.class).single(); }

    private long count(String sql, UUID cycle, UUID version) { return jdbc.sql(sql).param("c", cycle).param("v", version).query(Long.class).single(); }

    private static TransitionRequest publish() { return new TransitionRequest("PUBLISH", null, null, null, null, null); }

    private static TransitionRequest submit(String region) { return new TransitionRequest("SUBMIT_REGION", region, null, null, null, null); }
}
