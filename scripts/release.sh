#!/usr/bin/env bash
#
# Build AIxodia on this machine and publish the APK that was just built.
#
# There is no CI. A build here is minutes cold and seconds once warm, and the
# APK that is signed and verified is the same file that is published — nothing
# is rebuilt elsewhere from a different toolchain. Publishing stays in one
# place, so release tags and versionCodes have a single writer and cannot race.
#
# The APK is signed with the stable key at app/aixodia-debug.keystore. Same
# applicationId + same signature + a higher versionCode is what lets an update
# install straight over the old app with the Room cache and settings kept.
#
# Needs: gh (authenticated), an Android SDK + Gradle, and the stable signing
# key. The SDK and Gradle are looked for in the usual local locations; override
# with ANDROID_HOME and PATH if they live somewhere else.
#
#   scripts/release.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

# A toolchain that is already configured wins; otherwise fall back to the local
# unpacked copies this repo is developed against.
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if ! command -v gradle >/dev/null 2>&1; then
  for g in /opt/gradle-*/bin; do
    if [ -x "$g/gradle" ]; then export PATH="$g:$PATH"; break; fi
  done
fi
command -v gradle >/dev/null 2>&1 || { echo "error: gradle is not on PATH" >&2; exit 1; }
command -v gh >/dev/null 2>&1 || { echo "error: gh is not on PATH" >&2; exit 1; }

KEYSTORE="app/aixodia-debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  cat >&2 <<EOF
error: $KEYSTORE is missing.

Without it the APK is signed with a throwaway debug key and cannot install
over any release already on a phone. Put the stable key there before releasing.
EOF
  exit 1
fi

# The build tools live under the SDK; apksigner is what proves the signature.
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
APKSIGNER="${BT}apksigner"
[ -x "$APKSIGNER" ] || { echo "error: apksigner not found under $ANDROID_HOME/build-tools" >&2; exit 1; }

# The build has to match the commit that is tagged, so a dirty tree is refused
# instead of silently shipping something that is not in the history.
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "error: the working tree has uncommitted changes; commit them first" >&2
  git status --short >&2
  exit 1
fi

branch=$(git rev-parse --abbrev-ref HEAD)
if [ "$branch" = "HEAD" ]; then
  echo "error: HEAD is detached; check out a branch before releasing" >&2
  exit 1
fi
if [ "$branch" != "main" ]; then
  echo "warning: releasing from '$branch', not main" >&2
fi

# The release is created against this commit, so it has to exist on the remote.
echo "pushing $branch"
git push origin "HEAD:refs/heads/$branch"

# One rolling release, like the engine's engine-latest: the tag never changes,
# the asset is replaced in place. The version code still moves forward on every
# build so an install overwrites the previous APK instead of being rejected.
tag="latest"
VERSION_CODE="$(date +%s)"
VERSION_NAME="1.0.$(date +%Y%m%d%H%M)"

echo "building $tag (versionCode=$VERSION_CODE)"
gradle assembleDebug --console=plain \
  -PaixodiaVersionCode="$VERSION_CODE" -PaixodiaVersionName="$VERSION_NAME"

APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "error: $APK was not produced" >&2; exit 1; }

# The signature is the whole point: a different key cannot install over the
# previous release, so fail loudly rather than publish an APK nobody can update.
signer=$("$APKSIGNER" verify --print-certs "$APK" | grep -i "certificate DN" | head -1)
case "$signer" in
  *CN=AIxodia*) echo "signature OK: $signer" ;;
  *) echo "error: APK is not signed with the stable key ($signer)" >&2; exit 1 ;;
esac

cp "$APK" AIxodia.apk
if gh release view "$tag" >/dev/null 2>&1; then
  gh release edit "$tag" --title "AIxodia $tag" --target "$(git rev-parse HEAD)"
  gh release upload "$tag" AIxodia.apk --clobber
else
  gh release create "$tag" AIxodia.apk \
    --title "AIxodia $tag" \
    --target "$(git rev-parse HEAD)" \
    --generate-notes
fi
echo "released $tag"

# Keep a single release, the rolling one. Legacy v0.1.x tags from the old
# scheme are removed on the first run and never come back.
gh release list --limit 1000 --json tagName --jq '.[].tagName' \
  | while read -r t; do
      [ "$t" = "$tag" ] && continue
      echo "deleting superseded release $t"
      gh release delete "$t" --yes --cleanup-tag
    done
