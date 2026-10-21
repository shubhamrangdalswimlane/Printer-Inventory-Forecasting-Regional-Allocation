# UAT-02 Regional Manager (run once per region)

Preconditions: the cycle is in REGIONAL_REVIEW (after UAT-01 step 13). You are signed in as the Regional Manager of your region.

| # | Step | Expected | Pass/Fail |
|---|---|---|---|
| 1 | Open the dashboard. | Only your region's column is editable. Other regions are read-only or hidden. | |
| 2 | Open **Sales analysis** for a product. | Averages over 3, 6, 12 and 24 months, the same month last year, and a 12-month trend chart. | |
| 3 | Revise a quantity by less than the change threshold, with no reason. | The value saves. | |
| 4 | Revise a quantity by more than the change threshold, with no reason. | Blocked: a reason is required. | |
| 5 | Repeat step 4 with a reason. | The value saves. The reason appears in **History**. | |
| 6 | Submit your region. | Your region shows SUBMITTED, and the Planner sees it on the monitor. | |
| 7 | (After the Planner approves the allocation, UAT-03 step 8) Open the allocation for your region. | Your allocated quantities and fill rate are shown. | |
| 8 | **Request** units of a product from another region, with a reason. | The transfer is PENDING. The other region's manager is notified. | |
| 9 | As the other region: **Accept** the request. | Both regions' quantities change by the amount. Totals are unchanged. | |
| 10 | Make an **Offer** to another region. That region uses **Reject** with a reason. | Nothing changes. The offerer is notified with the reason. | |
| 11 | Try to move more units than you hold. | Blocked with a clear message. | |
| 12 | **Export xlsx** and **Export csv** of your allocation. | Files contain only your region. Cells beginning with `=` are neutralised. | |
| 13 | **Finalize** your region (no pending transfers). | Your region shows FINALIZED. | |
| 14 | **Un-finalize**, then finalize again. | Works while the cycle is not FINAL. | |
| 15 | When all four regions are finalized. | The cycle is FINAL, and every role is notified. Nothing can be edited any more. | |
