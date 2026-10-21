# Planner quick guide

**Your job each month:** turn the revised forecast and the stock into an allocation, approve it, and see it through to final.

1. **Wait for the regions** (cycle state REGIONAL_REVIEW). The cycle bar shows who has submitted. After the review
   deadline you may **Proceed after deadline**, giving a reason. **Return selected** sends regions back to review.
2. **Check the stock** (PLANNING). The latest inventory file is applied automatically. Set a **Reserve** where stock
   must be held back. Allocatable stock is available stock minus the reserve.
3. **Preview run** shows what the rules would propose without saving anything. Run and save the proposal when you are happy with it:
   - **Sufficient** product (stock covers demand): every region gets its full demand.
   - **Constrained** product: stock is split by the region weights (rule parameters) and never given above demand
     when cap-at-demand is on. The explanation column says exactly what happened.
4. **Adjust** approved quantities by hand where needed, giving a reason. The validation panel must show no errors.
5. **Approve** the allocation. Regions can now transfer and finalize.
   **Reopen allocation** takes the cycle back to PLANNING, and any pending transfers are cancelled.
6. **Finalization monitor**: follow each region's status, pending transfers and the deadline. It updates live.

Rules that surprise people:
- A new inventory file during PLANNING marks your proposal **stale**. Run it again. Your reserves are kept.
- Rule parameters (weights, threshold, floor, cap-at-demand) are versioned. A proposal records the version it used.
