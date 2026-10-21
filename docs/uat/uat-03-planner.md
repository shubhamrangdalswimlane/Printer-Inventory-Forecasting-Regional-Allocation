# UAT-03 Planner

Preconditions: all regions have submitted (or the review deadline has passed). An inventory file is active. You are signed in as the Planner.

| # | Step | Expected | Pass/Fail |
|---|---|---|---|
| 1 | Before all regions submit, try **Proceed after deadline**. | Allowed only after the review deadline, and a reason is required. | |
| 2 | **Return selected** regions to review, with a reason. | Those regions are reopened and notified. The cycle is back in REGIONAL_REVIEW. | |
| 3 | When the cycle is in PLANNING, open the allocation grid. | Demand, available stock, reserve, allocatable quantity and classification are shown per product. | |
| 4 | Set a reserve on a product, with a reason. | Allocatable = available - reserve. Any existing proposal is marked stale. | |
| 5 | **Preview run**. | Proposed quantities appear but are not saved. The run time is shown (under 10 s for 10k products). | |
| 6 | Run and save the proposal. | Sufficient products get full demand. Constrained products are split by the region weights. Each product shows an explanation. | |
| 7 | Change one approved quantity by hand, with a reason. | Saves. The validation panel shows no errors while totals stay within stock. | |
| 8 | Approve the allocation. | State REGIONAL_FINALIZATION. An approved snapshot is stamped with the ruleset, parameter and inventory versions. | |
| 9 | **Reopen allocation** with a reason while a transfer is pending. | Back to PLANNING. The pending transfers are cancelled and the people involved are notified. | |
| 10 | Approve again. Watch the **Finalization monitor**. | It shows per-region status, pending transfers and the deadline, and updates live as regions act. | |
| 11 | Upload a new inventory file during PLANNING. | Reserves are kept. The proposal is marked stale, and you are notified. | |
| 12 | Export the full allocation (all regions). | The file contains all regions and both proposed and approved values. | |
