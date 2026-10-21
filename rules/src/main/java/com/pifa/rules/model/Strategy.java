package com.pifa.rules.model;

/**
 * How a product's stock is split across regions (PRD 11.1). The rules choose the strategy; the Java
 * {@code AllocationCalculator} does the arithmetic.
 */
public enum Strategy {
    /** Sufficient stock: every region gets its revised demand (BR-AL-03). */
    FULL_DEMAND,
    /** Constrained stock: split by region weights, 25% each by default (BR-AL-04, BR-AL-05). */
    WEIGHTED_BASELINE,
    /** Constrained stock with cap-at-demand on: no region gets more than it asked for (BR-AL-06). */
    WEIGHTED_BASELINE_CAPPED
}
