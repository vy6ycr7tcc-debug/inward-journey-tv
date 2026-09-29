#!/bin/bash
set -e
cd "$(dirname "$0")"

SDK="/opt/android-sdk"
KCP="$(echo /usr/share/gradle-8.8/lib/*.jar | tr ' ' ':')"
STDLIB="/usr/share/gradle-8.8/lib/kotlin-stdlib-1.9.22.jar"
JUNIT="/usr/share/gradle-8.8/lib/junit-4.13.2.jar"
HAMCREST="/usr/share/gradle-8.8/lib/hamcrest-core-1.3.jar"
WORK="$(pwd)/manual-build"

# Ensure BuildConfig stub exists
mkdir -p "$WORK/app/inwardjourney/tv"
VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]\+\([0-9][0-9]*\).*/\1/p' app/build.gradle | head -1)"
VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\(.*\)".*/\1/p' app/build.gradle | head -1)"
cat > "$WORK/app/inwardjourney/tv/BuildConfig.java" <<EOFB
package app.inwardjourney.tv;
public final class BuildConfig {
    public static final boolean DEBUG = true;
    public static final int VERSION_CODE = $VERSION_CODE;
    public static final String VERSION_NAME = "$VERSION_NAME";
}
EOFB

TEST_OUT="$WORK/out/test-classes"
rm -rf "$TEST_OUT"
mkdir -p "$TEST_OUT"

echo "== Compiling test stubs =="
javac -d "$TEST_OUT" \
  manual-build/test-stubs/android/util/Log.java \
  manual-build/test-stubs/org/json/JSONException.java \
  manual-build/test-stubs/org/json/JSONObject.java

echo "== Compiling main & test sources =="
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  app/src/main/java/app/inwardjourney/tv/LauncherUpdate.kt \
  $(find app/src/test/java -name "*.kt" 2>/dev/null || true) \
  "$WORK/app/inwardjourney/tv/BuildConfig.java" \
  -classpath "$TEST_OUT:$STDLIB:$JUNIT" \
  -d "$TEST_OUT" -jvm-target 17 -no-stdlib

echo "== Running JUnit Tests =="
java -cp "$TEST_OUT:$STDLIB:$JUNIT:$HAMCREST" \
  org.junit.runner.JUnitCore app.inwardjourney.tv.LauncherUpdateTest
