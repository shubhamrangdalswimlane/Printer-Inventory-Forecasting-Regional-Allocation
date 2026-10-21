package com.pifa.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pifa.config.PifaProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tests {@link ClamAvScanner} against a real clamd daemon (the {@code clamav} service of docker-compose).
 * Skipped unless {@code PIFA_TEST_CLAMAV_HOST} is set. Uses the standard, harmless EICAR antivirus test string.
 */
@EnabledIfEnvironmentVariable(named = "PIFA_TEST_CLAMAV_HOST", matches = ".+")
class ClamAvScannerTest {

    /** The EICAR test string: every antivirus engine flags it, and it is not real malware. */
    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private ClamAvScanner scanner(int port) {
        var props = new PifaProperties(null, new PifaProperties.Clamav(true, System.getenv("PIFA_TEST_CLAMAV_HOST"), port), null, null, null, null, null, null, null);
        return new ClamAvScanner(props);
    }

    @Test
    void cleanFilePassesAndEicarIsDetected() {
        ClamAvScanner s = scanner(3310);
        assertThat(s.isClean("just some harmless text".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(s.isClean(EICAR.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    /** If the daemon cannot be reached the scan must fail (not silently pass), so the pipeline can fail closed. */
    @Test
    void unreachableDaemonThrows() {
        assertThatThrownBy(() -> scanner(1).isClean(new byte[]{1})).isInstanceOf(IllegalStateException.class);
    }
}
