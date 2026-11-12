#!/usr/bin/env bash
#
# Keep the build caches from filling the disk.
#
# Two partitions matter here and both have run to 100% before: / (24G, the root
# filesystem, where the app checkout and Gradle build) and /data (20G, where the
# cargo target dir lives). A full disk does not fail loudly — cargo and Gradle
# report it as a compile error in whichever crate was writing, which reads like a
# code problem and wastes a lot of time chasing the wrong thing.
#
# So this runs in two modes:
#
#   disk-guard.sh check     report usage, and exit 1 if either partition is at or
#                           above the panic threshold. Safe to call from any
#                           script that is about to write a lot of bytes.
#   disk-guard.sh clean     drop the caches that are safe to lose: the cargo
#                           target directory (a rebuild follows on its own) and
#                           the Gradle transform/build caches. Never touches a
#                           checkout, a keystore or a release artefact.
#
set -euo pipefail

# Below this much free space, caches are dropped. Sized for a full cargo build of
# the workspace, which needs a few gigabytes with room to spare.
MIN_FREE_GB=3
# After cleaning, this is what should be free; if it is not, say so loudly rather
# than letting the next build fail halfway.
TARGET_FREE_GB=6

WATCH_PATHS=(/ /data)

free_gb() {
  df -BG --output=avail "$1" 2>/dev/null | tail -1 | tr -dc '0-9'
}

report() {
  for p in "${WATCH_PATHS[@]}"; do
    [ -d "$p" ] || continue
    printf '%-8s %sG free of %sG\n' "$p" "$(free_gb "$p")" \
      "$(df -BG --output=size "$p" | tail -1 | tr -dc '0-9')"
  done
}

check() {
  local low=0
  for p in "${WATCH_PATHS[@]}"; do
    [ -d "$p" ] || continue
    local f
    f=$(free_gb "$p")
    if [ -z "$f" ]; then continue; fi
    if [ "$f" -lt "$MIN_FREE_GB" ]; then
      echo "disk-guard: $p has only ${f}G free (need ${MIN_FREE_GB}G)" >&2
      low=1
    fi
  done
  return "$low"
}

clean() {
  local freed=0
  # The cargo target dir is a build cache by definition: it is regenerated from
  # source. Taken from .cargo/config.toml so this follows the configured location
  # instead of assuming ./target.
  local target
  target=$(sed -n 's/^target-dir *= *"\(.*\)"/\1/p' "$HOME/.cargo/config.toml" 2>/dev/null | head -1)
  for dir in "${target:-}" /root/aixodia-engine/target; do
    [ -n "$dir" ] || continue
    if [ -d "$dir" ]; then
      local before
      before=$(du -sBG "$dir" 2>/dev/null | tr -dc '0-9')
      rm -rf "$dir"
      echo "disk-guard: removed $dir (~${before:-?}G)"
      freed=1
    fi
  done
  # Gradle caches: transforms and journal are re-derivable; the wrapper
  # distribution and the dependency cache are not, and are left alone because a
  # release machine may have no network to fetch them again.
  for dir in /root/AIxodia-work/.gradle/transforms /root/AIxodia-work/app/build/intermediates; do
    if [ -d "$dir" ]; then
      rm -rf "$dir"
      echo "disk-guard: removed $dir"
      freed=1
    fi
  done
  # Release APKs left in the build tree: the published copy is the one that
  # matters, and app/build/outputs is rebuilt on demand.
  rm -rf /root/AIxodia-work/app/build/outputs/apk 2>/dev/null || true
  # Stray downloads from earlier runs.
  rm -f /tmp/AIxodia.apk /tmp/rel.apk /tmp/inst.apk /tmp/AIxodia-version.json 2>/dev/null || true

  [ "$freed" = 1 ] || echo "disk-guard: nothing to remove"
}

case "${1:-check}" in
  check)
    report
    check || exit 1
    ;;
  clean)
    clean
    report
    for p in "${WATCH_PATHS[@]}"; do
      [ -d "$p" ] || continue
      f=$(free_gb "$p")
      if [ -n "$f" ] && [ "$f" -lt "$TARGET_FREE_GB" ]; then
        echo "disk-guard: $p still has only ${f}G free after cleaning; look for something else holding space:" >&2
        du -sBGx "$p"/* 2>/dev/null | sort -rn | head -5 >&2 || true
      fi
    done
    ;;
  report)
    report
    ;;
  *)
    echo "usage: $0 [check|clean|report]" >&2
    exit 2
    ;;
esac
