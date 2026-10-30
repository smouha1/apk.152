#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Requires: JDK 17+, Android SDK (ANDROID_HOME or local.properties sdk.dir)
if [ ! -f local.properties ]; then
  if [ -n "${ANDROID_HOME:-}" ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
  elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
  else
    echo "ERROR: set ANDROID_HOME or create local.properties with sdk.dir=..."
    exit 1
  fi
fi
mkdir -p /tmp/smouha-keys
if [ ! -f /tmp/smouha-keys/debug.keystore ]; then
  keytool -genkeypair -keystore /tmp/smouha-keys/debug.keystore \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Smouha,O=Smouha,C=EG"
fi
if [ -x ./gradlew ]; then
  ./gradlew :app:assembleRelease --no-daemon
else
  gradle :app:assembleRelease --no-daemon
fi
APK=$(find app/build/outputs/apk -name '*.apk' | head -1)
echo "APK: $APK"
ls -la "$APK"
