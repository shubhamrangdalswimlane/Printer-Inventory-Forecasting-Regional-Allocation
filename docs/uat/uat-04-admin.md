# UAT-04 Admin

Preconditions: you are signed in as the Admin.

| # | Step | Expected | Pass/Fail |
|---|---|---|---|
| 1 | **Overview**. | Cycle state, region progress, deadlines, open issues and system health (last SFTP poll, audit chain). | |
| 2 | **Analytics** over several cycles. | Fill rate by region, transfer flows and coverage charts. | |
| 3 | **Master data**: add a product, add an alias, then **Rename** the product. | All three work and are audited. A duplicate name or alias is refused. | |
| 4 | Import the products sheet with one duplicate row. | Nothing is imported, and the duplicate is reported. | |
| 5 | **Rule parameters**: set weights that add up to 90. | Refused: the weights must total 100. | |
| 6 | Save new parameters with a reason and an effective-from date. | A new version is listed in **History**. Older proposals keep their old version number. | |
| 7 | **Audit log**: filter by user and action, then export. | Matching rows are listed, and the export contains them. | |
| 8 | **Verify integrity**. | "Audit chain intact" with a row count. | |
| 9 | **Cycles**: create next month's cycle and set deadlines. | The cycle appears in the cycle bar for all roles. | |
| 10 | **Reopen FINAL cycle** with a reason. | The cycle goes back to REGIONAL_FINALIZATION. Everyone is notified. A high-severity audit row is written. | |
| 11 | **Users**. | Users, roles, regions and last login are read from Keycloak. **Open the Keycloak admin console** works. | |
| 12 | Go-live: run the matching report on a real upstream file. | Every name is EXACT, ALIAS, INACTIVE or UNKNOWN. Unknown names come with suggestions. | |
| 13 | Go-live: import the sales template with one unknown product. | 422 response listing the row. Nothing is imported. | |
| 14 | Go-live: readiness check. | Every checklist item is listed. In UAT, the S3 and ClamAV checks pass when the UAT matches prod. | |
| 15 | **Synthetic data, Purge**. | All synthetic cycles, files, products and sales are gone. Real data is untouched. | |
