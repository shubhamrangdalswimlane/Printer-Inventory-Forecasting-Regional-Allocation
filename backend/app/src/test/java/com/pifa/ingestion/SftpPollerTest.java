package com.pifa.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetType;
import com.pifa.security.CurrentUser;
import com.pifa.synthetic.SyntheticDataService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

/**
 * Tests {@link SftpIngestPoller} against a real SFTP server (the {@code sftp} service from
 * infra/docker-compose.yml). Skipped unless the environment variable {@code PIFA_TEST_SFTP_HOST} is set,
 * so a plain {@code mvn verify} without Docker still passes.
 *
 * <p>Story: a producer drops a valid forecast and a broken file; the first poll must ignore both
 * (size-stable rule), the second must ingest them, archive them and write an error CSV for the broken one.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "PIFA_TEST_SFTP_HOST", matches = ".+")
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
    "pifa.storage.dir=target/test-files",
    "pifa.sftp.enabled=true",
    "pifa.sftp.strict-host-key=false",
    "pifa.sftp.host=${PIFA_TEST_SFTP_HOST:localhost}",
    "pifa.sftp.port=${PIFA_TEST_SFTP_PORT:22}",
    "pifa.sftp.sales-dir=/inbound/sales",
    "pifa.sftp.poll-ms=3600000"   // effectively off: the test calls pollOnce() itself
})
class SftpPollerTest {

    private static final String MONTH = "2032-02";
    private static final CurrentUser ADMIN = new CurrentUser("alex.admin", Set.of("admin"));

    @MockBean JwtDecoder jwtDecoder;
    @Autowired SftpIngestPoller poller;
    @Autowired SyntheticDataService synthetic;
    @Autowired DatasetRepository repo;
    @Autowired com.pifa.master.MasterDataService master;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    @Test
    void picksUpStableFilesIngestsArchivesAndReportsErrors() throws Exception {
        com.pifa.TestData.wipeCycles(tx, jdbc, MONTH);
        synthetic.purgeAll(ADMIN);
        synthetic.seedMaster(1);
        byte[][] pair = synthetic.buildPair(MONTH, 1);
        byte[] bad = synthetic.negativePack(MONTH, pair[0]).stream().filter(f -> f.name().equals("unknown_product")).findFirst().orElseThrow().bytes();

        String host = System.getenv("PIFA_TEST_SFTP_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("PIFA_TEST_SFTP_PORT", "22"));
        // Producer uploads: a .part file (must be ignored), a valid forecast, and a broken forecast.
        try (Remote r = new Remote("sftp-forecast", "forecast-dev", host, port)) {
            r.put("/inbound/forecast/FORECAST_partial.xlsx.part", new byte[]{1, 2, 3});
            r.put("/inbound/forecast/FORECAST_" + MONTH + "_good.xlsx", pair[0]);
            r.put("/inbound/forecast/FORECAST_" + MONTH + "_bad.xlsx", bad);
        }

        assertThat(poller.pollOnce()).as("first poll only records size/mtime").isZero();
        assertThat(poller.pollOnce()).as("second poll processes the two stable .xlsx files").isEqualTo(2);

        var cycle = repo.findCycleByMonth(MONTH).orElseThrow();
        var versions = repo.list(DatasetType.FORECAST_UPLOAD, cycle.id(), false);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).source()).isEqualTo("SFTP");
        assertThat(versions.get(0).createdBy()).isEqualTo("sftp-forecast");

        try (Remote worker = new Remote("pifa-worker", "worker-dev", host, port)) {
            assertThat(worker.list("/inbound/forecast")).as("processed files leave the inbound folder").noneMatch(n -> n.endsWith(".xlsx"));
            assertThat(worker.list("/inbound/forecast")).as(".part file is left alone").contains("FORECAST_partial.xlsx.part");
            assertThat(worker.list("/outbound/errors")).contains("FORECAST_" + MONTH + "_bad_errors.csv");
            assertThat(worker.get("/outbound/errors/FORECAST_" + MONTH + "_bad_errors.csv")).contains("PRODUCT_UNKNOWN");
            worker.delete("/inbound/forecast/FORECAST_partial.xlsx.part");
        }
        com.pifa.TestData.wipeCycles(tx, jdbc, MONTH);
        synthetic.purgeAll(ADMIN);
    }

    /**
     * Phase 7 sales feed: a valid sales file is imported into sales_history with source SFTP and archived;
     * a file naming an unknown product is rejected as a whole, with an error CSV for the producer.
     */
    @Test
    void salesFeedImportsGoodFilesAndRejectsBadOnes() throws Exception {
        synthetic.purgeAll(ADMIN);
        String product = "Test Sales Feed Printer S1";
        java.util.UUID pid = master.addProduct(product, "MANUAL");
        try {
            String[] h = {"Product_name", "Month", "North", "East", "West", "South"};
            byte[] good = sheet(h, new Object[][]{{product, "2025-03", 7, 8, 9, 10}});
            byte[] bad = sheet(h, new Object[][]{{product, "2025-04", 1, 1, 1, 1}, {"No Such Printer", "2025-04", 1, 1, 1, 1}});
            String host = System.getenv("PIFA_TEST_SFTP_HOST");
            int port = Integer.parseInt(System.getenv().getOrDefault("PIFA_TEST_SFTP_PORT", "22"));
            try (Remote r = new Remote("sftp-sales", "sales-dev", host, port)) {
                r.put("/inbound/sales/SALES_good.xlsx", good);
                r.put("/inbound/sales/SALES_bad.xlsx", bad);
            }
            poller.pollOnce();
            assertThat(poller.pollOnce()).isEqualTo(2);

            assertThat(jdbc.sql("SELECT COALESCE(SUM(qty), 0) FROM sales_history WHERE product_id = :p AND source = 'SFTP'")
                .param("p", pid).query(Long.class).single()).as("only the good file was imported").isEqualTo(34);
            try (Remote worker = new Remote("pifa-worker", "worker-dev", host, port)) {
                assertThat(worker.list("/inbound/sales")).noneMatch(n -> n.endsWith(".xlsx"));
                assertThat(worker.get("/outbound/errors/SALES_bad_errors.csv")).contains("No Such Printer");
                worker.delete("/outbound/errors/SALES_bad_errors.csv");
            }
        } finally {
            jdbc.sql("DELETE FROM sales_history WHERE product_id = :p").param("p", pid).update();
            jdbc.sql("DELETE FROM product WHERE id = :p").param("p", pid).update();
        }
    }

    /** @return a one-sheet "Sales" workbook with the given header and rows */
    private static byte[] sheet(String[] headers, Object[][] rows) throws Exception {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var s = wb.createSheet("Sales");
            var hr = s.createRow(0);
            for (int i = 0; i < headers.length; i++) hr.createCell(i).setCellValue(headers[i]);
            for (int r = 0; r < rows.length; r++) {
                var row = s.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else row.createCell(c).setCellValue(rows[r][c].toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Minimal SFTP client for the test (upload, list, read, delete). */
    private static final class Remote implements AutoCloseable {
        private final Session session;
        private final ChannelSftp ch;

        Remote(String user, String pass, String host, int port) throws Exception {
            session = new JSch().getSession(user, host, port);
            session.setPassword(pass);
            Properties p = new Properties();
            p.put("StrictHostKeyChecking", "no");
            session.setConfig(p);
            session.connect(10_000);
            ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(10_000);
        }

        void put(String path, byte[] data) throws Exception { ch.put(new ByteArrayInputStream(data), path); }

        @SuppressWarnings("unchecked")
        java.util.List<String> list(String dir) throws Exception {
            Vector<ChannelSftp.LsEntry> v = ch.ls(dir);
            return v.stream().map(ChannelSftp.LsEntry::getFilename).toList();
        }

        String get(String path) throws Exception {
            try (var in = ch.get(path); var out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toString();
            }
        }

        void delete(String path) throws Exception { ch.rm(path); }

        @Override public void close() { ch.disconnect(); session.disconnect(); }
    }
}
