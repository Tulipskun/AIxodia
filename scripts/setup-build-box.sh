#!/usr/bin/env bash
# Bring up the Android SDK on a box that has never built this app.
#
# The SDK is not part of the app and not part of the backup: it lives outside the
# tree the engine walks, so a machine restored from a backup comes back with the
# repos and no SDK. This script is the difference between "one command" and
# re-deriving the package list and the licence dance from scratch.
#
#   scripts/setup-build-box.sh [install-prefix]
#
# Then build with the SDK exported, on a new cgroup namespace — without `-C` the
# JDK image transform chokes on the multi-line cgroup v1 warning that Gradle
# captures from java --version:
#
#   unshare -Cm bash -c 'export ANDROID_HOME=/opt/android-sdk && ./gradlew :app:testDebugUnitTest'
#
set -euo pipefail

PREFIX="${1:-/opt/android-sdk}"
TOOLS="$PREFIX/cmdline-tools/latest"
CMDLINE_ZIP="commandlinetools-linux-11076708_latest.zip"

if [ -x "$TOOLS/bin/sdkmanager" ]; then
  echo "sdkmanager already at $TOOLS — nothing to download"
else
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  echo "fetching command line tools"
  curl -fsSL -o "$tmp/$CMDLINE_ZIP" \
    "https://dl.google.com/android/repository/$CMDLINE_ZIP"
  mkdir -p "$PREFIX/cmdline-tools"
  unzip -q "$tmp/$CMDLINE_ZIP" -d "$tmp/unpacked"
  # The archive unpacks to a directory called "cmdline-tools"; the SDK expects it
  # under cmdline-tools/latest, which is also what ANDROID_HOME probes look for.
  mv "$tmp/unpacked/cmdline-tools" "$TOOLS"
fi

echo "accepting licences"
yes | "$TOOLS/bin/sdkmanager" --licenses >/dev/null 2>&1 || true

echo "installing platform 35 and build-tools 35.0.0"
"$TOOLS/bin/sdkmanager" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" >/dev/null

echo "sdk ready at $PREFIX"
