#!/bin/sh
set -eu
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$SOURCE/tools/toolchain.sh"
. "$SOURCE/tools/compile.sh"
TEST=$BUILD/tests
mkdir -p "$TEST"
java "$SOURCE/tools/RepositoryCheck.java" "$SOURCE"
if [ "$PHONE" -eq 1 ]; then
    "$CC" --target="$TARGET" -std=c11 -Wall -Wextra -Werror "$SOURCE/tests/handshake_test.c" -o "$TEST/handshake-test"
else
    clang -std=c11 -Wall -Wextra -Werror "$SOURCE/tests/handshake_test.c" -o "$TEST/handshake-test"
fi
"$TEST/handshake-test"
sh "$SOURCE/tests/file_write_test.sh"
compile_app "$TEST/app"
rm -rf "$TEST/classes" "$TEST/dex"
mkdir -p "$TEST/classes" "$TEST/dex"
find "$SOURCE/tests" -name '*.java' | sed 's/.*/"&"/' > "$TEST/tests.args"
javac --release 8 -parameters -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR:$TEST/app/classes.jar" -d "$TEST/classes" @"$TEST/tests.args"
jar cf "$TEST/tests.jar" -C "$TEST/classes" .
java -Xmx512m -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_API" --lib "$ANDROID_JAR" --output "$TEST/dex" "$TEST/app/classes.jar" "$TEST/tests.jar"
if [ "$PHONE" -eq 1 ]; then
    CLASSPATH="$TEST/dex/classes.dex" app_process /system/bin com.mesystem.shell.FeatureTests "$TEST/run-$(date +%s)" "$TEST/app/resources.apk" "${1:-en}"
else
    java -cp "$TEST/app/classes.jar:$TEST/tests.jar" com.mesystem.shell.DomainTests
    printf 'Android integration tests compiled; execution requires a device.\n'
fi
