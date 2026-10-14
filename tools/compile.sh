compile_app() {
    OUT=$1
    rm -rf "$OUT/classes" "$OUT/generated" "$OUT/dex"
    mkdir -p "$OUT/classes" "$OUT/generated" "$OUT/dex"
    "$AAPT2" compile --dir "$SOURCE/res" -o "$OUT/resources.zip"
    "$AAPT2" link -o "$OUT/resources.apk" -I "$ANDROID_JAR" --manifest "$SOURCE/AndroidManifest.xml" --java "$OUT/generated" "$OUT/resources.zip"
    find "$SOURCE/src" "$OUT/generated" -name '*.java' | sed 's/.*/"&"/' > "$OUT/sources.args"
    javac -J-Xmx512m --release 8 -parameters -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.args"
    jar cf "$OUT/classes.jar" -C "$OUT/classes" .
    java -Xmx512m -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_API" --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"
}
