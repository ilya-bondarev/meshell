#!/system/bin/sh
set -eu
ROOT=/data/local/tmp/meshell
P=$ROOT/toolusr
mkdir -p "$ROOT/home/tmp" "$ROOT/home/bin" "$ROOT/home/source" "$ROOT/home/build"
# Clang derives its sysroot as bin/../.. and expects usr/include there.
if [ ! -e "$ROOT/usr" ] && [ ! -L "$ROOT/usr" ]; then ln -s toolusr "$ROOT/usr"; fi
for name in java javac jar javap keytool jdeps jlink; do
    if [ -x "$P/lib/jvm/java-21-openjdk/bin/$name" ]; then
        ln -sf "../lib/jvm/java-21-openjdk/bin/$name" "$P/bin/$name"
    fi
done
export HOME=$ROOT/home
export JAVA_HOME=$P/lib/jvm/java-21-openjdk
export PATH=$P/bin:/system/bin
export LD_LIBRARY_PATH=$P/lib
java -version
javac -version
clang --version
aapt2 version
zipalign -h 2>&1 || true
adb version
