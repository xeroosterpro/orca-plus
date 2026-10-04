#!/usr/bin/env bash
# Build Orca+ = upstream Wholphin + the Orca+ addon, for any Wholphin release.
#
#   ./build.sh            latest Wholphin release, personal build, install on the Shield
#   ./build.sh v1.0.8     a specific tag
#   SHIELD= ./build.sh    build only (no install); echo <ip> > .shield sets the default device
#
# Shared (public) build, as run by GitHub Actions:
#   PUBLIC=1 REPO=owner/name SHIELD= ./build.sh
#   -> text badges, in-app updates from REPO's releases, APKs in dist/release/ named the way
#      Wholphin's updater looks for them (Wholphin-release[-<abi>].apk).
#
# Signing: keystore.jks + .keystore-pass next to this script (created on first run), or
# KEYSTORE_B64 + KEYSTORE_PASS from the environment (CI).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
TAG=${1:-latest}
# Device to install on: $SHIELD, else the IP saved in .shield (not committed). Empty = build only.
SHIELD=${SHIELD-$(cat .shield 2>/dev/null || true)}
PUBLIC=${PUBLIC:-0}
REPO=${REPO:-}
PKG=io.github.xeroosterpro.orcaplus

# AGP needs JDK 17-21. Prefer the portable JDK 21 (this laptop's system JRE 25 has no javac).
JDK=${WHOLPHIN_JDK:-$HOME/.local/lib/jdk-21.0.12.1+1}
[ -d "$JDK" ] && export JAVA_HOME=$JDK
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)

# A truncated hooks.patch would silently build plain Wholphin
grep -q "com.wholphinplus.sources" hooks.patch || { echo "!! hooks.patch is missing the addon hooks" >&2; exit 1; }

[ -d upstream ] || git clone -q https://github.com/damontecres/Wholphin upstream
git -C upstream fetch -q --tags --force https://github.com/damontecres/Wholphin
if [ "$TAG" = latest ]; then
    TAG=$(git -C upstream tag -l 'v*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1)
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

# Wholphin's versionName is `git describe` (vX.Y.Z-<commits>-g<hash>). Commit the patch as one
# commit per addon revision so every addon update gets a higher version and the in-app updater
# offers it. upstream/ is a throwaway checkout, so these commits never go anywhere.
# REV_OFFSET keeps versions increasing if the repo history is ever squashed
REV=$(( $(git rev-list --count HEAD 2>/dev/null || echo 1) + ${REV_OFFSET:-0} ))
G="git -C upstream -c user.name=Wholphin+ -c user.email=build@localhost -c commit.gpgsign=false"
$G commit -q -m "Wholphin+ addon" --no-verify
for _ in $(seq 2 "$REV"); do $G commit -q --allow-empty -m "Wholphin+ addon revision" --no-verify; done

GRADLE_ARGS=()
if [ "$PUBLIC" = 1 ]; then
    GRADLE_ARGS+=(-PwholphinPlusPublic=true "-PwholphinPlusRepo=$REPO")
fi
rm -rf upstream/app/build/outputs/apk
(cd upstream && ./gradlew --console=plain -q "${GRADLE_ARGS[@]}" :wholphin-plus-sources:testDebugUnitTest :app:assembleDefaultRelease)
# Back to the release tag with the patch as plain edits, so `git -C upstream diff HEAD` is the
# whole patch again (the version commits above would otherwise hide it).
git -C upstream reset -q --mixed "$TAG"
OUT=upstream/app/build/outputs/apk/default/release
VERSION=$(ls "$OUT"/Wholphin-default-release-*.apk | head -1 | sed -E 's/.*Wholphin-default-release-(.+)-[0-9]+(-[a-z0-9_-]+)?\.apk/\1/' | sed -E 's/-(arm64-v8a|armeabi-v7a|x86_64)$//')

# Signing key
KS=$ROOT/keystore.jks
PASSFILE=$ROOT/.keystore-pass
if [ -n "${KEYSTORE_B64:-}" ]; then
    KS=$(mktemp) && PASSFILE=$(mktemp)
    echo "$KEYSTORE_B64" | base64 -d > "$KS"
    printf '%s' "$KEYSTORE_PASS" > "$PASSFILE"
    trap 'rm -f "$KS" "$PASSFILE"' EXIT
elif [ ! -f "$KS" ]; then
    head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$PASSFILE"
    chmod 600 "$PASSFILE"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass:file "$PASSFILE" -keypass:file "$PASSFILE" \
        -alias wholphinplus -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Wholphin Plus" >/dev/null
    chmod 600 "$KS"
fi
sign() { # in out
    "$BT/zipalign" -f -p 4 "$1" "$2.aligned"
    "$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASSFILE" --ks-key-alias wholphinplus --out "$2" "$2.aligned"
    rm -f "$2.aligned" "$2.idsig"
    "$BT/apksigner" verify "$2" | grep -v "not protected by signature" || true
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
APK=$(ls "$OUT"/Wholphin-default-release-*"${ABI:+-$ABI}".apk 2>/dev/null | head -1)
[ -n "$APK" ] || APK=$(ls "$OUT"/Wholphin-default-release-*.apk | grep -vE -- '-(arm64-v8a|armeabi-v7a|x86_64)\.apk$' | head -1)
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
