#!/bin/bash
# permissions.sh - runs inside the atmoz/sftp container at start-up (mounted into /etc/sftp.d).
#
# The drop folders are SHARED between accounts on purpose: the producer account (sftp-forecast or
# sftp-inventory) writes a file, and the worker account (pifa-worker) reads, archives and deletes it.
# The same named volume is mounted into both home directories (see docker-compose.yml). Because the
# accounts have different uids, the shared folders are made group-writable here.
# Local development only: production uses a managed SFTP service or a hardened OpenSSH setup.
mkdir -p /home/pifa-worker/inbound/forecast /home/pifa-worker/inbound/inventory /home/pifa-worker/outbound/errors /home/pifa-worker/archive
chmod -R 777 /home/pifa-worker/inbound /home/pifa-worker/outbound /home/pifa-worker/archive
