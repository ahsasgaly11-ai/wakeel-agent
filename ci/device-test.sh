#!/usr/bin/env bash
# Runs on the CI emulator after every push. Two parts:
#   1) the release build exactly as shipped (minified): install, open, switch the service on, run one real task;
#   2) the on-device test suite (debug build + a small sandbox app the agent operates on).
# Everything it learns goes to device-report/, which the workflow publishes on the `device-logs` branch.
set +e
R="$PWD/device-report"
mkdir -p "$R"
PKG=com.wakeel.agent
SVC="$PKG/$PKG.service.AgentAccessibilityService"
say() { echo "$@" | tee -a "$R/summary.txt"; }
shot() { adb exec-out screencap -p > "$R/$1.png" 2>/dev/null; }
prop() { adb shell getprop "$1" | tr -d '\r'; }

adb logcat -G 16M > /dev/null 2>&1
say "emulator: $(prop ro.product.model) · Android $(prop ro.build.version.release) (API $(prop ro.build.version.sdk)) · $(adb shell wm size | tr -d '\r')"
adb shell input keyevent 82 > /dev/null 2>&1   # dismiss the lock screen if there is one
adb shell input keyevent 3 > /dev/null 2>&1

# ---------------------------------------------------------------- 1) the exact released file, installed as an update
# The APK tested here is the very file the build job signed and published (not a rebuild),
# and it is installed over the older versions a phone may still have — the way it will really be installed.
SHIPPED=$(ls shipped/*.apk 2>/dev/null | head -1)
if [ -z "$SHIPPED" ]; then SHIPPED=app/build/outputs/apk/release/app-release.apk; fi
say ""
say "== THE RELEASED FILE: $(basename "$SHIPPED") · sha256 $(sha256sum "$SHIPPED" | cut -c1-16) =="
crashes() { adb logcat -d -v time 2>/dev/null | grep -c "FATAL EXCEPTION"; }
running() { [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ]; }

# A) over the last 1.x version: only that the update installs and the app opens on the old data
if [ -f old/Wakeel-v1.0.13.apk ]; then
  adb logcat -c
  adb install -r old/Wakeel-v1.0.13.apk > "$R/update-v1.txt" 2>&1
  adb shell am start -W -n "$PKG/.ui.MainActivity" >> "$R/update-v1.txt" 2>&1
  sleep 8
  shot 0a-v1.0.13-before-update
  if adb install -r "$SHIPPED" >> "$R/update-v1.txt" 2>&1; then say "update over v1.0.13 installs: OK"; else say "update over v1.0.13 installs: FAILED"; fi
  adb shell am start -W -n "$PKG/.ui.MainActivity" >> "$R/update-v1.txt" 2>&1
  sleep 8
  shot 0b-after-update-from-v1
  if running && [ "$(crashes)" = "0" ]; then say "opens on v1.0.13 data without crashing: OK"; else say "opens on v1.0.13 data without crashing: FAILED"; fi
  adb uninstall "$PKG" > /dev/null 2>&1
fi

# B) over v2.0.16, after really using it: the service on and one task in its history
UPDATED=no
if [ -f old/Wakeel-v2.0.16.apk ]; then
  adb logcat -c
  adb install -r old/Wakeel-v2.0.16.apk > "$R/update-v2.txt" 2>&1
  adb shell am start -W -n "$PKG/.ui.MainActivity" >> "$R/update-v2.txt" 2>&1
  sleep 8
  adb shell settings put secure enabled_accessibility_services "$SVC"
  adb shell settings put secure accessibility_enabled 1
  sleep 8
  adb shell input tap 88 760
  sleep 4
  python3 ci/ui.py tap "شخّص الهاتف" 6 >> "$R/update-v2.txt" 2>&1
  sleep 2
  python3 ci/ui.py tap "تنفيذ" 7 >> "$R/update-v2.txt" 2>&1
  sleep 10
  shot 0c-v2.0.16-task-before-update
  adb shell input keyevent 4; sleep 2
  if adb install -r "$SHIPPED" >> "$R/update-v2.txt" 2>&1; then say "update over v2.0.16 installs: OK"; UPDATED=yes; else say "update over v2.0.16 installs: FAILED"; fi
  say "version on the device after the update: $(adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')"
  sleep 6
  if adb shell settings get secure enabled_accessibility_services | grep -q "$PKG"; then say "accessibility service still switched on after the update: yes"; else say "accessibility service still switched on after the update: NO (it has to be switched on again)"; fi
fi
if [ "$UPDATED" != "yes" ]; then
  adb uninstall "$PKG" > /dev/null 2>&1
  if adb install -r "$SHIPPED" > "$R/release-install.txt" 2>&1; then say "clean install: OK"; else say "clean install: FAILED"; fi
fi

adb shell am start -W -n "$PKG/.ui.MainActivity" > "$R/release-start.txt" 2>&1
sleep 10
shot 1-release-main
if running; then say "app opens and stays open: OK"; else say "app opens and stays open: FAILED"; fi

if [ "$UPDATED" = "yes" ]; then
  # What was done in the old version must still be there
  python3 ci/ui.py tap "السجل" 5 >> "$R/release-ui.txt" 2>&1
  sleep 3
  python3 ci/ui.py dump > "$R/update-history-text.txt" 2>&1
  shot 1b-history-after-update
  if grep -q "شخّص الهاتف" "$R/update-history-text.txt"; then say "history from v2.0.16 is still there after the update: OK"; else say "history from v2.0.16 is still there after the update: NOT CONFIRMED (see 1b-history-after-update.png)"; fi
  python3 ci/ui.py tap "القيادة" 5 >> "$R/release-ui.txt" 2>&1
  sleep 2
fi

adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
sleep 8
adb shell dumpsys accessibility > "$R/release-accessibility.txt" 2>&1
if grep -i "bound services" "$R/release-accessibility.txt" | grep -q "Service\["; then say "accessibility service binds: OK"; else say "accessibility service binds: FAILED"; fi
shot 2-release-service-on

# The floating bubble opens the quick-command window…
adb shell input tap 88 760
sleep 4
shot 3-release-bubble-command
python3 ci/ui.py dump > "$R/release-command-text.txt" 2>&1
if grep -q "تنفيذ" "$R/release-command-text.txt"; then say "the bubble opens the command window: OK"; else say "the bubble opens the command window: FAILED"; fi
sleep 6

# …and one real task goes through it: bubble → command → engine → result window.
# (Device diagnosis: it runs locally, so it needs no API key.)
python3 ci/ui.py tap "شخّص الهاتف" 6 >> "$R/release-ui.txt" 2>&1
sleep 2
python3 ci/ui.py tap "تنفيذ" 7 >> "$R/release-ui.txt" 2>&1
sleep 10
shot 4-release-task-result
python3 ci/ui.py dump > "$R/release-result-text.txt" 2>&1
if grep -q "مهمة جديدة" "$R/release-result-text.txt" && grep -q "حالة الهاتف" "$R/release-result-text.txt"; then
  say "a task runs from the bubble to its result window: OK"
else
  say "a task runs from the bubble to its result window: NOT CONFIRMED (see 4-release-task-result.png)"
fi
adb shell input keyevent 4; sleep 2

# Teaching by hand: the command window starts a recording, the bubble turns red and opens the recording panel
adb shell input keyevent 3; sleep 2
adb shell input tap 88 760
sleep 4
python3 ci/ui.py tap "علّمني مهمة بيدك" 6 >> "$R/release-ui.txt" 2>&1
sleep 2
adb shell input text "open%sthe%sclock"
sleep 1
shot 5-release-teach-window
python3 ci/ui.py tap "ابدأ التسجيل" 7 >> "$R/release-ui.txt" 2>&1
sleep 4
shot 6-release-recording
adb shell input tap 88 760
sleep 3
shot 7-release-recording-panel
adb shell input tap 88 760   # close the panel, keep recording
sleep 2

# …then a real demonstration with real touches: open Settings, tap its first row, go back to the home screen.
# After a still moment the skill must save itself and say so.
adb shell am start -n com.android.settings/.Settings > /dev/null 2>&1
sleep 6
adb shell input tap 540 890
sleep 4
shot 7b-release-demonstration
adb shell input keyevent 3
sleep 8
shot 7c-release-saved-by-itself

adb logcat -d -v time > "$R/release-logcat-full.txt" 2>&1
grep -E "FATAL EXCEPTION|AndroidRuntime|$PKG" "$R/release-logcat-full.txt" | grep -E "FATAL|AndroidRuntime: |Exception|Error" | head -150 > "$R/release-errors.txt"
rm -f "$R/release-logcat-full.txt"
FATAL=$(grep -c "FATAL EXCEPTION" "$R/release-errors.txt")
if [ "$FATAL" = "0" ]; then say "crashes: none"; else say "crashes: $FATAL (see release-errors.txt)"; fi
if running; then say "still running at the end: OK"; else say "still running at the end: FAILED"; fi
# What the app kept on the phone (file names only): the taught skill must be among them
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime" | head -4 > "$R/release-package.txt" 2>&1
adb uninstall "$PKG" > /dev/null 2>&1

# ---------------------------------------------------------------- 2) on-device test suite
say ""
say "== ON-DEVICE TESTS (real accessibility service, real taps; the AI model is scripted) =="
adb install -r app/build/outputs/apk/debug/app-debug.apk > "$R/test-install.txt" 2>&1
adb install -r sandbox/build/outputs/apk/debug/sandbox-debug.apk >> "$R/test-install.txt" 2>&1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$R/test-install.txt" 2>&1
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >> "$R/test-install.txt" 2>&1
adb shell input keyevent 3
adb logcat -c

# A few pictures while the tests run
( for i in 01 02 03 04 05 06 07 08 09 10 11 12 13 14; do sleep 14; adb exec-out screencap -p > "$R/8-tests-$i.png" 2>/dev/null; done ) &
SHOTS=$!
timeout 1500 adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$R/tests-instrument.txt" 2>&1
kill "$SHOTS" > /dev/null 2>&1
shot 9-after-tests

adb exec-out run-as "$PKG" cat files/device-report.txt > "$R/tests-report.txt" 2>/dev/null
adb logcat -d -v time -s WakeelTest:V AndroidRuntime:E TestRunner:I > "$R/tests-logcat.txt" 2>&1

# One line per test
python3 - "$R/tests-instrument.txt" >> "$R/summary.txt" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
order, state = [], {}
for block in re.split(r"(?=INSTRUMENTATION_STATUS: class=)", text):
    name = re.search(r"INSTRUMENTATION_STATUS: test=(\S+)", block)
    code = re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", block)
    if not name or not code:
        continue
    n = name.group(1)
    if n not in state:
        order.append(n)
    state[n] = code[-1]
for n in order:
    print(("PASS  " if state[n] == "0" else "FAIL  ") + n)
tail = re.search(r"(OK \(\d+ tests?\)|Tests run: \d+,\s+Failures: \d+)", text)
print("result: " + (tail.group(1) if tail else "the test run did not complete"))
PY
cat "$R/summary.txt"

grep -q "^OK (" "$R/tests-instrument.txt"
