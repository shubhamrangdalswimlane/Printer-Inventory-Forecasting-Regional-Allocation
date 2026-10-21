# Reopen a finalized cycle

Only an Admin can reopen a FINAL cycle (transition `REOPEN_CYCLE`). It is recorded as a high-severity audit event.

1. Get written approval from the Supply Planning lead (record the ticket id).
2. **Admin, Cycles**: choose the cycle and **Reopen**, entering the reason with the ticket id.
3. The cycle returns to REGIONAL_FINALIZATION with every region un-finalized. The previous `ALLOCATION_FINAL`
   snapshot is kept (snapshots are immutable). If the allocation itself must change, the Planner then uses
   **Reopen allocation** to go back to PLANNING.
4. Regions must finalize again; the new FINAL creates a new snapshot.
5. Tell downstream consumers (exports) that a new final version will follow.
