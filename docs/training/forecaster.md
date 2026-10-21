# Forecaster quick guide

**Your job each month:** get the forecast in, clean it, and publish it to the regions.

1. **Get the forecast in.** Usually the forecasting system drops the file on SFTP and it appears by itself within
   about 5 minutes. To upload by hand, use **Upload** on your dashboard and pick the file
   (**Download blank template** gives the exact layout).
2. **Check ingestion.** **Recent ingestion** shows each file's status:
   - ACTIVATED: the file is in the grid.
   - STORED: the file is kept but not active. Activate it from **Version history**.
   - REJECTED: nothing changed. Use **Download error report**, fix the file, and upload it again.
3. **Review the validation panel.** Errors block publishing and warnings do not. A *spike* warning means a value
   is much higher than recent sales. Check it, but you may keep it.
4. **Edit cells** where needed. Give a reason when asked. **History** on a line shows every change.
5. **Approve & publish.** The Regional Managers get the forecast for review.
   Made a mistake? **Recall** (with a reason) brings it back to draft, as long as the regions have not moved on.

Rules that surprise people:
- A product name in the file must match the product master (or an alias). Case and extra spaces do not matter, but
  spelling does. Ask the Admin to add an alias.
- Uploading the exact same file twice is refused as a duplicate.
- After publishing, the forecast is read-only for you. The regions own the revisions.
