MIN_API=$(sed -n 's/.*android:minSdkVersion="\([0-9]*\)".*/\1/p' "$SOURCE/AndroidManifest.xml")
TARGET=aarch64-linux-android$MIN_API
PHONE=0
if [ -x /system/bin/getprop ]; then
    PHONE=1
    TOOL_ROOT=/data/local/tmp/meshell/toolusr
    export HOME=/data/local/tmp/meshell/home
    export JAVA_HOME=$TOOL_ROOT/lib/jvm/java-21-openjdk
    export TMPDIR=$HOME/tmp
    export PATH=$JAVA_HOME/bin:$TOOL_ROOT/bin:/system/bin
    unset LD_LIBRARY_PATH
    ANDROID_JAR=$HOME/sdk/android.jar
    D8_JAR=$HOME/sdk/d8.jar
    SIGNER_JAR=$HOME/sdk/apksigner.jar
    CC=$TOOL_ROOT/bin/clang
    STRIP=$TOOL_ROOT/bin/llvm-strip
    AAPT2=$TOOL_ROOT/bin/aapt2
    ZIPALIGN=$TOOL_ROOT/bin/zipalign
    DEFAULT_BUILD=$HOME/build
else
    [ "$(uname -sm)" = 'Linux x86_64' ] || { echo 'Use Linux x86_64 or WSL2'; exit 1; }
    SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME to the Android SDK}}
    PLATFORM=$(sed -n '1s/platforms;//p' "$SOURCE/tools/sdk-packages.txt")
    TOOLS_VERSION=$(sed -n '2s/build-tools;//p' "$SOURCE/tools/sdk-packages.txt")
    NDK_VERSION=$(sed -n '3s/ndk;//p' "$SOURCE/tools/sdk-packages.txt")
    TOOLS=$SDK/build-tools/$TOOLS_VERSION
    NDK=$SDK/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin
    ANDROID_JAR=$SDK/platforms/$PLATFORM/android.jar
    D8_JAR=$TOOLS/lib/d8.jar
    SIGNER_JAR=$TOOLS/lib/apksigner.jar
    CC=$NDK/clang
    STRIP=$NDK/llvm-strip
    AAPT2=$TOOLS/aapt2
    ZIPALIGN=$TOOLS/zipalign
    DEFAULT_BUILD=$SOURCE/build
fi
BUILD=${MESHELL_BUILD_DIR:-$DEFAULT_BUILD}
test -f "$ANDROID_JAR"
test -f "$D8_JAR"
mkdir -p "$BUILD" "${TMPDIR:-/tmp}"
