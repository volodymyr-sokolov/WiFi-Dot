#!/bin/sh
# Build without Gradle: aapt -> javac -> dx -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
rm -rf build && mkdir -p build/gen build/classes build/stubs
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK" -F build/unsigned.apk
javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath "$SDK" -d build/stubs $(find stubs -name '*.java')
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -bootclasspath "$SDK" -classpath build/stubs \
  -d build/classes $(find src build/gen -name '*.java')
dalvik-exchange --dex --output=build/classes.dex build/classes
(cd build && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f wifidot.keystore ] || keytool -genkeypair -keystore wifidot.keystore -storepass wifidot \
  -keypass wifidot -alias wifidot -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=WiFi Dot" >/dev/null 2>&1
apksigner sign --ks wifidot.keystore --ks-pass pass:wifidot --key-pass pass:wifidot \
  --out WiFiDot.apk build/aligned.apk
apksigner verify -v WiFiDot.apk | head -4
