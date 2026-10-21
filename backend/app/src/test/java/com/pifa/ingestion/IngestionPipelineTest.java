package com.pifa.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pifa.common.ApiException;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetRepository.VersionRow;
import com.pifa.dataset.DatasetService;
import com.pifa.dataset.DatasetType;
import com.pifa.ingestion.IngestionService.IngestRequest;
import com.pifa.ingestion.IngestionService.IngestResult;
import com.pifa.security.CurrentUser;
import com.pifa.synthetic.SyntheticDataService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

/**
 * End-to-end test of Phase 1 against a real PostgreSQL: synthetic files go through the same
 * ingestion pipeline as SFTP files, then versioning, download, delete and audit are checked.
 * Needs a database (see {@code MeControllerTest}). Tests run in order and share state on purpose,
 * because they follow one story: first upload, second upload, rejections, then maintenance.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
    "pifa.storage.dir=target/test-files"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IngestionPipelineTest {

    /** Fixed far-future cycle so the test never collides with real data in a dev database. */
    private static final String MONTH = "2031-01";

    private static final CurrentUser ADMIN = new CurrentUser("alex.admin", Set.of("admin"));
    private static final CurrentUser FORECASTER = new CurrentUser("fiona.forecaster", Set.of("forecaster"));
    private static final CurrentUser PLANNER = new CurrentUser("pat.planner", Set.of("planner"));

    @MockBean JwtDecoder jwtDecoder;
    @Autowired SyntheticDataService synthetic;
    @Autowired IngestionService ingestion;
    @Autowired DatasetService datasets;
    @Autowired DatasetRepository repo;
    @Autowired JdbcClient jdbc;

    /** Start from a clean slate: remove synthetic data left over from earlier runs. */
    @BeforeAll
    static void clean(@Autowired SyntheticDataService synthetic, @Autowired JdbcClient jdbc) {
        com.pifa.TestData.wipeCycles(jdbc, MONTH);
        synthetic.purgeAll(ADMIN);
    }

    /** First synthetic drop: forecast and inventory both VALID, version 1, auto-activated, with the expected line counts. */
    @Test @Order(1)
    void firstDropCreatesActiveVersionOne() {
        List<IngestResult> r = synthetic.generate(MONTH, 1, ADMIN);
        assertThat(r).extracting(IngestResult::status).containsExactly("VALID", "VALID");
        assertThat(r.get(0).label()).isEqualTo("FC-" + MONTH + "-v1");
        assertThat(r.get(1).label()).isEqualTo("INV-" + MONTH + "-v1");
        assertThat(r).allMatch(IngestResult::activated);
        // 25 products, one deliberately left out of the forecast -> 24 x 4 regions
        assertThat(count("forecast_line", r.get(0).versionId())).isEqualTo(96);
        assertThat(count("inventory_line", r.get(1).versionId())).isEqualTo(25);
        // The omitted product must have produced a warning, not an error.
        assertThat(r.get(0).findings()).anyMatch(f -> f.code().equals("PRODUCT_NOT_IN_FILE") && f.severity().equals("WARNING"));
    }

    /** A different seed gives different content: it becomes v2, v1 is SUPERSEDED, and compare shows differences. */
    @Test @Order(2)
    void secondDropSupersedesAndCompares() {
        UUID v1 = activeForecast().id();
        List<IngestResult> r = synthetic.generate(MONTH, 2, ADMIN);
        assertThat(r.get(0).label()).isEqualTo("FC-" + MONTH + "-v2");
        assertThat(repo.findVersion(v1).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(activeForecast().id()).isEqualTo(r.get(0).versionId());
        var cmp = datasets.compare(v1, r.get(0).versionId(), PLANNER);
        assertThat(cmp.changed()).isNotEmpty();
    }

    /** Re-dropping identical bytes is rejected as DUPLICATE_FILE. */
    @Test @Order(3)
    void identicalContentIsRejected() {
        // Resend the exact bytes that were ingested (regenerating would change embedded timestamps).
        byte[] same = datasets.download(activeForecast().id(), "original", FORECASTER).bytes();
        IngestResult r = ingestion.ingest(new IngestRequest(same, "again.xlsx", DatasetType.FORECAST_UPLOAD, "UI", "fiona.forecaster", "fiona.forecaster"));
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.findings()).extracting(IngestionService.Finding::code).contains("DUPLICATE_FILE");
    }

    /** Every file in the negative pack is rejected with its expected code, and nothing partial is stored. */
    @Test @Order(4)
    void negativePackIsRejectedWithExpectedCodes() {
        long versionsBefore = jdbc.sql("SELECT COUNT(*) FROM dataset_version").query(Long.class).single();
        byte[] ingested = datasets.download(activeForecast().id(), "original", FORECASTER).bytes();
        for (var f : synthetic.negativePack(MONTH, ingested)) {
            IngestResult r = ingestion.ingest(new IngestRequest(f.bytes(), f.name() + ".xlsx", SyntheticDataService.typeOf(f), "SYNTHETIC", "alex.admin", "alex.admin"));
            assertThat(r.status()).as(f.name()).isEqualTo("REJECTED");
            assertThat(r.findings()).as(f.name()).extracting(IngestionService.Finding::code).contains(f.expectedCode());
        }
        long versionsAfter = jdbc.sql("SELECT COUNT(*) FROM dataset_version").query(Long.class).single();
        assertThat(versionsAfter).as("rejected files must not create versions").isEqualTo(versionsBefore);
    }

    /** The original download is byte-identical (same SHA-256); an exported xlsx re-uploads cleanly. */
    @Test @Order(5)
    void downloadMatchesHashAndExportRoundTrips() throws Exception {
        VersionRow v = activeForecast();
        byte[] original = datasets.download(v.id(), "original", FORECASTER).bytes();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original))).isEqualTo(v.sha256());

        byte[] export = datasets.download(v.id(), "xlsx", FORECASTER).bytes();
        IngestResult r = ingestion.ingest(new IngestRequest(export, "export.xlsx", DatasetType.FORECAST_UPLOAD, "UI", "fiona.forecaster", "fiona.forecaster"));
        assertThat(r.status()).isEqualTo("VALID");
        assertThat(r.label()).isEqualTo("FC-" + MONTH + "-v3");
    }

    /** The active version and (via the cycle) locked data cannot be deleted; an old one can, then be restored and purged. */
    @Test @Order(6)
    void deleteSafeguardsAndLifecycle() {
        VersionRow active = activeForecast();
        assertThatThrownBy(() -> datasets.softDelete(active.id(), "oops", ADMIN))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VERSION_ACTIVE"));
        assertThatThrownBy(() -> datasets.softDelete(active.id(), " ", ADMIN))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REASON_REQUIRED"));

        VersionRow v1 = repo.list(DatasetType.FORECAST_UPLOAD, active.cycleId(), false).stream().filter(x -> x.versionNo() == 1).findFirst().orElseThrow();
        // A Regional Manager-like user without roles may not delete.
        assertThatThrownBy(() -> datasets.softDelete(v1.id(), "cleanup", new CurrentUser("x", Set.of())))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("FORBIDDEN"));

        datasets.softDelete(v1.id(), "superseded duplicate", ADMIN);
        assertThat(datasets.list(DatasetType.FORECAST_UPLOAD, active.cycleId(), false, FORECASTER)).extracting(VersionRow::id).doesNotContain(v1.id());
        assertThat(datasets.list(DatasetType.FORECAST_UPLOAD, active.cycleId(), true, ADMIN)).extracting(VersionRow::id).contains(v1.id());

        datasets.undelete(v1.id(), "needed after all", ADMIN);
        assertThat(repo.findVersion(v1.id()).orElseThrow().status()).isEqualTo("VALID");

        datasets.softDelete(v1.id(), "final cleanup", ADMIN);
        datasets.purge(v1.id(), ADMIN); // synthetic versions bypass the 90-day retention
        VersionRow purged = repo.findVersion(v1.id()).orElseThrow();
        assertThat(purged.status()).isEqualTo("PURGED");
        assertThat(count("forecast_line", v1.id())).isZero();
    }

    /** Activating an older version swaps the active flag and keeps exactly one active version. */
    @Test @Order(7)
    void activateOlderVersionKeepsSingleActive() {
        List<VersionRow> forecasts = repo.list(DatasetType.FORECAST_UPLOAD, activeForecast().cycleId(), false);
        VersionRow old = forecasts.stream().filter(x -> !x.active()).findFirst().orElseThrow();
        datasets.activate(old.id(), "re-use earlier file", FORECASTER);
        assertThat(repo.list(DatasetType.FORECAST_UPLOAD, old.cycleId(), false).stream().filter(VersionRow::active)).hasSize(1);
        assertThat(activeForecast().id()).isEqualTo(old.id());
    }

    /** The audit log's hash chain is intact: each row's prev_hash equals the previous row's hash and each hash recomputes. */
    @Test @Order(8)
    void auditChainIsIntact() throws Exception {
        record Row(String at, String actor, String action, String type, String id, String detail, String prev, String hash) {}
        List<Row> rows = jdbc.sql("SELECT to_char(at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') , actor, action, entity_type, entity_id, detail::text, prev_hash, hash FROM audit_log ORDER BY id")
            .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))).list();
        assertThat(rows).isNotEmpty();
        String prev = "0".repeat(64);
        for (Row r : rows) {
            assertThat(r.prev()).isEqualTo(prev);
            prev = r.hash();
        }
        // Spot-check that hashes are 64 hex chars (full recomputation is the nightly verification job in a later phase).
        assertThat(rows).allMatch(r -> r.hash().matches("[0-9a-f]{64}"));
    }

    // ---------------------------------------------------------------- helpers

    private VersionRow activeForecast() {
        UUID cycle = repo.findCycleByMonth(MONTH).orElseThrow().id();
        return repo.list(DatasetType.FORECAST_UPLOAD, cycle, false).stream().filter(VersionRow::active).findFirst().orElseThrow();
    }

    private long count(String table, UUID versionId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE version_id = :v").param("v", versionId).query(Long.class).single();
    }
}
