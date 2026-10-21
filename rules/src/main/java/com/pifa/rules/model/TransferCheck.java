package com.pifa.rules.model;

/**
 * Fact: everything the transfer rules (PRD BR-TR-01..04) need to judge one transfer of units of one
 * product from a donor region to a receiving region. Java fills it from the current final allocation;
 * the rules decide what is allowed. It is checked when a transfer is requested and again when it is
 * accepted, because the allocation may have changed in between.
 */
public class TransferCheck {
    private final String productId;
    private final String productName;
    private final String fromRegion;
    private final String toRegion;
    private final int qty;
    private final int donorAllocation;
    private final int receiverAllocation;
    private final int receiverDemand;
    private final int floor;
    private final boolean donorFinalized;
    private final boolean receiverFinalized;
    private final int modelTotalBefore;
    private final int modelTotalAfter;

    /**
     * @param productId          product key
     * @param productName        printer model, for messages
     * @param fromRegion         donor region code
     * @param toRegion           receiving region code
     * @param qty                units to move
     * @param donorAllocation    donor's current final allocation
     * @param receiverAllocation receiver's current final allocation
     * @param receiverDemand     receiver's revised demand
     * @param floor              minimum a donor must keep (rule parameter, default 0)
     * @param donorFinalized     true if the donor region is already finalized
     * @param receiverFinalized  true if the receiving region is already finalized
     * @param modelTotalBefore   sum over all regions before the transfer
     * @param modelTotalAfter    sum over all regions after applying it (must be equal)
     */
    public TransferCheck(String productId, String productName, String fromRegion, String toRegion, int qty,
                         int donorAllocation, int receiverAllocation, int receiverDemand, int floor,
                         boolean donorFinalized, boolean receiverFinalized, int modelTotalBefore, int modelTotalAfter) {
        this.productId = productId;
        this.productName = productName;
        this.fromRegion = fromRegion;
        this.toRegion = toRegion;
        this.qty = qty;
        this.donorAllocation = donorAllocation;
        this.receiverAllocation = receiverAllocation;
        this.receiverDemand = receiverDemand;
        this.floor = floor;
        this.donorFinalized = donorFinalized;
        this.receiverFinalized = receiverFinalized;
        this.modelTotalBefore = modelTotalBefore;
        this.modelTotalAfter = modelTotalAfter;
    }

    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getFromRegion() { return fromRegion; }
    public String getToRegion() { return toRegion; }
    public int getQty() { return qty; }
    public int getDonorAllocation() { return donorAllocation; }
    public int getReceiverAllocation() { return receiverAllocation; }
    public int getReceiverDemand() { return receiverDemand; }
    public int getFloor() { return floor; }
    public boolean isDonorFinalized() { return donorFinalized; }
    public boolean isReceiverFinalized() { return receiverFinalized; }
    public int getModelTotalBefore() { return modelTotalBefore; }
    public int getModelTotalAfter() { return modelTotalAfter; }
    /** @return true if donor and receiver are the same region (read by rules as {@code sameRegion}) */
    public boolean isSameRegion() { return fromRegion.equals(toRegion); }
    /** @return units the donor may give away: its allocation minus the floor, never negative */
    public int getDonorSpare() { return Math.max(donorAllocation - floor, 0); }
}
