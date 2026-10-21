package com.pifa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the {@code pifa.*} block in application.yml.
 *
 * <p>Spring binds the YAML values into these nested records at startup, so the rest of the code
 * reads {@code props.ingestion().maxRows()} instead of scattering string keys around.
 *
 * @param storage    where original files are kept
 * @param clamav     antivirus daemon settings
 * @param cycle      planning-cycle behaviour
 * @param synthetic  synthetic-data generator switch
 * @param ingestion  limits applied to every uploaded file
 * @param retention  how long soft-deleted versions are kept before an Admin may purge them
 * @param sftp       SFTP poller settings
 */
@ConfigurationProperties(prefix = "pifa")
public record PifaProperties(Storage storage, Clamav clamav, Cycle cycle, Synthetic synthetic,
                             Ingestion ingestion, Retention retention, Sftp sftp) {

    /** @param dir folder on disk for original files (Phase 1 uses local disk; S3/MinIO comes later) */
    public record Storage(String dir) {}

    /**
     * @param enabled whether files are scanned; when false a no-op scanner is used (dev only)
     * @param host    clamd host name
     * @param port    clamd TCP port (3310)
     */
    public record Clamav(boolean enabled, String host, int port) {}

    /** @param autoCreate create the planning cycle automatically when a forecast names an unknown month */
    public record Cycle(boolean autoCreate) {}

    /** @param enabled allow generating synthetic data; must be false in production (a startup guard enforces it) */
    public record Synthetic(boolean enabled) {}

    /**
     * @param maxFileBytes largest accepted file (PRD: 10 MB)
     * @param maxRows      most data rows accepted (PRD: 50,000)
     * @param maxQty       largest quantity allowed in a cell (PRD: 1,000,000)
     * @param spikeFactor  forecast above this multiple of 6-month average sales raises QTY_SPIKE
     */
    public record Ingestion(long maxFileBytes, int maxRows, long maxQty, double spikeFactor) {}

    /** @param purgeDays days a soft-deleted version must age before purge (PRD: 90) */
    public record Retention(int purgeDays) {}

    /**
     * SFTP poller settings (PRD 8.2, architecture.md section 5).
     *
     * @param enabled       true to start polling
     * @param host          SFTP server host
     * @param port          SFTP server port
     * @param username      account the poller logs in with (needs read/delete on the inbound folders, write on outbound/errors)
     * @param password      password for that account (dev only; use a key in real environments)
     * @param privateKey    path to an ed25519/RSA private key file, or empty to use the password
     * @param strictHostKey true to verify the server against {@code knownHosts} (required outside local development)
     * @param knownHosts    path to an OpenSSH known_hosts file used when {@code strictHostKey} is true
     * @param forecastDir   remote folder holding forecast files, e.g. {@code /inbound/forecast}
     * @param inventoryDir  remote folder holding inventory files
     * @param errorsDir     remote folder where error reports are written for the source system
     * @param archiveDir    remote folder where processed files are moved
     * @param pollMs        delay between polls in milliseconds (PRD: 60 s)
     */
    public record Sftp(boolean enabled, String host, int port, String username, String password, String privateKey,
                       boolean strictHostKey, String knownHosts, String forecastDir, String inventoryDir,
                       String errorsDir, String archiveDir, long pollMs) {}
}
