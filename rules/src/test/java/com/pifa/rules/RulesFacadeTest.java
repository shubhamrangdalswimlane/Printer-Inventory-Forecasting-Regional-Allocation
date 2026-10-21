package com.pifa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.pifa.rules.model.Classification;
import org.junit.jupiter.api.Test;

class RulesFacadeTest {

    private final RulesFacade rules = new RulesFacade();

    @Test
    void demandAboveStockIsConstrained() {
        // PRD worked example: Model A, 100 units
        assertThat(rules.classify("Model A", 100, 140)).isEqualTo(Classification.CONSTRAINED);
    }

    @Test
    void demandEqualToStockIsSufficient() {
        assertThat(rules.classify("Model A", 100, 100)).isEqualTo(Classification.SUFFICIENT);
    }

    @Test
    void demandBelowStockIsSufficient() {
        assertThat(rules.classify("Model B", 500, 120)).isEqualTo(Classification.SUFFICIENT);
    }

    @Test
    void negativeAllocatableIsTreatedAsZero() {
        assertThat(rules.classify("Model C", -5, 10)).isEqualTo(Classification.CONSTRAINED);
    }
}
