#!/usr/bin/env bash
#
# Keep free space available without anyone watching.
#
# Run from cron a few times a day. The check is deliberately cheap and quiet: it
# only acts when a partition is genuinely low, so a healthy machine produces no
# output and no cache rebuilds.
#
#   */30 * * * * /root/bin/disk-guard-cron.sh >> /var/log/disk-guard.log 2>&1
#
set -euo pipefail

LOG_PREFIX="$(date -u +%Y-%m-%dT%H:%M:%SZ) disk-guard:"

# Nothing to do unless a partition is actually short.
if /root/bin/disk-guard.sh check >/dev/null 2>&1; then
  exit 0
fi

echo "$LOG_PREFIX low space detected, cleaning"
/root/bin/disk-guard.sh clean

if /root/bin/disk-guard.sh check >/dev/null 2>&1; then
  echo "$LOG_PREFIX recovered"
else
  echo "$LOG_PREFIX still low after cleaning:"
  /root/bin/disk-guard.sh report
fi

# The log itself must not grow without end: keep the last 2000 lines.
if [ -f /var/log/disk-guard.log ]; then
  tail -n 2000 /var/log/disk-guard.log > /var/log/disk-guard.log.tmp 2>/dev/null &&
    mv /var/log/disk-guard.log.tmp /var/log/disk-guard.log || true
fi
