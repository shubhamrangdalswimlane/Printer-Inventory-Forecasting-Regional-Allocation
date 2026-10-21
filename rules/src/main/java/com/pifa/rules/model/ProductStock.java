package com.pifa.rules.model;

/**
 * Drools <em>fact</em>: the stock position of one printer model for one planning cycle.
 *
 * <p>A fact is a plain object that is inserted into a rule session. The rules read its getters
 * (in the {@code when} part of a rule) and may modify it (in the {@code then} part). Here the
 * inputs are immutable and the only field the rules change is {@link #classification}.
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code allocatableQty} = available stock minus the Planner's reserve (BR-AL-01).</li>
 *   <li>{@code totalDemand} = sum of the revised forecast across all regions.</li>
 * </ul>
 * Output: {@code classification}, which starts as {@code null} and is set by the rules.
 */
public class ProductStock {
    /** Printer model name; used for logging and explanations only. */
    private final String productName;
    /** Units that may be handed out (available minus reserve, never negative). */
    private final int allocatableQty;
    /** Sum of revised demand across all regions for this model. */
    private final int totalDemand;
    /** Set by the rules; {@code null} means "not classified yet". */
    private Classification classification;

    /**
     * Creates the fact with its inputs.
     *
     * @param productName    printer model name (informational)
     * @param allocatableQty units available to allocate; callers should pass a value &gt;= 0
     * @param totalDemand    total revised demand across regions
     */
    public ProductStock(String productName, int allocatableQty, int totalDemand) {
        this.productName = productName;
        this.allocatableQty = allocatableQty;
        this.totalDemand = totalDemand;
    }

    /** @return the printer model name */
    public String getProductName() { return productName; }

    /** @return units available to allocate (read by the rules) */
    public int getAllocatableQty() { return allocatableQty; }

    /** @return total revised demand across regions (read by the rules) */
    public int getTotalDemand() { return totalDemand; }

    /** @return the classification set by the rules, or {@code null} if no rule has fired yet */
    public Classification getClassification() { return classification; }

    /**
     * Called by the rules (through {@code modify}) to record the result.
     *
     * @param classification the outcome to store
     */
    public void setClassification(Classification classification) { this.classification = classification; }
}
