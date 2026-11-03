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
# Needs: gh (authenticated), an Android SDK + Gradle. The stable signing key is
# fetched from the private Tulipskun/aixodia-keys repo when app/aixodia-debug.keystore
# is not already there; the SDK and Gradle are looked for in the usual local
# locations; override with ANDROID_HOME and PATH if they live somewhere else.
#
#   scripts/release.sh
#
# The same script runs in GitHub Actions (.github/workflows/release.yml). There
# the runner provides the SDK, JDK and an authenticated gh (GH_TOKEN), the key
# comes from the AIXODIA_KEYSTORE_B64 repository secret instead of
# Tulipskun/aixodia-keys, the checkout is already the commit being released so
# nothing is pushed, and the project's ./gradlew is used so the Gradle version
# is the one the project pins.
#
set -euo pipefail

cd "$(dirname "$0")/.."

# A toolchain that is already configured wins; otherwise fall back to the local
# unpacked copies this repo is developed against.
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -z "$ANDROID_HOME" ]; then
  for a in /opt/android-sdk /data/home/opt/android-sdk; do
    if [ -x "$a/platform-tools/adb" ]; then export ANDROID_HOME="$a"; break; fi
  done
fi
if ! command -v gradle >/dev/null 2>&1; then
  for g in /opt/gradle-*/bin; do
    if [ -x "$g/gradle" ]; then export PATH="$g:$PATH"; break; fi
  done
fi
if [ -n "${GITHUB_ACTIONS:-}" ] && [ -x ./gradlew ]; then
  GRADLE=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=gradle
elif [ -x ./gradlew ]; then
  GRADLE=./gradlew
else
  echo "error: gradle is not on PATH and no ./gradlew wrapper was found" >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/jdk17 /data/home/opt/jdk17 /opt/jdk /usr/lib/jvm/java-17-openjdk-amd64; do
    if [ -x "$j/bin/java" ]; then export JAVA_HOME="$j"; break; fi
  done
fi
export JAVA_HOME="${JAVA_HOME:-}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
command -v gh >/dev/null 2>&1 || { echo "error: gh is not on PATH" >&2; exit 1; }

KEYSTORE="app/aixodia-debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  # The stable key is not in this repo (it is gitignored here).
  if [ -n "${AIXODIA_KEYSTORE_B64:-}" ]; then
    # CI: the key arrives as a repository secret.
    echo "writing $KEYSTORE from AIXODIA_KEYSTORE_B64"
    printf '%s' "$AIXODIA_KEYSTORE_B64" | base64 -d > "$KEYSTORE"
  elif [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "error: the AIXODIA_KEYSTORE_B64 repository secret is not set; refusing to sign with another key" >&2
    echo "       set it with: base64 -w0 app/aixodia-debug.keystore | gh secret set AIXODIA_KEYSTORE_B64" >&2
    exit 1
  else
    # A release machine: it lives in the private Tulipskun/aixodia-keys repo.
    # Fetch it there so a release machine can go straight from clone to build
    # instead of someone copying the file by hand.
    echo "no $KEYSTORE found; fetching the stable key from Tulipskun/aixodia-keys"
    gh api "repos/Tulipskun/aixodia-keys/contents/aixodia-debug.keystore" --jq .content \
      | base64 -d > "$KEYSTORE"
  fi
  got=$(md5sum "$KEYSTORE" | cut -d' ' -f1)
  if [ "$got" != "30b3cd5702f8bac4139efeb414cd20c8" ]; then
    rm -f "$KEYSTORE"
    echo "error: keystore md5 mismatch (got $got, want 30b3cd5702f8bac4139efeb414cd20c8); refusing to sign" >&2
    exit 1
  fi
  chmod 600 "$KEYSTORE"
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

if [ -n "${GITHUB_ACTIONS:-}" ]; then
  # The runner checked out a commit that is already on the remote.
  echo "releasing $(git rev-parse --short HEAD) from CI"
else
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
fi

# One rolling release, like the engine's engine-latest: the tag never changes,
# the asset is replaced in place. The version code still moves forward on every
# build so an install overwrites the previous APK instead of being rejected.
tag="latest"
VERSION_CODE="$(date +%s)"
VERSION_NAME="1.0.$(date +%Y%m%d%H%M)"

# The rolling tag cannot carry a version, so publish the exact versionCode the
# APK was built with. The in-app checker compares this file against its own
# BuildConfig instead of trying to parse the tag.
VERSION_ASSET=AIxodia-version.json
trap 'rm -f "$VERSION_ASSET"' EXIT
cat > "$VERSION_ASSET" <<EOF
{"versionCode":$VERSION_CODE,"versionName":"$VERSION_NAME","builtAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","commit":"$(git rev-parse HEAD)"}
EOF

echo "building $tag (versionCode=$VERSION_CODE)"
"$GRADLE" assembleDebug --console=plain \
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
  gh release upload "$tag" AIxodia.apk "$VERSION_ASSET" --clobber
else
  gh release create "$tag" AIxodia.apk "$VERSION_ASSET" \
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
