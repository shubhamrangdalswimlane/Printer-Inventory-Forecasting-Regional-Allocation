package com.pifa.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pifa.audit.AuditChainVerifier;
import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.security.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

/** Admin services against PostgreSQL: master data and import, audit search/export, audit-chain tamper detection, cycles, users view. */
@SpringBootTest
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
class AdminServicesTest {

    private static final CurrentUser ADMIN = new CurrentUser("alex.admin", Set.of("admin"));
    private static final CurrentUser PLANNER = new CurrentUser("pat.planner", Set.of("planner"));

    @MockBean JwtDecoder jwtDecoder;
    @Autowired MasterDataAdminService master;
    @Autowired AuditQueryService auditQuery;
    @Autowired AuditChainVerifier verifier;
    @Autowired AuditService audit;
    @Autowired CycleAdminService cycles;
    @Autowired KeycloakUsersService users;
    @Autowired JdbcClient jdbc;

    /** Products created here must not leak into other tests' working copies. */
    @AfterAll
    static void cleanUp(@Autowired JdbcClient jdbc) {
        jdbc.sql("DELETE FROM product_alias WHERE product_id IN (SELECT id FROM product WHERE product_name LIKE 'Test Admin %')").update();
        jdbc.sql("DELETE FROM product WHERE product_name LIKE 'Test Admin %'").update();
        jdbc.sql("DELETE FROM planning_cycle WHERE month = '2036-07'").update();
    }

    @Test
    void productsAliasesAndConflicts() {
        UUID id = master.addProduct("Test Admin Laser Z1", ADMIN);
        assertThatThrownBy(() -> master.addProduct("  test admin   LASER z1 ", ADMIN)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NAME_TAKEN"));
        assertThatThrownBy(() -> master.addProduct("Test Admin X", PLANNER)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        UUID alias = master.addAlias(id, "TA Laser Z-1", ADMIN);
        assertThatThrownBy(() -> master.addAlias(id, "ta laser z-1", ADMIN)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NAME_TAKEN"));
        assertThat(master.products("ta laser", false)).singleElement().satisfies(p -> assertThat(p.name()).isEqualTo("Test Admin Laser Z1"));
        master.updateProduct(id, null, null, " ta-z1 ", ADMIN);
        assertThat(master.products("TA-Z1", false)).singleElement().satisfies(p -> assertThat(p.code()).isEqualTo("TA-Z1"));
        assertThatThrownBy(() -> master.updateProduct(id, null, null, "bad code", ADMIN))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CODE_INVALID"));
        master.updateProduct(id, null, false, "", ADMIN);
        assertThat(jdbc.sql("SELECT product_code FROM product WHERE id = :id").param("id", id).query(String.class).optional()).isEmpty();
        assertThat(master.products("Test Admin Laser", false)).isEmpty();
        assertThat(master.products("Test Admin Laser", true)).hasSize(1);
        master.removeAlias(id, alias, ADMIN);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action LIKE 'ALIAS_%'").param("id", id.toString()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void bulkImportIsAllOrNothing() throws Exception {
        byte[] bad = workbook(new String[][]{{"Test Admin Import A", "ta import a", "yes"}, {"Test Admin Import A", "", "yes"}});
        var r1 = master.importProducts(bad, ADMIN);
        assertThat(r1.problems()).anyMatch(p -> p.contains("appears twice"));
        assertThat(master.products("Test Admin Import", true)).isEmpty();

        byte[] good = workbook(new String[][]{{"Test Admin Import A", "ta import a, ta-imp-a", "yes"}, {"Test Admin Import B", "", "no"}});
        var r2 = master.importProducts(good, ADMIN);
        assertThat(r2.problems()).isEmpty();
        assertThat(r2.created()).isEqualTo(2);
        assertThat(r2.aliasesAdded()).isEqualTo(2);
        assertThat(master.products("Test Admin Import B", false)).isEmpty(); // imported inactive
    }

    @Test
    void auditSearchAndExport() {
        audit.record("audit.tester", "TEST_EVENT", "test", "t-1", Map.of("note", "find me please"));
        @SuppressWarnings("unchecked")
        List<AuditQueryService.Entry> rows = (List<AuditQueryService.Entry>) auditQuery.search(new AuditQueryService.Filter("audit.tester", null, null, null, null, null, "find me"), 0, 10).get("rows");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).action()).isEqualTo("TEST_EVENT");
        String csv = new String(auditQuery.export(new AuditQueryService.Filter("audit.tester", null, null, null, null, null, null), "csv"));
        assertThat(csv).startsWith("id,at,actor,action").contains("TEST_EVENT");
        assertThat(auditQuery.export(new AuditQueryService.Filter("audit.tester", null, null, null, null, null, null), "xlsx")).isNotEmpty();
    }

    /** The log is append-only in the database, and the verifier detects a row altered behind the application's back. */
    @Test
    void auditChainDetectsTampering() {
        audit.record("audit.tester", "TEST_TAMPER_TARGET", "test", "t-2", Map.of("amount", 1));
        assertThat(verifier.verify().ok()).isTrue();
        long id = jdbc.sql("SELECT MAX(id) FROM audit_log WHERE action = 'TEST_TAMPER_TARGET'").query(Long.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET actor = 'mallory' WHERE id = :id").param("id", id).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log WHERE id = :id").param("id", id).update()).hasMessageContaining("append-only");

        // Simulate an attacker with database superuser rights who bypasses the trigger.
        jdbc.sql("ALTER TABLE audit_log DISABLE TRIGGER trg_audit_append_only").update();
        try {
            jdbc.sql("UPDATE audit_log SET actor = 'mallory' WHERE id = :id").param("id", id).update();
            var broken = verifier.verify();
            assertThat(broken.ok()).isFalse();
            assertThat(broken.brokenAtId()).isEqualTo(id);
            jdbc.sql("UPDATE audit_log SET actor = 'audit.tester' WHERE id = :id").param("id", id).update();
        } finally {
            jdbc.sql("ALTER TABLE audit_log ENABLE TRIGGER trg_audit_append_only").update();
        }
        assertThat(verifier.verify().ok()).isTrue();
        assertThat(jdbc.sql("SELECT ok FROM system_status WHERE key = 'audit.chain'").query(Boolean.class).single()).isTrue();
    }

    @Test
    void cyclesAndUsersView() {
        UUID c = cycles.create("2036-07", null, null, ADMIN);
        assertThat(c).isNotNull();
        assertThatThrownBy(() -> cycles.create("2036-07", null, null, ADMIN)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CYCLE_EXISTS"));
        assertThatThrownBy(() -> cycles.create("2036-13", null, null, ADMIN)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("BAD_MONTH"));
        assertThatThrownBy(() -> cycles.create("2036-08", null, null, PLANNER)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        // Not configured in tests: the users view answers 503 instead of failing obscurely.
        assertThatThrownBy(() -> users.users()).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(503));
    }

    private static byte[] workbook(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Products");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Product_name");
            h.createCell(1).setCellValue("Aliases");
            h.createCell(2).setCellValue("Active");
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i + 1);
                for (int j = 0; j < rows[i].length; j++) if (!rows[i][j].isEmpty()) r.createCell(j).setCellValue(rows[i][j]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
