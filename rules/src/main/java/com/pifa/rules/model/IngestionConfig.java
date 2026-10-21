package com.pifa.rules.model;

/**
 * Fact: the tunable limits for ingestion rules. They come from configuration (Admin-editable
 * rule parameters from Phase 3 onward), so rule logic never contains hard-coded numbers.
 */
public class IngestionConfig {
    private final long maxQty;
    private final double spikeFactor;

    /**
     * @param maxQty      largest quantity allowed in any cell (PRD: 1,000,000)
     * @param spikeFactor warn when a forecast exceeds this multiple of 6-month average sales (PRD: 3)
     */
    public IngestionConfig(long maxQty, double spikeFactor) {
        this.maxQty = maxQty;
        this.spikeFactor = spikeFactor;
    }

    /** @return the maximum allowed quantity */
    public long getMaxQty() { return maxQty; }
    /** @return the spike multiple */
    public double getSpikeFactor() { return spikeFactor; }
}
