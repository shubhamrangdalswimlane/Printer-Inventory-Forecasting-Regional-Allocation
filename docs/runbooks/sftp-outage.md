# SFTP polling errors (PifaSftpNoSuccessfulPoll, PifaWorkerDown)

## What it means
`pifa_sftp_poll_errors_total` is increasing: the worker cannot list or fetch files from the SFTP server.

## Impact
Dropped files wait on the server; nothing is lost. Manual upload in the UI still works.

## Diagnose
0. `PifaWorkerDown`: no worker pod is running, so nothing polls (or sends mail, or runs nightly jobs).
   `kubectl -n pifa get pods -l app.kubernetes.io/component=worker`, then follow [pod-restarts.md](pod-restarts.md).
1. **Admin, Overview, System status**: the `sftp.poll` row shows the last error and time.
2. `kubectl -n pifa logs deploy/pifa-worker --since=30m | grep -i sftp`
3. Typical errors:
   - `Auth fail`: the password or key was rotated; see [key-rotation.md](key-rotation.md).
   - `HostKey`/`reject HostKey`: the server host key changed. Confirm the change with the SFTP owner
     **before** updating `known_hosts` (`PIFA_SFTP_KNOWN_HOSTS`). Never set `strict-host-key=false` in production.
   - `Connection refused`/timeout: a network or firewall issue, or the server is down.
   - `Permission denied` on rename/move: the account cannot move files to `processed/` or `failed/`.

## Fix
Fix the cause above, then restart the worker if the secret changed: `kubectl -n pifa rollout restart deploy/pifa-worker`.

## Verify
The `sftp.poll` status row turns OK on the next poll (default every minute) and waiting files are picked up.

## Escalate
SFTP server owner (infrastructure team) when the server or network is at fault.
