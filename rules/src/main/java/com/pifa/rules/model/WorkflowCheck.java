package com.pifa.rules.model;

/**
 * Fact: what the workflow knows when someone tries one of the later transitions. One class serves the
 * three guards, distinguished by {@link #getAction()}:
 * <ul>
 *   <li>{@code APPROVE_ALLOCATION} (BR-WF-02): needs an active inventory, a proposal, no allocation ERRORs;</li>
 *   <li>{@code FINALIZE_REGION} (BR-WF-03): the region has no pending transfers;</li>
 *   <li>{@code FINAL} (BR-WF-03): every region finalized, no pending transfers, no allocation ERRORs.</li>
 * </ul>
 */
public class WorkflowCheck {
    private final String action;
    private final String region;
    private final boolean hasActiveInventory;
    private final boolean hasProposal;
    private final int allocationErrors;
    private final int pendingTransfers;
    private final boolean allRegionsFinalized;

    /**
     * @param action              APPROVE_ALLOCATION, FINALIZE_REGION or FINAL
     * @param region              region concerned (FINALIZE_REGION), else null
     * @param hasActiveInventory  true if the cycle has an active inventory version
     * @param hasProposal         true if an allocation proposal was generated
     * @param allocationErrors    number of ERROR findings of the allocation validation
     * @param pendingTransfers    pending transfer requests (of the region, or of the whole cycle for FINAL)
     * @param allRegionsFinalized true if every region is FINALIZED
     */
    public WorkflowCheck(String action, String region, boolean hasActiveInventory, boolean hasProposal,
                         int allocationErrors, int pendingTransfers, boolean allRegionsFinalized) {
        this.action = action;
        this.region = region;
        this.hasActiveInventory = hasActiveInventory;
        this.hasProposal = hasProposal;
        this.allocationErrors = allocationErrors;
        this.pendingTransfers = pendingTransfers;
        this.allRegionsFinalized = allRegionsFinalized;
    }

    public String getAction() { return action; }
    public String getRegion() { return region; }
    public boolean isHasActiveInventory() { return hasActiveInventory; }
    public boolean isHasProposal() { return hasProposal; }
    public int getAllocationErrors() { return allocationErrors; }
    public int getPendingTransfers() { return pendingTransfers; }
    public boolean isAllRegionsFinalized() { return allRegionsFinalized; }
}
