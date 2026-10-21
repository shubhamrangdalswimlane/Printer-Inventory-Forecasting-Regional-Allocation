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
 * @param mail       e-mail notification settings
 *
 * <p>Business thresholds (change threshold, spike factor, region weights ...) are NOT here: they are
 * Admin-editable, versioned rule parameters (see {@code com.pifa.parameters.RuleParameterService}).
 */
@ConfigurationProperties(prefix = "pifa")
public record PifaProperties(Storage storage, Clamav clamav, Cycle cycle, Synthetic synthetic,
                             Ingestion ingestion, Retention retention, Sftp sftp, Mail mail) {

    /**
     * E-mail notifications sent from the outbox (PRD FR-COM-06).
     *
     * @param enabled         true to send mails; when false the outbox rows simply wait
     * @param from            sender address
     * @param addressTemplate recipient address for a role or role+region; {@code {target}} is replaced by
     *                        e.g. {@code regional_manager-north}. Development only: real recipients will be
     *                        looked up from Keycloak users.
     */
    public record Mail(boolean enabled, String from, String addressTemplate) {}

    /** @param dir folder on disk for original files (Phase 1 uses local disk; S3/MinIO comes later) */
    public record Storage(String dir) {}

    /**
     * ClamAV scanning. The connection is always TLS (clamd itself is plain TCP, so it runs behind a TLS
     * terminator); there is no plain-text option.
     *
     * @param enabled whether files are scanned; when false a no-op scanner is used (dev only)
     * @param host    host name of the TLS endpoint in front of clamd; must match the server certificate
     * @param port    port of that TLS endpoint (compose: 3311)
     * @param caCert  path of a PEM file with the CA / self-signed certificate to trust; empty = the JVM's trust store
     */
    public record Clamav(boolean enabled, String host, int port, String caCert) {}

    /** @param autoCreate create the planning cycle automatically when a forecast names an unknown month */
    public record Cycle(boolean autoCreate) {}

    /** @param enabled allow generating synthetic data; must be false in production (a startup guard enforces it) */
    public record Synthetic(boolean enabled) {}

    /**
     * @param maxFileBytes largest accepted file (PRD: 10 MB)
     * @param maxRows      most data rows accepted (PRD: 50,000)
     * @param maxQty       largest quantity allowed in a cell (PRD: 1,000,000)
     */
    public record Ingestion(long maxFileBytes, int maxRows, long maxQty) {}

    /**
     * Data retention (PRD 13).
     *
     * @param purgeDays         days a soft-deleted version must age before purge (PRD: 90)
     * @param autoPurgeDeleted  true: the nightly job purges soft-deleted versions once they are old enough
     * @param fileYears         original files of non-active versions are removed after this many years (PRD: 3)
     * @param auditYears        audit and change-log months older than this may be dropped (PRD: 7)
     * @param dropOldPartitions true to actually drop them (off by default: export first, see the runbook)
     */
    public record Retention(int purgeDays, boolean autoPurgeDeleted, int fileYears, int auditYears, boolean dropOldPartitions) {}

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
     * @param salesDir      remote folder of the monthly sales feed (Phase 7), e.g. {@code /inbound/sales}; empty = feed off
     * @param errorsDir     remote folder where error reports are written for the source system
     * @param archiveDir    remote folder where processed files are moved
     * @param pollMs        delay between polls in milliseconds (PRD: 60 s)
     */
    public record Sftp(boolean enabled, String host, int port, String username, String password, String privateKey,
                       boolean strictHostKey, String knownHosts, String forecastDir, String inventoryDir, String salesDir,
                       String errorsDir, String archiveDir, long pollMs) {}
}
