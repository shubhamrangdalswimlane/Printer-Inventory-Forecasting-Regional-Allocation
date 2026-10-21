# Restore a dataset version

Each forecast or inventory upload is an immutable version. "Restoring" means activating an earlier one.

1. **Datasets**: pick the type and month and open the version list.
2. Compare versions (**Compare**) to confirm which one is correct.
3. Click **Activate** on the version to restore (`POST /api/v1/datasets/{id}/activate`). It is audited.
4. Activating an inventory version re-applies stock to the plan (reserves are kept); if an allocation
   proposal exists it is marked stale and the Planner is notified to re-run it.
5. A deleted (soft-deleted) version can be brought back with **Undelete** until it is purged
   (`pifa.retention.purge-days`).

A version referenced by a FINAL snapshot cannot be deleted.
