# Reprocess a file

Use when a file was rejected for a reason that has since been fixed (for example a product alias was added).

1. Download the original from **Admin, Overview, Ingestion jobs** (the original is kept for audit).
2. Fix any data errors in the file itself if needed. Keep the original name pattern.
3. Upload it again in the UI (**Datasets, Upload**) or drop it again into the SFTP `forecast/` or `inventory/`
   folder. Files rejected by SFTP were moved to `failed/`; move them back to the inbound folder after the fix.
4. A new ingestion job runs; the previous rejected job remains in the history.

Never reprocess a file the virus scanner flagged.
