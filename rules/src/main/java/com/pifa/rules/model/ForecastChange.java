package com.pifa.rules.model;

/**
 * Fact: one Regional Manager revision of a forecast cell (published quantity -> revised quantity).
 * Used by the change-control rules BR-VA-05 and BR-VA-06 (PRD 11.2).
 */
public class ForecastChange {
    private final String productName;
    private final String region;
    private final int publishedQty;
    private final int revisedQty;
    private final String reason;

    /**
     * @param productName  printer model, used in messages
     * @param region       region code, e.g. NORTH
     * @param publishedQty quantity frozen at Forecaster approval
     * @param revisedQty   quantity after the Regional Manager edit
     * @param reason       the manager's explanation; may be null or blank
     */
    public ForecastChange(String productName, String region, int publishedQty, int revisedQty, String reason) {
        this.productName = productName;
        this.region = region;
        this.publishedQty = publishedQty;
        this.revisedQty = revisedQty;
        this.reason = reason;
    }

    /** @return the printer model name */
    public String getProductName() { return productName; }
    /** @return the region code */
    public String getRegion() { return region; }
    /** @return the published quantity */
    public int getPublishedQty() { return publishedQty; }
    /** @return the revised quantity */
    public int getRevisedQty() { return revisedQty; }

    /** @return true if no reason (or only whitespace) was given; read by rules as {@code reasonBlank} */
    public boolean isReasonBlank() { return reason == null || reason.isBlank(); }

    /**
     * Size of the change as a percentage of the published quantity, always positive.
     * Published 0 with any increase counts as 1000% (a change from nothing is treated as very large).
     *
     * @return absolute percentage change; 0 when nothing changed
     */
    public double getAbsChangePct() {
        if (revisedQty == publishedQty) return 0;
        if (publishedQty == 0) return 1000;
        return Math.abs((double) (revisedQty - publishedQty)) / publishedQty * 100.0;
    }
}
