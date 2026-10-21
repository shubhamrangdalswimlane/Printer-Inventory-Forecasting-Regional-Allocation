package com.pifa.rules.model;

/** Fact: one model's stock position and total revised demand across regions. */
public class ProductStock {
    private final String productName;
    private final int allocatableQty;
    private final int totalDemand;
    private Classification classification;

    public ProductStock(String productName, int allocatableQty, int totalDemand) {
        this.productName = productName;
        this.allocatableQty = allocatableQty;
        this.totalDemand = totalDemand;
    }

    public String getProductName() { return productName; }
    public int getAllocatableQty() { return allocatableQty; }
    public int getTotalDemand() { return totalDemand; }
    public Classification getClassification() { return classification; }
    public void setClassification(Classification classification) { this.classification = classification; }
}
