package com.pifa.rules.model;

/**
 * Fact: one region's allocated quantity of one product (proposed, approved or final), together with
 * the region's revised demand, for the allocation validation rules (PRD BR-VA-01..04).
 */
public class AllocationLine {
    private final String productId;
    private final String productName;
    private final String region;
    private final int qty;
    private final int demand;

    /**
     * @param productId   product key (matches {@link ProductStock#getProductId()})
     * @param productName printer model, used in messages
     * @param region      region code
     * @param qty         allocated quantity
     * @param demand      the region's revised demand for the product
     */
    public AllocationLine(String productId, String productName, String region, int qty, int demand) {
        this.productId = productId;
        this.productName = productName;
        this.region = region;
        this.qty = qty;
        this.demand = demand;
    }

    /** @return the product key */
    public String getProductId() { return productId; }
    /** @return the printer model name */
    public String getProductName() { return productName; }
    /** @return the region code */
    public String getRegion() { return region; }
    /** @return the allocated quantity */
    public int getQty() { return qty; }
    /** @return the region's revised demand */
    public int getDemand() { return demand; }
}
