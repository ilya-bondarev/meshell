#!/system/bin/sh
set -eu
umask 077
ROOT=/data/local/tmp/meshell
export HOME=$ROOT/home
export PATH=$ROOT/toolusr/bin:/system/bin
unset LD_LIBRARY_PATH
exec 9>"$HOME/self-update.lock"
flock -n 9 9>&9 || { echo 'Another updater is running'; exit 1; }
STAGE=$(mktemp -d "$HOME/tmp/update.XXXXXXXX")
changed=0
cleanup() {
    status=$?
    trap - EXIT
    if [ "$status" -ne 0 ] && [ "$changed" -eq 1 ]; then
        echo SELF_UPDATE_FAILED_ROLLING_BACK
        pm install -r -d --user 0 "$STAGE/old.apk" && cp "$STAGE/old-broker" "$ROOT/meshelld" && cp "$STAGE/old-start" "$ROOT/start.sh" && am start -W --user 0 -n com.mesystem.shell/.MainActivity && sh "$ROOT/start.sh" || echo SELF_UPDATE_ROLLBACK_FAILED
    fi
    rm -rf "$STAGE"
    exit "$status"
}
trap cleanup EXIT
sleep 2
APK=$STAGE/new.apk
cp "$HOME/build/MeShell.apk" "$APK"
OLD=$(pm path --user 0 com.mesystem.shell | sed -n 's/^package://p')
cp "$OLD" "$STAGE/old.apk"
cp "$ROOT/meshelld" "$STAGE/old-broker"
cp "$ROOT/start.sh" "$STAGE/old-start"
SIGNER=$HOME/sdk/apksigner.jar
NAME=$(aapt dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[ "$NAME" = com.mesystem.shell ] || { echo 'Wrong APK package'; exit 1; }
OLD_CERT=$(java -Xmx256m -jar "$SIGNER" verify --print-certs "$STAGE/old.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
NEW_CERT=$(java -Xmx256m -jar "$SIGNER" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ -n "$OLD_CERT" ] && [ "$OLD_CERT" = "$NEW_CERT" ] || { echo 'Signing certificate mismatch'; exit 1; }
unzip -p "$APK" assets/meshelld > "$STAGE/broker"
unzip -p "$APK" assets/start.sh > "$STAGE/start"
test -s "$STAGE/broker" && test -s "$STAGE/start"
cp "$STAGE/old.apk" "$HOME/build/MeShell.rollback.apk"
echo 'Installing verified APK and its embedded broker'
pm install -r --user 0 "$APK"
changed=1
am start -W --user 0 -n com.mesystem.shell/.MainActivity
cp "$STAGE/broker" "$ROOT/meshelld.next"
chmod 700 "$ROOT/meshelld.next"
mv -f "$ROOT/meshelld.next" "$ROOT/meshelld"
cp "$STAGE/start" "$ROOT/start.sh"
sh "$ROOT/start.sh"
am start --user 0 -n com.mesystem.shell/.MainActivity
echo SELF_UPDATE_OK
sha256sum "$APK"
date
