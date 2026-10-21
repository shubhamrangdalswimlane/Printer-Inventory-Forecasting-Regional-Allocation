package com.pifa.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link VirusScanner} used when ClamAV is switched off ({@code pifa.clamav.enabled=false}, the
 * default for local development and unit tests). It accepts everything; never use it in production.
 */
@Component
@ConditionalOnProperty(name = "pifa.clamav.enabled", havingValue = "false", matchIfMissing = true)
public class NoopScanner implements VirusScanner {

    @Override
    public boolean isClean(byte[] data) {
        return true;
    }
}
