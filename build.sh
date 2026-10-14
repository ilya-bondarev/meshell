#!/bin/sh
set -eu
umask 077
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SOURCE/tools/toolchain.sh"
. "$SOURCE/tools/compile.sh"
KEY=${1:---unsigned}
if [ "$KEY" != --unsigned ]; then
    test -f "$KEY"
    test -n "${MESHELL_KEY_PASSWORD:-}"
fi
printf 'Native broker\n'
"$CC" --target="$TARGET" -std=c11 -Oz -flto -Wall -Wextra -Werror -fPIE -pie -fstack-protector-strong \
    -D_FORTIFY_SOURCE=2 -ffunction-sections -fdata-sections \
    -Wl,--gc-sections,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384 \
    "$SOURCE/native/meshelld.c" -o "$BUILD/meshelld"
"$STRIP" --strip-unneeded "$BUILD/meshelld"
printf 'Resources, Java and DEX\n'
compile_app "$BUILD/app"
rm -rf "$BUILD/assets-root"
mkdir -p "$BUILD/assets-root/assets"
cp "$BUILD/meshelld" "$SOURCE/device/start.sh" "$SOURCE/device/mkshrc" "$BUILD/assets-root/assets/"
cp "$BUILD/app/resources.apk" "$BUILD/unsigned.apk"
jar uf "$BUILD/unsigned.apk" -C "$BUILD/app/dex" classes.dex -C "$BUILD/assets-root" assets
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
sh "$SOURCE/tests/run_phone.sh"
if [ "$KEY" = --unsigned ]; then
    printf 'UNSIGNED_APK %s\n' "$BUILD/aligned.apk"
    exit 0
fi
java -Xmx256m -jar "$SIGNER_JAR" sign --ks "$KEY" --ks-key-alias "${MESHELL_KEY_ALIAS:-meshell}" \
    --ks-pass env:MESHELL_KEY_PASSWORD --out "$BUILD/MeShell.next.apk" "$BUILD/aligned.apk"
java -Xmx256m -jar "$SIGNER_JAR" verify --verbose "$BUILD/MeShell.next.apk"
if [ -f "$BUILD/MeShell.apk" ]; then mv -f "$BUILD/MeShell.apk" "$BUILD/MeShell.previous.apk"; fi
mv -f "$BUILD/MeShell.next.apk" "$BUILD/MeShell.apk"
sha256sum "$BUILD/MeShell.apk"
printf 'BUILD_OK %s\n' "$BUILD/MeShell.apk"
