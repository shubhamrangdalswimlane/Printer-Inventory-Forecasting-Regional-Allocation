# UAT-05 Negative tests: permissions and bad input

Every step must be **refused**, with a clear message and no change to the data. Check the audit log afterwards:
refused actions must not appear as changes.

| # | As | Try to | Expected | Pass/Fail |
|---|---|---|---|---|
| 1 | Regional Manager North | Edit an East value (also by calling the API directly) | 403 | |
| 2 | Regional Manager North | Open East's sales analysis | 403 REGION_FORBIDDEN | |
| 3 | Regional Manager | Upload a forecast | Not offered in the UI. The API refuses it. | |
| 4 | Forecaster | Approve the allocation | Refused | |
| 5 | Planner | Edit the forecast in FORECAST_DRAFT | Refused | |
| 6 | Planner | Reopen a FINAL cycle | Refused (Admin only) | |
| 7 | Any non-Admin | Open `/api/v1/admin/...` | 403 | |
| 8 | Anyone | Upload a `.xlsm` (macro) file renamed to `.xlsx` | Rejected: FILE_TYPE | |
| 9 | Anyone | Upload a password-protected workbook | Rejected: FILE_TYPE | |
| 10 | Anyone | Upload the EICAR test file renamed to `.xlsx` | Rejected: INFECTED (only where ClamAV is enabled) | |
| 11 | Anyone | Upload a file with more rows than the limit | Rejected: ROW_LIMIT | |
| 12 | Anyone | Upload a file with a missing region column | Rejected: HEADER_MISSING | |
| 13 | Anyone | Upload a file whose `Forecast_Month` differs from the cycle | Rejected | |
| 14 | Anyone | Upload an inventory file dated in the future | Rejected | |
| 15 | Regional Manager | Finalize while a transfer is pending | Refused: pending transfers must be resolved | |
| 16 | Anyone | Use the app after signing out (browser Back button) | Redirected to sign in. API calls get 401. | |
| 17 | Anyone | Use a token from the dev realm against UAT | 401 (wrong issuer) | |

The **negative pack** (**Admin, Synthetic data**) uploads a set of bad files automatically. Every file must end up REJECTED with the expected code.
