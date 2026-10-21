package com.pifa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.pifa.rules.model.Classification;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the classification rules, run through {@link RulesFacade}.
 *
 * <p>These tests use the real DRL file (no mocks), so they prove both that the rules compile and
 * that they behave as the PRD describes. Every test follows the same pattern: call
 * {@code classify(name, allocatable, demand)} and assert the returned {@link Classification}.
 */
class RulesFacadeTest {

    /** Built once per test class instance; compiling the rules is the slow part. */
    private final RulesFacade rules = new RulesFacade();

    /** Demand 140 against 100 units (the PRD's Model A) cannot be met, so it is constrained. */
    @Test
    void demandAboveStockIsConstrained() {
        assertThat(rules.classify("Model A", 100, 140)).isEqualTo(Classification.CONSTRAINED);
    }

    /** Boundary: demand exactly equal to stock is fully servable, so it is sufficient (not constrained). */
    @Test
    void demandEqualToStockIsSufficient() {
        assertThat(rules.classify("Model A", 100, 100)).isEqualTo(Classification.SUFFICIENT);
    }

    /** Plenty of stock for a small demand is sufficient. */
    @Test
    void demandBelowStockIsSufficient() {
        assertThat(rules.classify("Model B", 500, 120)).isEqualTo(Classification.SUFFICIENT);
    }

    /** Negative stock is clamped to 0, so any positive demand is constrained. */
    @Test
    void negativeAllocatableIsTreatedAsZero() {
        assertThat(rules.classify("Model C", -5, 10)).isEqualTo(Classification.CONSTRAINED);
    }
}
