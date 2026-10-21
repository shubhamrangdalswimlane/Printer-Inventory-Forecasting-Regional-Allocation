# UAT-01 Forecaster

Preconditions: synthetic data generated; a cycle for next month exists in FORECAST_DRAFT; you are signed in as the Forecaster.

| # | Step | Expected | Pass/Fail |
|---|---|---|---|
| 1 | Open the Forecaster dashboard. Check the cycle bar. | The month and state FORECAST_DRAFT are shown, with the review and finalize deadlines. | |
| 2 | **Download blank template**. | The workbook has a `Forecast` sheet with `Product_name`, `Forecast_Month` and one column per region. | |
| 3 | Upload a correct forecast file for the cycle month. | Ingestion shows ACTIVATED (or STORED when auto-activation is off). The grid shows the products. | |
| 4 | Upload the same file again. | Rejected as a duplicate. The original version stays active. | |
| 5 | Upload a file with one unknown product name. | Rejected. **Download error report** names the row and the product. | |
| 6 | Upload a file with one regional value 5 times last year's average. | Accepted with a spike warning on that row (Validation panel). | |
| 7 | Edit a cell in the grid and give a reason. | The value saves. **History** on the line shows old value, new value, you and the reason. | |
| 8 | Edit the same cell in two browser tabs. Save in tab 1, then in tab 2. | Tab 2 is told the row changed, and nothing is overwritten silently. | |
| 9 | **Version history**: compare the two most recent versions. | Differences per product and region are listed. | |
| 10 | Activate the older version, then the newer one again. | The grid follows the active version each time. Both actions are in the audit log. | |
| 11 | Fix every blocking validation error, then **Approve & publish**. | State REGIONAL_REVIEW. The Regional Managers are notified (bell and mail). | |
| 12 | **Recall** with a reason. | State FORECAST_DRAFT. The Regional Managers are notified. | |
| 13 | Publish again. | State REGIONAL_REVIEW. | |
| 14 | Try to edit a cell after publishing. | The grid is read-only for the Forecaster. | |
| 15 | Delete an inactive forecast version, then **Undelete** it. | It disappears from the list and comes back. A version used by a snapshot cannot be deleted. | |
| 16 | Download the original file of a version. | The exact uploaded file downloads. The download is in the audit log. | |
