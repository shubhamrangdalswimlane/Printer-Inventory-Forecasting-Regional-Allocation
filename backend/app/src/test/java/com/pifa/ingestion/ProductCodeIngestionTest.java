package com.pifa.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.pifa.TestData;
import com.pifa.dataset.DatasetType;
import com.pifa.ingestion.IngestionService.Finding;
import com.pifa.ingestion.IngestionService.IngestRequest;
import com.pifa.ingestion.IngestionService.IngestResult;
import com.pifa.master.MasterDataService;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
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
 * Phase 7 optional {@code Product_code} column: a known code identifies the product even without a name; an unknown
 * code is an unknown product; a code and a name pointing at different products is PRODUCT_CODE_MISMATCH; the same
 * product listed by name and by code is a duplicate.
 */
@SpringBootTest
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
class ProductCodeIngestionTest {

    private static final String MONTH = "2037-07";
    private static final String A = "Test Code Printer A";
    private static final String B = "Test Code Printer B";

    @MockBean JwtDecoder jwtDecoder;
    @Autowired IngestionService ingestion;

    @BeforeAll
    static void setUp(@Autowired MasterDataService master, @Autowired JdbcClient jdbc, @Autowired TransactionTemplate tx) {
        cleanUp(jdbc, tx);
        UUID a = master.addProduct(A, "MANUAL");
        master.addProduct(B, "MANUAL");
        jdbc.sql("UPDATE product SET product_code = 'TC-A' WHERE id = :id").param("id", a).update();
        jdbc.sql("INSERT INTO planning_cycle (id, month) VALUES (:id, :m)").param("id", UUID.randomUUID()).param("m", MONTH).update();
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcClient jdbc, @Autowired TransactionTemplate tx) {
        TestData.wipeCycles(tx, jdbc, MONTH);
        jdbc.sql("DELETE FROM product WHERE product_name LIKE 'Test Code Printer %'").update();
    }

    @Test
    void codeMismatchUnknownCodeAndDuplicatesAreRejected() throws Exception {
        IngestResult r = ingest(new Object[][]{
            {null, 5, "TC-A"},            // code only: product A
            {B, 3, "TC-A"},               // B by name, A by code: mismatch
            {B, 2, "TC-NOPE"},            // unknown code
            {A, 1, null}});               // A again by name: duplicate of row 2
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.findings()).extracting(Finding::code).contains("PRODUCT_CODE_MISMATCH", "PRODUCT_UNKNOWN", "PRODUCT_DUPLICATE");
        assertThat(r.findings()).filteredOn(f -> f.code().equals("PRODUCT_UNKNOWN")).singleElement()
            .satisfies(f -> assertThat(f.message()).contains("TC-NOPE"));
    }

    @Test
    void knownCodeWithoutNameIsAccepted() throws Exception {
        IngestResult r = ingest(new Object[][]{{null, 7, " tc-a "}, {B, 4, null}});
        assertThat(r.status()).isNotEqualTo("REJECTED");
        assertThat(r.findings()).noneMatch(f -> f.severity().equals("ERROR"));
    }

    /** Ingests an inventory workbook with rows of (Product_name, Available_Qty, Product_code). */
    private IngestResult ingest(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Inventory");
            String[] h = {"Product_name", "Available_Qty", "As_Of_Date", "Planning_Month", "Product_code"};
            Row hr = s.createRow(0);
            for (int i = 0; i < h.length; i++) hr.createCell(i).setCellValue(h[i]);
            String asOf = LocalDate.now().minusDays(1).toString();
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 1);
                if (rows[r][0] != null) row.createCell(0).setCellValue((String) rows[r][0]);
                row.createCell(1).setCellValue(((Number) rows[r][1]).doubleValue());
                row.createCell(2).setCellValue(asOf);
                row.createCell(3).setCellValue(MONTH);
                if (rows[r][2] != null) row.createCell(4).setCellValue((String) rows[r][2]);
            }
            wb.write(out);
            return ingestion.ingest(new IngestRequest(out.toByteArray(), "INVENTORY_code_" + System.nanoTime() + ".xlsx",
                DatasetType.INVENTORY_UPLOAD, "UI", "pat.planner", "pat.planner"));
        }
    }
}
