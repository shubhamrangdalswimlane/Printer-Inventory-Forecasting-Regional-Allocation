package com.pifa.ingestion;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.pifa.config.PifaProperties;
import com.pifa.common.ApiException;
import com.pifa.dataset.DatasetType;
import com.pifa.golive.SalesHistoryImportService;
import com.pifa.notify.NotificationService;
import com.pifa.ingestion.IngestionService.IngestRequest;
import com.pifa.ingestion.IngestionService.IngestResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the SFTP inbound folders and feeds every finished {@code *.xlsx} file into
 * {@link IngestionService} (PRD 8.2, architecture.md section 5).
 *
 * <p>Folder decides the dataset type: {@code /inbound/forecast} becomes a forecast upload (Forecaster
 * dashboard), {@code /inbound/inventory} an inventory upload (Planner dashboard), and - when
 * {@code pifa.sftp.sales-dir} is set (Phase 7 sales/actuals feed) - {@code /inbound/sales} a monthly sales file
 * imported into the sales history by {@link SalesHistoryImportService} (all-or-nothing; a rejected file gets an
 * error report in the errors folder and the Admins are notified).
 *
 * <p>Safety properties:
 * <ul>
 *   <li><b>Partial uploads:</b> only {@code *.xlsx} names are considered (producers upload as {@code .part}
 *       and rename), and a file is processed only when its size and modification time were identical on
 *       two consecutive polls.</li>
 *   <li><b>Exactly once:</b> after processing, the file is moved to the archive folder; the content-hash
 *       check in the ingestion pipeline also rejects an identical file arriving again.</li>
 *   <li><b>Single runner:</b> a Postgres advisory lock held on a dedicated connection ensures only one
 *       application instance polls at a time.</li>
 *   <li><b>Retry:</b> if processing throws (for example the database is down) the file stays in place and
 *       is tried again on the next poll.</li>
 * </ul>
 * Active only when {@code pifa.sftp.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "pifa.sftp.enabled", havingValue = "true")
public class SftpIngestPoller {

    private static final Logger log = LoggerFactory.getLogger(SftpIngestPoller.class);
    /** Arbitrary constant key of the advisory lock that makes polling single-runner. */
    private static final long LOCK_KEY = 7002L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private final PifaProperties.Sftp cfg;
    private final long maxBytes;
    private final IngestionService ingestion;
    private final DataSource dataSource;
    private final com.pifa.ops.PifaMetrics metrics;
    private final SalesHistoryImportService sales;
    private final NotificationService notifications;
    /** name+folder -> "size:mtime" seen on the previous poll, used for the size-stable check. */
    private final Map<String, String> previous = new HashMap<>();
    @org.springframework.beans.factory.annotation.Value("${pifa.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    public SftpIngestPoller(PifaProperties props, IngestionService ingestion, DataSource dataSource, com.pifa.ops.PifaMetrics metrics,
                            SalesHistoryImportService sales, NotificationService notifications) {
        this.metrics = metrics;
        this.sales = sales;
        this.notifications = notifications;
        this.cfg = props.sftp();
        this.maxBytes = props.ingestion().maxFileBytes();
        this.ingestion = ingestion;
        this.dataSource = dataSource;
    }

    /** Scheduled entry point; the delay between polls comes from {@code pifa.sftp.poll-ms}. Never throws. */
    @Scheduled(fixedDelayString = "${pifa.sftp.poll-ms:60000}", initialDelayString = "${pifa.sftp.poll-ms:60000}")
    public void scheduledPoll() {
        if (!jobsEnabled) return; // api pods do not poll; worker pods do
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.error("SFTP poll failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs one poll over both inbound folders.
     *
     * @return number of files processed (accepted or rejected) in this poll; 0 if another instance
     *         holds the lock or nothing was ready
     */
    public int pollOnce() {
        try (Connection lock = dataSource.getConnection()) {
            boolean got = Boolean.TRUE.equals(scalar(lock, "SELECT pg_try_advisory_lock(" + LOCK_KEY + ")"));
            if (!got) return 0; // another instance is polling
            try {
                return pollFolders();
            } finally {
                scalar(lock, "SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot acquire poll lock", e);
        }
    }

    /** Opens one SFTP session and processes both folders. */
    private int pollFolders() {
        Session session = null;
        ChannelSftp sftp = null;
        try {
            session = connect();
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10_000);
            int n = pollFolder(sftp, cfg.forecastDir(), archiveName(DatasetType.FORECAST_UPLOAD), datasetHandler(DatasetType.FORECAST_UPLOAD, "sftp-forecast"))
                + pollFolder(sftp, cfg.inventoryDir(), archiveName(DatasetType.INVENTORY_UPLOAD), datasetHandler(DatasetType.INVENTORY_UPLOAD, "sftp-inventory"));
            if (salesEnabled()) n += pollFolder(sftp, cfg.salesDir(), "sales", this::importSales);
            status(true, "Last poll OK, " + n + " file(s) processed");
            return n;
        } catch (JSchException e) {
            // Recorded for the Admin dashboard (FR-AD-04 "SFTP connectivity health") and logged for alerting.
            status(false, "SFTP connection failed: " + e.getMessage());
            if (metrics != null) metrics.sftpPollError();
            throw new IllegalStateException("SFTP connection failed: " + e.getMessage(), e);
        } finally {
            if (sftp != null) sftp.disconnect();
            if (session != null) session.disconnect();
        }
    }

    /** Creates an authenticated SSH session using a key if configured, otherwise the password. */
    private Session connect() throws JSchException {
        JSch jsch = new JSch();
        if (cfg.privateKey() != null && !cfg.privateKey().isBlank()) jsch.addIdentity(cfg.privateKey());
        if (cfg.strictHostKey()) {
            if (cfg.knownHosts() == null || cfg.knownHosts().isBlank()) {
                throw new JSchException("strict-host-key is on but pifa.sftp.known-hosts is not set");
            }
            jsch.setKnownHosts(cfg.knownHosts());
        }
        Session s = jsch.getSession(cfg.username(), cfg.host(), cfg.port());
        if (cfg.password() != null && !cfg.password().isBlank()) s.setPassword(cfg.password());
        Properties conf = new Properties();
        conf.put("StrictHostKeyChecking", cfg.strictHostKey() ? "yes" : "no"); // "no" is for local development only
        s.setConfig(conf);
        s.connect(10_000);
        return s;
    }

    /**
     * Processes the ready files of one folder.
     *
     * @param sftp    connected channel
     * @param dir     remote folder
     * @param archive archive sub-folder for processed files
     * @param handler what to do with each finished file
     * @return number of files processed
     */
    @SuppressWarnings("unchecked")
    private int pollFolder(ChannelSftp sftp, String dir, String archive, FileHandler handler) {
        int processed = 0;
        try {
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(dir);
            for (ChannelSftp.LsEntry e : entries) {
                String name = e.getFilename();
                SftpATTRS a = e.getAttrs();
                if (a.isDir() || !name.toLowerCase().endsWith(".xlsx")) continue; // ignores *.part and other files
                String key = dir + "/" + name;
                String fingerprint = a.getSize() + ":" + a.getMTime();
                boolean stable = fingerprint.equals(previous.get(key));
                previous.put(key, fingerprint);
                if (!stable) continue; // first time seen or still changing: wait for the next poll
                try {
                    processFile(sftp, dir, name, a.getSize(), archive, handler);
                    previous.remove(key);
                    processed++;
                } catch (RuntimeException | SftpException ex) {
                    log.error("Processing {} failed, will retry next poll: {}", key, ex.getMessage(), ex);
                }
            }
        } catch (SftpException e) {
            log.warn("Cannot list {}: {}", dir, e.getMessage());
        }
        return processed;
    }

    /** What to do with one finished file. */
    @FunctionalInterface
    private interface FileHandler {
        /**
         * @param bytes the file (for an oversized file: a buffer one byte over the limit, so it is rejected by size)
         * @param name  the file name
         * @return the error report (CSV bytes) if the file was rejected, or null if it was accepted
         * @throws RuntimeException to leave the file in place and retry on the next poll (e.g. database down)
         */
        byte[] handle(byte[] bytes, String name);
    }

    /** @return the archive sub-folder of a dataset type, e.g. {@code forecast_upload} (unchanged since Phase 1) */
    private static String archiveName(DatasetType type) {
        return type.name().toLowerCase();
    }

    /**
     * @param type    FORECAST_UPLOAD or INVENTORY_UPLOAD
     * @param account the SFTP account recorded as the file's source
     * @return a handler that runs the forecast/inventory ingestion pipeline
     */
    private FileHandler datasetHandler(DatasetType type, String account) {
        return (bytes, name) -> {
            IngestResult r = ingestion.ingest(new IngestRequest(bytes, name, type, "SFTP", account, account));
            log.info("SFTP file {} -> {} ({})", name, r.status(), r.label());
            return "REJECTED".equals(r.status()) ? IngestionJobService.toCsv(r.findings()) : null;
        };
    }

    /** @return true when the sales feed folder is configured */
    private boolean salesEnabled() {
        return cfg.salesDir() != null && !cfg.salesDir().isBlank();
    }

    /**
     * Sales feed handler (Phase 7): imports a monthly sales workbook into the sales history.
     * File-level problems (wrong layout, infected) and row problems reject the file; an unavailable virus scanner
     * is rethrown so the file is retried. Every rejection notifies the Admins.
     *
     * @param bytes the file
     * @param name  its name
     * @return the error report CSV (one problem per line) if rejected, or null if imported
     */
    private byte[] importSales(byte[] bytes, String name) {
        long start = System.nanoTime();
        List<String> problems;
        if (bytes.length > maxBytes) {
            problems = List.of("File is larger than the limit of " + maxBytes + " bytes");
        } else {
            try {
                problems = sales.importSales(bytes, name, "SFTP", "sftp-sales").problems();
            } catch (ApiException e) {
                if (e.getStatus().is5xxServerError()) throw e; // scanner unavailable: retry next poll
                problems = List.of(e.getMessage());
            }
        }
        boolean ok = problems.isEmpty();
        if (metrics != null) metrics.ingestion("SALES", ok ? "VALID" : "REJECTED", "SFTP", System.nanoTime() - start);
        log.info("SFTP sales file {} -> {}", name, ok ? "IMPORTED" : "REJECTED (" + problems.size() + " problems)");
        if (ok) return null;
        notifications.notifyRole("admin", null, "Sales file " + name + " was rejected",
            problems.size() + " problem(s), first: " + problems.get(0) + ". The error report is in the SFTP errors folder.", null);
        StringBuilder csv = new StringBuilder("problem\r\n");
        for (String p : problems) csv.append('"').append(ExcelFiles.safe(p).replace("\"", "\"\"")).append("\"\r\n");
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Reads, processes, reports on and archives one file. */
    private void processFile(ChannelSftp sftp, String dir, String name, long size, String archive, FileHandler handler) throws SftpException {
        String path = dir + "/" + name;
        byte[] bytes;
        if (size > maxBytes) {
            bytes = new byte[(int) Math.min(maxBytes + 1, Integer.MAX_VALUE)]; // oversized: ingestion rejects by length without reading it all
        } else {
            try (InputStream in = sftp.get(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                bytes = out.toByteArray();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read " + path, e);
            }
        }
        byte[] errors = handler.handle(bytes, name);
        if (errors != null) {
            String base = name.substring(0, name.length() - 5);
            put(sftp, cfg.errorsDir() + "/" + base + "_errors.csv", errors);
        }
        archive(sftp, path, archive, name, size > maxBytes ? null : bytes);
    }

    /**
     * Moves the processed file to {@code <archive>/<type>/<timestamp>_<name>}, creating folders as needed.
     *
     * <p>A plain SFTP {@code rename} is tried first. It fails when the archive is on a different
     * filesystem than the inbound folder, so the fallback copies the bytes to the archive and then
     * deletes the original. Oversized files (whose bytes were never read) are only deleted.
     *
     * @param sftp  connected channel
     * @param path  remote path of the processed file
     * @param archive archive sub-folder name
     * @param name  file name
     * @param bytes the file content for the copy fallback, or null if it was not read
     */
    private void archive(ChannelSftp sftp, String path, String archive, String name, byte[] bytes) throws SftpException {
        String folder = cfg.archiveDir() + "/" + archive;
        String target = folder + "/" + STAMP.format(OffsetDateTime.now(ZoneOffset.UTC)) + "_" + name;
        try {
            mkdirs(sftp, folder);
            sftp.rename(path, target);
        } catch (SftpException renameFailed) {
            if (bytes != null) put(sftp, target, bytes);
            sftp.rm(path);
        }
    }

    /** Uploads bytes to a remote path, creating the parent folders. */
    private void put(ChannelSftp sftp, String path, byte[] data) throws SftpException {
        mkdirs(sftp, path.substring(0, path.lastIndexOf('/')));
        sftp.put(new ByteArrayInputStream(data), path);
    }

    /** {@code mkdir -p} for SFTP: creates each missing segment of an absolute path. */
    private static void mkdirs(ChannelSftp sftp, String dir) throws SftpException {
        StringBuilder cur = new StringBuilder();
        for (String part : dir.split("/")) {
            if (part.isEmpty()) continue;
            cur.append('/').append(part);
            try {
                sftp.stat(cur.toString());
            } catch (SftpException e) {
                sftp.mkdir(cur.toString());
            }
        }
    }

    /** Stores the poll outcome in {@code system_status} (key {@code sftp.poll}). Never throws. */
    private void status(boolean ok, String text) {
        try {
            new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "INSERT INTO system_status (key, value, ok, updated_at) VALUES ('sftp.poll', ?, ?, now()) "
                    + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, ok = EXCLUDED.ok, updated_at = now()", text, ok);
        } catch (RuntimeException e) {
            log.warn("Could not record SFTP status: {}", e.getMessage());
        }
    }

    /** Runs a single-value query on a raw connection and returns the first column of the first row. */
    private static Object scalar(Connection c, String sql) throws java.sql.SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }

    /** @return the names this poller treats as inbound folders (used by tests to know where to drop files) */
    public List<String> inboundDirs() {
        return salesEnabled() ? List.of(cfg.forecastDir(), cfg.inventoryDir(), cfg.salesDir()) : List.of(cfg.forecastDir(), cfg.inventoryDir());
    }
}
