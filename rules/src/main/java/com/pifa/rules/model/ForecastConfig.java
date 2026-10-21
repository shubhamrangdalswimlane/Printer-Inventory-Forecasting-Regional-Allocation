package com.pifa.rules.model;

/**
 * Fact: tunable limits for the forecast rules. Comes from configuration now and from the Admin
 * rule-parameters screen in Phase 3, so no number is hard-coded in a rule.
 */
public class ForecastConfig {
    private final double thresholdPct;
    private final double spikeFactor;
    private final long maxQty;

    /**
     * @param thresholdPct a Regional Manager change larger than this percent needs a reason (PRD: 20)
     * @param spikeFactor  a forecast above this multiple of 6-month average sales raises a warning (PRD: 3)
     * @param maxQty       largest quantity allowed in a cell (PRD: 1,000,000)
     */
    public ForecastConfig(double thresholdPct, double spikeFactor, long maxQty) {
        this.thresholdPct = thresholdPct;
        this.spikeFactor = spikeFactor;
        this.maxQty = maxQty;
    }

    /** @return the change threshold in percent */
    public double getThresholdPct() { return thresholdPct; }
    /** @return the spike multiple */
    public double getSpikeFactor() { return spikeFactor; }
    /** @return the maximum quantity */
    public long getMaxQty() { return maxQty; }
}
