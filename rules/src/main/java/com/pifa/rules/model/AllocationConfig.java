package com.pifa.rules.model;

/**
 * Fact: the allocation policy switches the strategy rules need. Comes from the Admin-editable rule
 * parameters. Inserting this fact is what enables the strategy rules; without it they never fire
 * (so a plain classification call does not produce directives).
 */
public class AllocationConfig {
    private final boolean capAtDemand;

    /** @param capAtDemand true to cap each constrained region at its demand (PRD BR-AL-06; default off) */
    public AllocationConfig(boolean capAtDemand) {
        this.capAtDemand = capAtDemand;
    }

    /** @return whether cap-at-demand is on */
    public boolean isCapAtDemand() { return capAtDemand; }
}
