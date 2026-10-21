package com.pifa.rules.model;

/**
 * Output of the strategy rules: which {@link Strategy} to use for one product. The Java calculator
 * then turns the directive into region quantities.
 */
public class AllocationDirective {
    private final String productId;
    private final Strategy strategy;

    /**
     * @param productId the product key (matches {@link ProductStock#getProductId()})
     * @param strategy  the chosen strategy
     */
    public AllocationDirective(String productId, Strategy strategy) {
        this.productId = productId;
        this.strategy = strategy;
    }

    /** @return the product key */
    public String getProductId() { return productId; }
    /** @return the chosen strategy */
    public Strategy getStrategy() { return strategy; }
}
