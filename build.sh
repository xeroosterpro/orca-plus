#!/usr/bin/env bash
# Build Orca+ = upstream Wholphin + the Orca+ addon, for any Wholphin release.
#
#   ./build.sh            the pinned Wholphin (UPSTREAM file) if there is one, else the latest release;
#                         personal build, install on the Shield
#   ./build.sh v1.0.8     a specific tag
#   SHIELD= ./build.sh    build only (no install); echo <ip> > .shield sets the default device
#   ./build.sh --check-patch   only check that hooks.patch touches every file in hooks.files
#
# Shared (public) build, as run by GitHub Actions:
#   PUBLIC=1 REPO=owner/name UNSIGNED=1 SHIELD= ./build.sh
#   -> text badges, in-app updates from REPO's releases, unsigned APKs in dist/release/ named the
#      way Wholphin's updater looks for them (Wholphin-release[-<abi>].apk); the release workflow
#      signs them in a separate job. With KEYSTORE_B64 + KEYSTORE_PASS instead of UNSIGNED=1 they
#      are signed here.
# WHOLPHIN_SHA=<commit> makes the build refuse a tag that doesn't point at that commit (tags can move).
#
# Signing (personal): keystore.jks + .keystore-pass next to this script (created on first run).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD

# hooks.patch must touch every Wholphin file listed in hooks.files: a truncated or half-regenerated
# patch would otherwise build an app with some hooks silently missing.
check_patch() {
    local missing
    grep -q "com.wholphinplus.sources" hooks.patch || { echo "!! hooks.patch is missing the addon hooks" >&2; return 1; }
    [ -f hooks.files ] || return 0
    missing=$(comm -23 <(sort -u hooks.files) <(grep '^diff --git' hooks.patch | sed -E 's#^diff --git a/(.*) b/.*#\1#' | sort -u))
    if [ -n "$missing" ]; then
        echo "!! hooks.patch lost its changes to:" >&2
        echo "$missing" | sed 's/^/     /' >&2
        echo "   (regenerate it from a tree with the whole patch applied; see HANDOFF Gotchas)" >&2
        return 1
    fi
    local extra
    extra=$(comm -13 <(sort -u hooks.files) <(grep '^diff --git' hooks.patch | sed -E 's#^diff --git a/(.*) b/.*#\1#' | sort -u))
    [ -z "$extra" ] || echo "   note: hooks.patch also touches files not in hooks.files (add them there):" $extra >&2
}
if [ "${1:-}" = --check-patch ]; then check_patch; echo "hooks.patch OK ($(grep -c '^diff --git' hooks.patch) files)"; exit 0; fi

PIN_TAG="" PIN_SHA=""
[ -f UPSTREAM ] && read -r PIN_TAG PIN_SHA < UPSTREAM
TAG=${1:-${PIN_TAG:-latest}}
[ "$TAG" = "$PIN_TAG" ] && WHOLPHIN_SHA=${WHOLPHIN_SHA:-$PIN_SHA}
WHOLPHIN_SHA=${WHOLPHIN_SHA:-}
# Device to install on: $SHIELD, else the IP saved in .shield (not committed). Empty = build only.
SHIELD=${SHIELD-$(cat .shield 2>/dev/null || true)}
PUBLIC=${PUBLIC:-0}
UNSIGNED=${UNSIGNED:-0}
REPO=${REPO:-}
PKG=io.github.xeroosterpro.orcaplus

# AGP needs JDK 17-21. Prefer the portable JDK 21 (this laptop's system JRE 25 has no javac).
JDK=${WHOLPHIN_JDK:-$HOME/.local/lib/jdk-21.0.12.1+1}
[ -d "$JDK" ] && export JAVA_HOME=$JDK
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)

check_patch
if [ "$PUBLIC" = 1 ] && [ "$UNSIGNED" != 1 ] && [ -z "${KEYSTORE_B64:-}" ]; then
    echo "!! PUBLIC=1 needs UNSIGNED=1 or KEYSTORE_B64 + KEYSTORE_PASS" >&2
    exit 1
fi

# Everything to undo when the script ends, however it ends (set -e, a failed Gradle, Ctrl-C)
CLEANUP=()
cleanup() { local c; for c in "${CLEANUP[@]}"; do eval "$c" || true; done; }
trap cleanup EXIT

[ -d upstream ] || git clone -q https://github.com/damontecres/Wholphin upstream
git -C upstream fetch -q --tags --force https://github.com/damontecres/Wholphin
if [ "$TAG" = latest ]; then
    TAG=$(git -C upstream tag -l 'v*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1)
fi
if [ -n "$WHOLPHIN_SHA" ] && [ "$(git -C upstream rev-parse "$TAG^{commit}")" != "$WHOLPHIN_SHA" ]; then
    echo "!! Wholphin $TAG is not the pinned commit $WHOLPHIN_SHA (the tag moved?); not building" >&2
    exit 1
fi
echo "==> Wholphin $TAG + addon$([ "$PUBLIC" = 1 ] && echo " (public build for $REPO)")"

# upstream/ is a build area: discard the previous patch application.
git -C upstream reset -q --hard
git -C upstream clean -qfd
git -C upstream checkout -q "$TAG"
if ! git -C upstream apply --3way "$ROOT/hooks.patch"; then
    echo "!! hooks.patch does not apply cleanly to $TAG. Conflicted files:" >&2
    git -C upstream diff --name-only --diff-filter=U >&2
    echo "Fix them in upstream/, then: git -C upstream diff HEAD > hooks.patch" >&2
    exit 2
fi
echo "sdk.dir=$ANDROID_HOME" > upstream/local.properties

# Back to the release tag with the patch as plain edits, so `git -C upstream diff HEAD` is the
# whole patch again (the version commits below would otherwise hide it). Runs on every exit,
# so a failed Gradle can't leave the patch committed (a patch regenerated from there is empty).
unversion() {
    git -C upstream reset -q --mixed "$TAG"
    # Files the patch adds come back untracked after that reset, and `git diff HEAD` skips
    # untracked files: a patch regenerated from here silently lost them. Mark them as intended additions.
    local new
    new=$(grep -A1 '^--- /dev/null' "$ROOT/hooks.patch" | sed -n 's#^+++ b/##p')
    [ -z "$new" ] || git -C upstream add -N -- $new
}
CLEANUP+=(unversion)

# Wholphin's versionName is `git describe` (vX.Y.Z-<commits>-g<hash>). Commit the patch as one
# commit per addon revision so every addon update gets a higher version and the in-app updater
# offers it. upstream/ is a throwaway checkout, so these commits never go anywhere.
# REV_OFFSET (env, else the REV_OFFSET file the public repo carries) keeps versions increasing
# when the public history is squashed.
REV_OFFSET=${REV_OFFSET:-$(cat REV_OFFSET 2>/dev/null || echo 0)}
REV=$(( $(git rev-list --count HEAD 2>/dev/null || echo 1) + REV_OFFSET ))
G="git -C upstream -c user.name=Wholphin+ -c user.email=build@localhost -c commit.gpgsign=false"
$G commit -q -m "Wholphin+ addon" --no-verify
for _ in $(seq 2 "$REV"); do $G commit -q --allow-empty -m "Wholphin+ addon revision" --no-verify; done

GRADLE_ARGS=()
# The Orca+ cloud's address: $ORCA_CLOUD_URL (the release workflow), else a local .cloud-url file.
# Never in the source; a build without it simply has no cloud features. Handed to Gradle through
# the environment, not the command line (which every local user can read in `ps`).
CLOUD_URL=${ORCA_CLOUD_URL:-$(cat "$ROOT/.cloud-url" 2>/dev/null || true)}
if [ -n "$CLOUD_URL" ]; then
    export ORG_GRADLE_PROJECT_orcaCloudUrl=$CLOUD_URL
    echo "==> Cloud: on"
else
    unset ORG_GRADLE_PROJECT_orcaCloudUrl
    echo "==> Cloud: off (no ORCA_CLOUD_URL or .cloud-url)"
fi
unset CLOUD_URL ORCA_CLOUD_URL
if [ "$PUBLIC" = 1 ]; then
    GRADLE_ARGS+=(-PwholphinPlusPublic=true "-PwholphinPlusRepo=$REPO")
fi
rm -rf upstream/app/build/outputs/apk
(cd upstream && ./gradlew --console=plain -q "${GRADLE_ARGS[@]}" :wholphin-plus-sources:testDebugUnitTest :app:assembleDefaultRelease)
unset ORG_GRADLE_PROJECT_orcaCloudUrl
OUT=upstream/app/build/outputs/apk/default/release
VERSION=$(ls "$OUT"/Wholphin-default-release-*.apk | head -1 | sed -E 's/.*Wholphin-default-release-(.+)-[0-9]+(-[a-z0-9_-]+)?\.apk/\1/' | sed -E 's/-(arm64-v8a|armeabi-v7a|x86_64)$//')

if [ "$PUBLIC" = 1 ] && [ "$UNSIGNED" = 1 ]; then
    REL=dist/release
    rm -rf "$REL" && mkdir -p "$REL"
    for apk in "$OUT"/Wholphin-default-release-*.apk; do
        abi=$(basename "$apk" .apk | grep -oE '(arm64-v8a|armeabi-v7a|x86_64)$' || true)
        cp "$apk" "$REL/Wholphin-release${abi:+-$abi}.apk"
    done
    echo "v$VERSION" > "$REL/VERSION"
    echo "==> Unsigned release APKs for v$VERSION in $REL (sign them before publishing)"
    exit 0
fi

# Signing key
KS=$ROOT/keystore.jks
PASSFILE=$ROOT/.keystore-pass
if [ -n "${KEYSTORE_B64:-}" ]; then
    KS=$(mktemp) && PASSFILE=$(mktemp)
    CLEANUP+=('rm -f "$KS" "$PASSFILE"')
    echo "$KEYSTORE_B64" | base64 -d > "$KS"
    printf '%s' "$KEYSTORE_PASS" > "$PASSFILE"
elif [ ! -f "$KS" ]; then
    (umask 077; head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$PASSFILE")
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass:file "$PASSFILE" -keypass:file "$PASSFILE" \
        -alias wholphinplus -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Wholphin Plus" >/dev/null
    chmod 600 "$KS"
fi
sign() { # in out
    "$BT/zipalign" -f -p 4 "$1" "$2.aligned"
    "$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASSFILE" --ks-key-alias wholphinplus --out "$2" "$2.aligned"
    rm -f "$2.aligned" "$2.idsig"
    # A failed verification stops everything: never install or publish an APK that doesn't verify
    local out
    if ! out=$("$BT/apksigner" verify "$2" 2>&1); then
        echo "$out" >&2
        echo "!! $2 failed apksigner verify" >&2
        rm -f "$2"
        exit 4
    fi
}

if [ "$PUBLIC" = 1 ]; then
    REL=dist/release
    rm -rf "$REL" && mkdir -p "$REL"
    for apk in "$OUT"/Wholphin-default-release-*.apk; do
        abi=$(basename "$apk" .apk | grep -oE '(arm64-v8a|armeabi-v7a|x86_64)$' || true)
        sign "$apk" "$REL/Wholphin-release${abi:+-$abi}.apk"
    done
    echo "v$VERSION" > "$REL/VERSION"
    echo "==> Release APKs for v$VERSION in $REL"
    exit 0
fi

# Personal build: the APK for the Shield's ABI (universal if unknown).
ABI=""
if [ -n "$SHIELD" ]; then
    adb connect "$SHIELD:5555" >/dev/null || true
    ABI=$(adb -s "$SHIELD:5555" shell getprop ro.product.cpu.abi | tr -d '\r')
fi
APK=$(ls "$OUT"/Wholphin-default-release-*"${ABI:+-$ABI}".apk 2>/dev/null | head -1 || true)
[ -n "$APK" ] || APK=$(ls "$OUT"/Wholphin-default-release-*.apk | grep -vE -- '-(arm64-v8a|armeabi-v7a|x86_64)\.apk$' | head -1 || true)
[ -n "$APK" ] || { echo "!! no APK for ${ABI:-any ABI} in $OUT" >&2; exit 1; }
mkdir -p dist
SIGNED=dist/Orca+-${TAG#v}${ABI:+-$ABI}.apk
sign "$APK" "$SIGNED"
HASH=$(sha256sum "$SIGNED" | cut -d' ' -f1)
echo "==> Built $SIGNED (v$VERSION, $HASH)"

[ -n "$SHIELD" ] || exit 0
adb -s "$SHIELD:5555" install -r "$SIGNED"
REMOTE=$(adb -s "$SHIELD:5555" shell "sha256sum \$(pm path $PKG | sed 's/package://')" | cut -d' ' -f1)
if [ "$REMOTE" = "$HASH" ]; then
    echo "==> Installed on $SHIELD, hash verified"
else
    echo "!! Installed APK hash $REMOTE does not match $HASH" >&2
    exit 3
fi
