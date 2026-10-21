# Mail outbox backlog (PifaMailOutboxGrowing)

## What it means
`pifa_outbox_pending` (unsent mails with fewer than 5 attempts) has stayed above 50 for 15 minutes.

## Impact
E-mail notifications are delayed. In-app notifications still work.

## Diagnose
1. Is the worker running? Only worker pods send mail (`PIFA_JOBS_ENABLED=true`).
2. `kubectl -n pifa logs deploy/pifa-worker --since=30m | grep -i mail`: look for SMTP auth or TLS errors.
3. Rows that gave up: `SELECT id, attempts, last_error FROM outbox_event WHERE sent_at IS NULL AND attempts >= 5 ORDER BY id DESC LIMIT 20;`

## Fix
- SMTP credentials or host wrong: fix the secret and restart the worker.
- After the cause is fixed, retry rows that gave up:
  `UPDATE outbox_event SET attempts = 0 WHERE sent_at IS NULL AND attempts >= 5;` (record the reason in the change ticket).

## Verify
`pifa_outbox_pending` falls to 0 within a few minutes.

## Escalate
The mail/SMTP relay owner.
