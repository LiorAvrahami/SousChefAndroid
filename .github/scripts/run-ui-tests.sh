#!/bin/bash
# Runs the on-device smoke tests against the booted emulator.
# Expects ui-test-apks/{app-v1.apk,app-v2.apk,app-test.apk} (built by ui-test.yml).
# Writes everything worth looking at into ui-test-output/.
set -u

PKG=com.lioravrahami.souschef
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
UPGRADE_PKG="$PKG.upgrade"
OUT=ui-test-output
mkdir -p "$OUT/screenshots"
STATUS=0

# Runs one instrumentation pass; $1 = label, rest = extra "am instrument" arguments.
run_pass() {
  local label="$1"; shift
  echo "=== instrumentation pass: $label ==="
  adb shell am instrument -w "$@" "$RUNNER" 2>&1 | tee "$OUT/instrument-$label.txt"
  if grep -q "^OK (" "$OUT/instrument-$label.txt" \
     && ! grep -q -E "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED" "$OUT/instrument-$label.txt"; then
    echo "=== pass '$label': OK ==="
  else
    echo "=== pass '$label': FAILED ==="
    STATUS=1
  fi
}

adb logcat -c || true

# 1. Fresh install of version 1 (-g grants runtime permissions such as notifications).
adb install -r -g ui-test-apks/app-v1.apk
adb install -r -g ui-test-apks/app-test.apk

# 2. Seed data with version 1, update to version 2 in place, verify the data survived.
run_pass upgrade-seed -e class "$UPGRADE_PKG.UpgradeSeedTest"
adb install -r -g ui-test-apks/app-v2.apk
run_pass upgrade-verify -e class "$UPGRADE_PKG.UpgradeVerifyTest"

# 3. Everything else: database, alarm reliability, full user journey.
run_pass main -e notPackage "$UPGRADE_PKG"

# 4. Collect evidence.
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "AndroidRuntime|FATAL EXCEPTION|souschef" "$OUT/logcat.txt" > "$OUT/logcat-app.txt" 2>/dev/null || true
adb shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime" > "$OUT/package-info.txt" || true
for f in $(adb shell run-as "$PKG" ls files/screenshots 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$PKG" cat "files/screenshots/$f" > "$OUT/screenshots/$f" || true
done
echo "screenshots collected: $(ls "$OUT/screenshots" | wc -l)"

exit $STATUS
