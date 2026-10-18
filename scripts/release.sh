#!/usr/bin/env bash
#
# Release AIxodia from this machine.
#
# CI only builds and verifies (.github/workflows/android.yml); publishing is
# done here, from one place, so release tags and versionCodes have a single
# writer and the two can never race. The APK is signed with the same stable key
# as every previous release — same applicationId + same signature + a higher
# versionCode is what lets an update install straight over the old app with the
# Room cache and settings kept.
#
# Needs: gh (authenticated), an Android SDK + Gradle on PATH, and the stable
# signing key at app/aixodia-debug.keystore. The key is a repository secret
# (AIXODIA_KEYSTORE_B64), never committed:
#
#   gh run download <run> --name aixodia-keystore-b64 --dir /tmp/ks
#   base64 --decode /tmp/ks/keystore.b64 > app/aixodia-debug.keystore
#
set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE="app/aixodia-debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  cat >&2 <<EOF
error: $KEYSTORE is missing.

Without it the APK is signed with a throwaway debug key and cannot install
over any release already on a phone. Fetch the stable key from the repository
secret (see the header comment of this script) before releasing.
EOF
  exit 1
fi

# The build tools live under the SDK; apksigner is what proves the signature.
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "error: ANDROID_HOME / ANDROID_SDK_ROOT is not set" >&2; exit 1; }
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
APKSIGNER="${BT}apksigner"
[ -x "$APKSIGNER" ] || { echo "error: apksigner not found under $SDK/build-tools" >&2; exit 1; }

# Next versionCode is one past the highest published release, so an install
# always moves forward and updates over the previous APK in place.
latest=$(gh release list --limit 1000 --json tagName --jq '.[].tagName' 2>/dev/null \
  | sed -n 's/^v0\.1\.\([0-9][0-9]*\)$/\1/p' | sort -n | tail -1)
next=$(( ${latest:-0} + 1 ))
tag="v0.1.$next"

if gh release view "$tag" >/dev/null 2>&1; then
  echo "error: $tag already exists" >&2
  exit 1
fi

echo "latest release: ${latest:-none}  ->  building $tag (versionCode=$next)"
gradle assembleDebug --console=plain \
  -PaixodiaVersionCode="$next" -PaixodiaVersionName="0.1.$next"

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
gh release create "$tag" AIxodia.apk \
  --title "AIxodia $tag" --generate-notes
echo "released $tag"
