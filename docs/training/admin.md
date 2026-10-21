# Admin and support quick guide

**Daily (5 minutes):**
- **Overview, System health**: the last SFTP poll and the last audit verification must both be OK.
- **Recent ingestion**: follow up on REJECTED files with the sender ([runbook](../runbooks/rejected-file.md)).

**Monthly:**
- **Cycles**: make sure next month's cycle exists and set its review and finalize deadlines.
- **Master data**: add new products and aliases before the files that use them arrive.

**When something is wrong:**
- A user reports an error: ask for the **request id** in the message. Search the logs and the **Audit log** for it.
- A file was rejected for an unknown product: add the alias, then reprocess ([runbook](../runbooks/reprocess-file.md)).
- A wrong dataset is active: activate the right version ([runbook](../runbooks/restore-version.md)).
- A FINAL cycle must change: **Reopen FINAL cycle** with written approval ([runbook](../runbooks/reopen-cycle.md)).
- Alerts: every alert links to its page in [docs/runbooks](../runbooks/README.md).

**Rule parameters** (weights, change threshold, floor, spike factor, cap-at-demand, auto-activation): change them only
with the business owner's approval. Put the approval reference in the reason. Each save is a new version.

**Users and access** are managed in Keycloak. **Users** in PIFA is a read-only view with a link to the console.
A Regional Manager needs the `regional_manager` role **and** the `regions` attribute (for example `NORTH`).

**Go-live tools** (Admin, Go-live): readiness check, matching report, sales-history import and parallel-run
reconciliation. See [the cutover runbook](../go-live/cutover-runbook.md).
