package com.pifa.rules.model;

/**
 * Result of classifying one printer model against its available stock (PRD rule BR-AL-02).
 *
 * <p>This enum is written onto a {@link ProductStock} fact by the Drools rules in
 * {@code pifa/rules/allocation/strategy/classification.drl}. Later phases use it to choose
 * the allocation strategy:
 * <ul>
 *   <li>{@link #CONSTRAINED} - total regional demand is larger than allocatable stock, so the
 *       stock must be shared (default: 25% per region as a baseline).</li>
 *   <li>{@link #SUFFICIENT} - stock covers total demand, so every region gets what it asked for.</li>
 * </ul>
 */
public enum Classification {
    /** Total revised demand across all regions is greater than allocatable stock. */
    CONSTRAINED,
    /** Total revised demand is less than or equal to allocatable stock. */
    SUFFICIENT
}
