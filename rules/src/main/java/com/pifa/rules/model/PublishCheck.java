package com.pifa.rules.model;

/** Fact: what the workflow knows when the Forecaster tries to publish; input of guard rule BR-WF-01. */
public class PublishCheck {
    private final boolean hasActiveForecast;
    private final int errorCount;

    /**
     * @param hasActiveForecast true if the cycle has an active forecast upload version
     * @param errorCount        number of ERROR findings from the forecast validation
     */
    public PublishCheck(boolean hasActiveForecast, int errorCount) {
        this.hasActiveForecast = hasActiveForecast;
        this.errorCount = errorCount;
    }

    /** @return true if an active forecast version exists */
    public boolean isHasActiveForecast() { return hasActiveForecast; }
    /** @return the number of blocking validation errors */
    public int getErrorCount() { return errorCount; }
}
