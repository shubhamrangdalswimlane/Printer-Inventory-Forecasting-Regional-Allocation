package com.pifa.rules.model;

/**
 * Fact: one forecast cell (product x region) in the working copy, checked before the Forecaster publishes.
 * Drives the limit and spike checks of the Forecaster validation panel (PRD FR-FC-05).
 */
public class ForecastCell {
    private final String productName;
    private final String region;
    private final long qty;
    private final double avgSales6;

    /**
     * @param productName printer model, used in messages
     * @param region      region code
     * @param qty         the working-copy quantity
     * @param avgSales6   average monthly sales of the last 6 months for this product and region, or -1 if unknown
     */
    public ForecastCell(String productName, String region, long qty, double avgSales6) {
        this.productName = productName;
        this.region = region;
        this.qty = qty;
        this.avgSales6 = avgSales6;
    }

    /** @return the printer model name */
    public String getProductName() { return productName; }
    /** @return the region code */
    public String getRegion() { return region; }
    /** @return the working-copy quantity */
    public long getQty() { return qty; }
    /** @return 6-month average sales, or -1 if unknown */
    public double getAvgSales6() { return avgSales6; }
}
