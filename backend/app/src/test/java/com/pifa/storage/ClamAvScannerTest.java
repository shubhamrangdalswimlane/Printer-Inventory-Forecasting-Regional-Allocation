package com.pifa.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pifa.config.PifaProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tests {@link ClamAvScanner} against a real clamd daemon behind its TLS terminator (the {@code clamav} and
 * {@code clamav-tls} services of docker-compose). Skipped unless {@code PIFA_TEST_CLAMAV_HOST} is set.
 *
 * <p>Environment: {@code PIFA_TEST_CLAMAV_HOST} (name in the certificate, e.g. {@code localhost}),
 * {@code PIFA_TEST_CLAMAV_PORT} (default 3311), {@code PIFA_TEST_CLAMAV_CA} (PEM file of the CA to trust) and
 * optionally {@code PIFA_TEST_CLAMAV_ALT_HOST} (another address of the same server that is NOT in the certificate).
 * Uses the standard, harmless EICAR antivirus test string.
 */
@EnabledIfEnvironmentVariable(named = "PIFA_TEST_CLAMAV_HOST", matches = ".+")
class ClamAvScannerTest {

    /** The EICAR test string: every antivirus engine flags it, and it is not real malware. */
    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static ClamAvScanner scanner(String host, int port, String caCert) {
        var props = new PifaProperties(null, new PifaProperties.Clamav(true, host, port, caCert), null, null, null, null, null, null);
        return new ClamAvScanner(props);
    }

    private static ClamAvScanner trusted() {
        return scanner(env("PIFA_TEST_CLAMAV_HOST", "localhost"), Integer.parseInt(env("PIFA_TEST_CLAMAV_PORT", "3311")), System.getenv("PIFA_TEST_CLAMAV_CA"));
    }

    /** Over a verified TLS connection a clean file passes and the EICAR test file is detected. */
    @Test
    void cleanFilePassesAndEicarIsDetectedOverTls() {
        ClamAvScanner s = trusted();
        assertThat(s.isClean("just some harmless text".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(s.isClean(EICAR.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    /** A server certificate that is not trusted (no CA configured, so the JVM trust store applies) must be refused. */
    @Test
    void untrustedCertificateIsRejected() {
        ClamAvScanner s = scanner(env("PIFA_TEST_CLAMAV_HOST", "localhost"), Integer.parseInt(env("PIFA_TEST_CLAMAV_PORT", "3311")), null);
        assertThatThrownBy(() -> s.isClean(new byte[]{1})).isInstanceOf(IllegalStateException.class).hasMessageContaining("ClamAV scan failed");
    }

    /** A trusted certificate presented for the wrong host name must be refused (host-name verification). */
    @Test
    @EnabledIfEnvironmentVariable(named = "PIFA_TEST_CLAMAV_ALT_HOST", matches = ".+")
    void certificateForAnotherHostNameIsRejected() {
        ClamAvScanner s = scanner(System.getenv("PIFA_TEST_CLAMAV_ALT_HOST"), Integer.parseInt(env("PIFA_TEST_CLAMAV_PORT", "3311")), System.getenv("PIFA_TEST_CLAMAV_CA"));
        assertThatThrownBy(() -> s.isClean(new byte[]{1})).isInstanceOf(IllegalStateException.class);
    }

    /** An unreadable CA file fails at start-up, never silently falling back to something weaker. */
    @Test
    void missingCaFileFailsFast() {
        assertThatThrownBy(() -> scanner("localhost", 3311, "/does/not/exist.pem")).isInstanceOf(IllegalStateException.class);
    }

    /** If the daemon cannot be reached the scan must fail (not silently pass), so the pipeline can fail closed. */
    @Test
    void unreachableDaemonThrows() {
        assertThatThrownBy(() -> scanner("localhost", 1, System.getenv("PIFA_TEST_CLAMAV_CA")).isClean(new byte[]{1})).isInstanceOf(IllegalStateException.class);
    }
}
