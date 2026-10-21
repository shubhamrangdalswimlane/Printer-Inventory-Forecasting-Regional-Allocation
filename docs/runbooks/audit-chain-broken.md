# Audit hash chain broken (PifaAuditChainBroken, PifaAuditChainUnverified)

## What it means
The nightly verifier (or a manual run) found a row in `audit_log` whose `prev_hash` or content does not
match. `pifa_audit_chain_ok` = 0 and Admins were notified.

**This is a security incident until proven otherwise.** The table is append-only (a trigger blocks UPDATE
and DELETE), so a break means someone with database owner rights bypassed it, or a restore was inconsistent.

## Impact
The audit trail can no longer be trusted from the broken row onwards. The application keeps working.

## Diagnose
1. **Admin, Overview** shows the message, for example `row 81234: content does not match its hash`.
2. Do **not** modify the table. Take a snapshot of the database for forensics first.
3. Check the database audit logs (RDS/pgaudit) for `ALTER TABLE ... DISABLE TRIGGER`, `UPDATE audit_log`,
   or `DELETE FROM audit_log`, and for which role ran them.
4. Was there a restore or partition operation recently? Compare with an earlier backup: export the rows
   around the broken id from both and diff them.

## Fix
- Real tampering: follow the security incident process. Restore from backup only on the decision of Security.
- A restore artefact (for example a partial restore of one partition): restore consistently, then re-run
  verification (**Admin, Overview, Verify audit chain** or `POST /api/v1/admin/audit/verify`).

## Verify
Verification reports "Audit chain intact".

## Escalate
Security on-call immediately; the DBA.
