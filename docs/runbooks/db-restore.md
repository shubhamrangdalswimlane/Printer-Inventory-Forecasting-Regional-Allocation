# Database restore (disaster recovery)

Targets: RPO 15 minutes (RDS point-in-time recovery), RTO 4 hours.

1. Declare the incident; stop writes: `kubectl -n pifa scale deploy/pifa-api deploy/pifa-worker --replicas=0`.
2. Restore RDS to a new instance at the chosen time (console or
   `aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier pifa --target-db-instance-identifier pifa-restore --restore-time <UTC>`).
3. Point the `pifa-db` secret (JDBC URL) at the restored instance.
4. Start one worker, then the API. Flyway validates the schema at start-up (it will not migrate down).
5. Run **Admin, Overview, Verify audit chain**. A break right after a restore usually means a mismatch with
   the anchor; check before declaring tampering ([audit-chain-broken.md](audit-chain-broken.md)).
6. Files: S3 has versioning; dataset rows created after the restore point no longer exist, so their
   files are orphans (harmless). Files ingested after the restore point must be uploaded again.
7. Tell users the restore time; work done after it must be redone.

Practise this in the DR drill at least every 6 months and record the measured RTO.
