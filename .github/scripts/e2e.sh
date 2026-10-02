#!/usr/bin/env bash
# Сквозные тесты на эмуляторе: приложение подключается к adbd самого эмулятора по 127.0.0.1:5555.
set -euxo pipefail

APP=com.prnoia.questremote
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
adb root || true
sleep 3
adb wait-for-device

./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Приложение пользуется тем же RSA-ключом, что и adb на CI, — эмулятор ему уже доверяет.
openssl pkey -in ~/.android/adbkey -outform DER -out /tmp/adbkey.pk8
openssl pkey -in ~/.android/adbkey -pubout -outform DER -out /tmp/adbkey.x509
adb shell mkdir -p /data/data/$APP/files/adb
adb push /tmp/adbkey.pk8 /data/data/$APP/files/adb/adbkey.pk8
adb push /tmp/adbkey.x509 /data/data/$APP/files/adb/adbkey.x509
APP_UID=$(adb shell stat -c %u /data/data/$APP | tr -d '\r')
adb shell chown -R "$APP_UID:$APP_UID" /data/data/$APP/files
adb shell restorecon -R /data/data/$APP/files || true

# Тестовое видео для клиента-плеера.
ffmpeg -loglevel error -f lavfi -i testsrc=duration=30:size=320x240:rate=25 \
  -f lavfi -i sine=frequency=440:duration=30 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest /tmp/qr_test.mp4
adb shell mkdir -p /sdcard/Movies
adb push /tmp/qr_test.mp4 /sdcard/Movies/qr_test.mp4

# Внутри эмулятора adbd работает через qemu-pipe, а не TCP; приложение достучится до него
# через ADB-порт эмулятора на хосте: изнутри это 10.0.2.2:5555 (emulator-5554).
adb shell getprop ro.adb.secure || true
adb shell 'echo | nc -w 3 10.0.2.2 5555 >/dev/null 2>&1 && echo "ADB-порт эмулятора доступен изнутри" || echo "WARN: 10.0.2.2:5555 недоступен"' || true

adb logcat -c || true
set +e
adb shell am instrument -w -r -e adbHost 10.0.2.2 -e adbPort 5555 $APP.test/androidx.test.runner.AndroidJUnitRunner | tee /tmp/instrument.txt
set -e
adb logcat -d > /tmp/logcat.txt || true
grep -E "INSTRUMENTATION_STATUS: test=|INSTRUMENTATION_STATUS_CODE: -2|^OK \(|FAILURES" /tmp/instrument.txt || true
grep -q "^OK (" /tmp/instrument.txt
