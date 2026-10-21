package com.pifa.golive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pifa.TestData;
import com.pifa.common.ApiException;
import com.pifa.master.MasterDataService;
import com.pifa.security.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 6 go-live tools against PostgreSQL: matching report (exact/alias/inactive/unknown with suggestions),
 * all-or-nothing sales import, parallel-run reconciliation, readiness report, and Admin-only access.
 */
@SpringBootTest
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
class GoLiveServicesTest {

    private static final CurrentUser ADMIN = new CurrentUser("alex.admin", Set.of("admin"));
    private static final CurrentUser PLANNER = new CurrentUser("pat.planner", Set.of("planner"));
    private static final String MONTH = "2037-03";

    @MockBean JwtDecoder jwtDecoder;
    @Autowired MatchingReportService matching;
    @Autowired SalesHistoryImportService sales;
    @Autowired ReconciliationService reconciliation;
    @Autowired ReadinessService readiness;
    @Autowired GoLiveTemplates templates;
    @Autowired MasterDataService master;
    @Autowired JdbcClient jdbc;

    static UUID laser;
    static UUID inkjet;
    static UUID retired;

    /** Three test products: one with an alias, one inactive. */
    @BeforeAll
    static void products(@Autowired MasterDataService master, @Autowired JdbcClient jdbc, @Autowired TransactionTemplate tx) {
        cleanUp(jdbc, tx);
        laser = master.addProduct("Test GoLive Mono Laser M100", "MANUAL");
        inkjet = master.addProduct("Test GoLive Inkjet Pro 200", "MANUAL");
        retired = master.addProduct("Test GoLive Retired R1", "MANUAL");
        master.addAlias(laser, "TGL Laser M-100");
        jdbc.sql("UPDATE product SET active = false WHERE id = :id").param("id", retired).update();
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcClient jdbc, @Autowired TransactionTemplate tx) {
        TestData.wipeCycles(tx, jdbc, MONTH);
        String mine = "(SELECT id FROM product WHERE product_name LIKE 'Test GoLive %')";
        jdbc.sql("DELETE FROM sales_history WHERE product_id IN " + mine).update();
        jdbc.sql("DELETE FROM product_alias WHERE product_id IN " + mine).update();
        jdbc.sql("DELETE FROM product WHERE product_name LIKE 'Test GoLive %'").update();
        jdbc.sql("DELETE FROM system_status WHERE key LIKE 'golive.%'").update();
    }

    @Test
    void matchingReportClassifiesEveryNameAndSuggests() throws Exception {
        byte[] file = workbook("Inventory", new String[]{"Product_name", "Available_Qty"},
            new Object[][]{{"Test GoLive Mono Laser M100", 5}, {"  test golive mono  laser m100", 6}, {"TGL Laser M-100", 1},
                {"Test GoLive Retired R1", 2}, {"Test GoLive Mono Laser M-100X", 3}, {"Test GoLive Mono Laser M-100X", 4}, {"Completely Different", 1}});
        var r = matching.report(file, "inv.xlsx", ADMIN);
        assertThat(r.distinctNames()).isEqualTo(5);
        assertThat(r.exact()).isEqualTo(1);
        assertThat(r.alias()).isEqualTo(1);
        assertThat(r.ok()).isFalse();
        // Most frequent problem first; the typo variant suggests the right product.
        assertThat(r.problems().get(0).name()).isEqualTo("Test GoLive Mono Laser M-100X");
        assertThat(r.problems().get(0).occurrences()).isEqualTo(2);
        assertThat(r.problems().get(0).suggestions()).first().satisfies(s -> assertThat(s.productId()).isEqualTo(laser));
        assertThat(r.problems()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("Test GoLive Retired R1");
            assertThat(p.match()).isEqualTo(MatchingReportService.Match.INACTIVE);
        });
        assertThat(jdbc.sql("SELECT ok FROM system_status WHERE key = 'golive.matching'").query(Boolean.class).single()).isFalse();

        assertThatThrownBy(() -> matching.report(file, "inv.xlsx", PLANNER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> matching.report(workbook("Other", new String[]{"X"}, new Object[][]{}), "x.xlsx", ADMIN))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("FILE_INVALID"));
    }

    @Test
    void salesImportIsAllOrNothingAndOverwrites() throws Exception {
        String[] h = {"Product_name", "Month", "North", "East", "West", "South"};
        byte[] bad = workbook("Sales", h, new Object[][]{
            {"Test GoLive Inkjet Pro 200", "2025-01", 10, 20, 30, 40},
            {"Unknown Printer", "2025-01", 1, 1, 1, 1},
            {"Test GoLive Inkjet Pro 200", "2025-01", 1, 1, 1, 1},
            {"Test GoLive Inkjet Pro 200", "2099-01", 1, 1, 1, 1},
            {"Test GoLive Inkjet Pro 200", "2025-02", -1, 1, 1, 1}});
        var r1 = sales.importSales(bad, "bad.xlsx", "UPLOAD", "alex.admin");
        assertThat(r1.ok()).isFalse();
        assertThat(r1.problems()).hasSize(4);
        assertThat(salesOf(inkjet)).isZero();

        byte[] good = workbook("Sales", h, new Object[][]{
            {"Test GoLive Inkjet Pro 200", "2025-01", 10, 20, 30, 40},
            {"TGL Laser M-100", "2025-01", 5, null, 0, 1}}); // blank East = no data
        var r2 = sales.importSales(good, "good.xlsx", "UPLOAD", "alex.admin");
        assertThat(r2.ok()).isTrue();
        assertThat(r2.imported()).isEqualTo(7);
        assertThat(r2.firstMonth()).isEqualTo("2025-01");
        assertThat(salesOf(inkjet)).isEqualTo(100);

        // Re-import with a corrected value overwrites, and the rows are tagged with the source.
        sales.importSales(workbook("Sales", h, new Object[][]{{"Test GoLive Inkjet Pro 200", "2025-01", 11, 20, 30, 40}}), "fix.xlsx", "SFTP", "sftp-sales");
        assertThat(salesOf(inkjet)).isEqualTo(101);
        assertThat(jdbc.sql("SELECT DISTINCT source FROM sales_history WHERE product_id = :p").param("p", inkjet).query(String.class).list()).containsExactly("SFTP");
    }

    @Test
    void reconciliationListsEveryDifference() throws Exception {
        UUID cycle = UUID.randomUUID();
        jdbc.sql("INSERT INTO planning_cycle (id, month, state) VALUES (:id, :m, 'FINAL')").param("id", cycle).param("m", MONTH).update();
        int[][] finals = {{30, 20, 25, 25}, {10, 10, 10, 10}};
        UUID[] pids = {laser, inkjet};
        String[] regions = {"NORTH", "EAST", "WEST", "SOUTH"};
        for (int p = 0; p < 2; p++) for (int r = 0; r < 4; r++) {
            jdbc.sql("INSERT INTO plan_line (id, cycle_id, product_id, region_code, uploaded_qty, published_qty, final_alloc_qty) VALUES (:id, :c, :p, :r, 0, 0, :q)")
                .param("id", UUID.randomUUID()).param("c", cycle).param("p", pids[p]).param("r", regions[r]).param("q", finals[p][r]).update();
        }
        String[] h = {"Product_name", "North", "East", "West", "South"};
        byte[] legacy = workbook("Allocation", h, new Object[][]{
            {"TGL Laser M-100", 30, 20, 25, 25},             // matches (through the alias)
            {"Test GoLive Inkjet Pro 200", 12, 8, 10, 10},   // two cells differ
            {"Not In Cycle", 1, 1, 1, 1}});
        var r = reconciliation.reconcile(cycle, legacy, "legacy.xlsx", ADMIN);
        assertThat(r.pifaStage()).isEqualTo("FINAL");
        assertThat(r.cellsCompared()).isEqualTo(8);
        assertThat(r.matching()).isEqualTo(6);
        assertThat(r.differenceCount()).isEqualTo(2);
        assertThat(r.differences()).extracting(ReconciliationService.Difference::diff).containsExactlyInAnyOrder(-2, 2);
        assertThat(r.onlyInFile()).containsExactly("Not In Cycle");
        assertThat(r.pifaTotal()).isEqualTo(140);
        assertThat(r.legacyTotal()).isEqualTo(140);
        assertThat(r.ok()).isFalse();

        assertThatThrownBy(() -> reconciliation.reconcile(UUID.randomUUID(), legacy, "x.xlsx", ADMIN))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test
    void readinessReportsFailuresInDevConfiguration() {
        var report = readiness.run();
        // Tests run with local storage and no virus scanner: go-live must be blocked.
        assertThat(report.overall()).isEqualTo(ReadinessService.Status.FAIL);
        assertThat(report.checks()).anySatisfy(c -> {
            assertThat(c.key()).isEqualTo("config.storage");
            assertThat(c.status()).isEqualTo(ReadinessService.Status.FAIL);
        });
        // Keycloak is not configured in tests: the users check degrades to WARN instead of failing the report.
        assertThat(report.checks()).anySatisfy(c -> {
            assertThat(c.key()).isEqualTo("keycloak.users");
            assertThat(c.status()).isEqualTo(ReadinessService.Status.WARN);
        });
        assertThat(report.checks()).filteredOn(c -> c.status() == ReadinessService.Status.MANUAL).hasSize(9);
    }

    @Test
    void templatesCarryTheRegionColumns() throws Exception {
        try (var wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(templates.template("sales")))) {
            Row header = wb.getSheet("Sales").getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Product_name");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Month");
            assertThat(header.getLastCellNum()).isEqualTo((short) (2 + master.activeRegionCodes().size()));
        }
        assertThatThrownBy(() -> templates.template("nope")).isInstanceOf(ApiException.class);
    }

    @Test
    void nameMatcherScores() {
        assertThat(NameMatcher.editSimilarity("monolaserm100", "monolaserm100")).isEqualTo(1.0);
        assertThat(NameMatcher.jaccard(NameMatcher.words("M100 Mono Laser"), NameMatcher.words("Mono Laser M100"))).isEqualTo(1.0);
        assertThat(NameMatcher.editSimilarity("abc", "xyzxyzxyz")).isZero(); // pruned by length
    }

    /** @return total sales of a test product across regions and months */
    private long salesOf(UUID product) {
        return jdbc.sql("SELECT COALESCE(SUM(qty), 0) FROM sales_history WHERE product_id = :p").param("p", product).query(Long.class).single();
    }

    /** Builds a one-sheet workbook; null cells are left empty. */
    static byte[] workbook(String sheetName, String[] headers, Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet(sheetName);
            Row h = s.createRow(0);
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else if (v != null) row.createCell(c).setCellValue(v.toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
