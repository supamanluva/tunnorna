#!/usr/bin/env bash
# Bygger Tunnorna.apk med Android SDK-verktygen direkt (ingen Gradle behövs).
#
# Signering: om KEYSTORE_FILE är satt används den nyckeln (release), annars
# debug-nyckeln i ~/.android/debug.keystore. För release behövs även
# KEYSTORE_PASSWORD, KEY_ALIAS och KEY_PASSWORD (lösenorden läses från
# miljön, så de syns inte i processlistan).
#
# VERSION_CODE / VERSION_NAME skriver över värdena i AndroidManifest.xml om de är satta.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"

VERSION_ARGS=(--replace-version)
[[ -n "${VERSION_CODE:-}" ]] && VERSION_ARGS+=(--version-code "$VERSION_CODE")
[[ -n "${VERSION_NAME:-}" ]] && VERSION_ARGS+=(--version-name "$VERSION_NAME")

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml --java "$OUT/gen" \
    "${VERSION_ARGS[@]}" -o "$OUT/unsigned.apk" "$OUT/res.zip"

javac --release 11 -nowarn -encoding UTF-8 -classpath "$JAR" -d "$OUT/classes" \
    $(find app/src "$OUT/gen" -name '*.java')

"$BT/d8" --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [[ -n "${KEYSTORE_FILE:-}" ]]; then
    : "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD saknas}" "${KEY_ALIAS:?KEY_ALIAS saknas}" "${KEY_PASSWORD:?KEY_PASSWORD saknas}"
    echo "Signerar med release-nyckeln ($KEY_ALIAS)"
    "$BT/apksigner" sign --ks "$KEYSTORE_FILE" --ks-pass env:KEYSTORE_PASSWORD \
        --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PASSWORD \
        --out Tunnorna.apk "$OUT/aligned.apk"
else
    DEBUG_KS="$HOME/.android/debug.keystore"
    [[ -f "$DEBUG_KS" ]] || { echo "Ingen KEYSTORE_FILE satt och $DEBUG_KS finns inte." >&2; exit 1; }
    echo "Signerar med debug-nyckeln"
    "$BT/apksigner" sign --ks "$DEBUG_KS" --ks-pass pass:android \
        --ks-key-alias androiddebugkey --key-pass pass:android \
        --out Tunnorna.apk "$OUT/aligned.apk"
fi
"$BT/apksigner" verify --print-certs Tunnorna.apk | head -3
echo "Klart: $(pwd)/Tunnorna.apk"
