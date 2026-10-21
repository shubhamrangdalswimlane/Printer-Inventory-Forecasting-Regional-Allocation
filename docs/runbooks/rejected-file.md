# Rejected file (PifaIngestionRejectedSpike)

## What it means
`pifa_ingestion_files_total{status="REJECTED"}` increased: an uploaded or SFTP-dropped forecast or
inventory file failed validation (header, product names, quantities, spike rule) or the virus scan.

## Impact
The dataset was **not** stored as a new version; the cycle still uses the previous active version.
The sender was notified (in-app and by mail) with the error report.

## Diagnose
1. **Admin, Overview, Ingestion jobs**: open the job, read its findings, or download the error report
   (`GET /api/v1/ingestion-jobs/{id}/error-report`).
2. Most common causes: an unknown product name (add an alias under **Admin, Master data**), a
   renamed header column, negative quantities, a regional value above the spike factor.
3. Status `INFECTED`: the virus scanner flagged the file. Do **not** reprocess it; tell Security.

## Fix
- Data error: the sender corrects the file and uploads again. If the product name is a legitimate new
  name, add it as an alias first, then follow [reprocess-file.md](reprocess-file.md).
- Rule too strict (for example the spike factor): an Admin changes **Admin, Rule parameters**. The change is
  versioned and audited.

## Verify
The new job shows `ACTIVATED` (or `STORED` when auto-activation is off) and the alert clears.

## Escalate
Repeated rejections from the SFTP feed after a sender-side system change: contact the data owner.
