#!/bin/sh
set -eu
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD=${MESHELL_BUILD_DIR:-$SOURCE/build}
adb get-state >/dev/null
REMOTE=$(adb shell mktemp -d /data/local/tmp/meshell-tests.XXXXXXXX | tr -d '\r')
trap 'adb shell rm -rf "$REMOTE"' EXIT
adb push "$BUILD/tests/dex/classes.dex" "$REMOTE/classes.dex"
adb push "$BUILD/tests/app/resources.apk" "$REMOTE/resources.apk"
for language in en ru; do
    adb shell "CLASSPATH=$REMOTE/classes.dex app_process /system/bin com.mesystem.shell.FeatureTests $REMOTE/$language $REMOTE/resources.apk $language"
done
