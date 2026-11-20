#!/bin/bash
# Builds and tests the iPad app on a macOS runner. Every stage writes into out/, which is published
# to the ipad-logs branch whether the run passes or not.
set -u
ROOT="$(pwd)"
APP="$ROOT/ipad/Wakeel.swiftpm"
OUT="$ROOT/out"
mkdir -p "$OUT/files" "$OUT/sim-files"
SUMMARY="$OUT/summary.txt"
FAILED=0
note() { echo "$*" | tee -a "$SUMMARY"; }
stage() { if [ "$2" -eq 0 ]; then note "OK    $1"; else note "FAIL  $1"; FAILED=1; fi; }

{ xcodebuild -version; swift --version 2>&1 | head -2; } > "$OUT/tools.txt" 2>&1
note "$(head -1 "$OUT/tools.txt") · $(sed -n 2p "$OUT/tools.txt")"
note ""

# ---- 1. logic tests: the app's own source files, without a screen
CORE="TextTools Schedule Commands Models Zip Docx Xlsx Pptx Skills Prompt LLM SettingsStore"
SRC=""; for f in $CORE; do SRC="$SRC $APP/$f.swift"; done
swiftc -o "$OUT/logic" $SRC "$ROOT/ipad/Tests/main.swift" > "$OUT/logic-build.txt" 2>&1; rc=$?
if [ $rc -eq 0 ]; then "$OUT/logic" "$OUT/files" > "$OUT/logic.txt" 2>&1; rc=$?; else cp "$OUT/logic-build.txt" "$OUT/logic.txt"; fi
rm -f "$OUT/logic"
stage "logic tests — $(tail -1 "$OUT/logic.txt" | cut -c1-120)" $rc

python3 -m pip install --quiet --break-system-packages python-docx python-pptx openpyxl > "$OUT/pip.txt" 2>&1
python3 "$ROOT/ipad/Tests/validate.py" "$OUT/files" > "$OUT/files.txt" 2>&1; rc=$?
stage "sample Office files open in independent libraries — $(tail -1 "$OUT/files.txt")" $rc

# ---- 2. the whole app type-checks against the iPad SDK (all errors at once)
SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun swiftc -typecheck -parse-as-library -sdk "$SDK" -target arm64-apple-ios17.0-simulator -module-name AppModule \
  $(ls "$APP"/*.swift | grep -v Package.swift) > "$OUT/typecheck.txt" 2>&1; rc=$?
grep -E "error:" "$OUT/typecheck.txt" | sed "s#$APP/##" | sort -u > "$OUT/errors.txt"
stage "type-check against the iPad SDK ($(wc -l < "$OUT/errors.txt" | tr -d ' ') errors, $(grep -c 'warning:' "$OUT/typecheck.txt") warnings)" $rc

# ---- 3. build the Swift Playgrounds project as it is shipped
cd "$APP"
xcodebuild -list > "$OUT/schemes.txt" 2>&1
xcodebuild -scheme Wakeel -destination 'generic/platform=iOS Simulator' -derivedDataPath "$ROOT/dd" CODE_SIGNING_ALLOWED=NO build > "$OUT/build.txt" 2>&1; rc=$?
grep -E "error:|BUILD (SUCCEEDED|FAILED)" "$OUT/build.txt" | sed "s#$APP/##" | sort -u | tail -40 > "$OUT/build-errors.txt"
tail -c 30000 "$OUT/build.txt" > "$OUT/build-tail.txt"; rm -f "$OUT/build.txt"
stage "xcodebuild of Wakeel.swiftpm" $rc
cd "$ROOT"
BUNDLE="$(find "$ROOT/dd/Build/Products" -maxdepth 2 -name '*.app' 2>/dev/null | head -1)"

# ---- 4. on an iPad simulator: the built-in self-test, then screenshots of every tab
if [ -n "$BUNDLE" ]; then
  UDID="$(xcrun simctl list devices available -j | python3 -c "
import json, sys
d = json.load(sys.stdin)['devices']
pads = [(k, x) for k, v in d.items() for x in v if 'iPad' in x['name'] and x.get('isAvailable')]
pads.sort(key=lambda t: (t[0], 'Pro' in t[1]['name'], '11' in t[1]['name']))
print(pads[-1][1]['udid'] if pads else '')")"
  note "simulator: $(xcrun simctl list devices available | grep "$UDID" | sed 's/ *(.*//' | xargs)"
  xcrun simctl boot "$UDID" > /dev/null 2>&1; xcrun simctl bootstatus "$UDID" -b > /dev/null 2>&1
  xcrun simctl install "$UDID" "$BUNDLE" > "$OUT/install.txt" 2>&1; rc=$?
  stage "the app installs on the simulator" $rc
  BID="com.wakeel.ipad"
  LANG_ARGS="-AppleLanguages (ar) -AppleLocale ar_QA"
  xcrun simctl launch "$UDID" "$BID" -selftest $LANG_ARGS > "$OUT/launch.txt" 2>&1
  DATA="$(xcrun simctl get_app_container "$UDID" "$BID" data 2>/dev/null)"
  REPORT="$DATA/Documents/selftest.txt"
  for i in $(seq 1 100); do
    sleep 3
    [ "$i" -eq 5 ] && xcrun simctl io "$UDID" screenshot "$OUT/selftest-running.png" > /dev/null 2>&1
    grep -q "^RESULT:" "$REPORT" 2>/dev/null && break
  done
  xcrun simctl io "$UDID" screenshot "$OUT/selftest-end.png" > /dev/null 2>&1
  cp "$REPORT" "$OUT/selftest.txt" 2>/dev/null || echo "no report was written" > "$OUT/selftest.txt"
  grep -q "^RESULT: OK" "$OUT/selftest.txt"; rc=$?
  stage "self-test on the simulator — $(grep '^RESULT' "$OUT/selftest.txt" || echo 'did not finish')" $rc
  cat "$OUT/selftest.txt" >> "$SUMMARY"; note ""
  cp "$DATA/Documents/ملفات وكيل/"* "$OUT/sim-files/" 2>/dev/null
  python3 "$ROOT/ipad/Tests/validate.py" "$OUT/sim-files" > "$OUT/sim-files.txt" 2>&1; rc=$?
  stage "the files made on the simulator open in independent libraries — $(tail -1 "$OUT/sim-files.txt")" $rc
  xcrun simctl spawn "$UDID" log show --last 6m --predicate 'process == "Wakeel"' --style compact 2>/dev/null | grep -iE "fatal|crash|exception|error" | tail -40 > "$OUT/device-log.txt"
  ls "$HOME/Library/Logs/DiagnosticReports" 2>/dev/null | grep -i wakeel > "$OUT/crashes.txt"
  [ -s "$OUT/crashes.txt" ] && { note "FAIL  the app crashed: $(head -3 "$OUT/crashes.txt" | xargs)"; FAILED=1; cp "$HOME/Library/Logs/DiagnosticReports/"*akeel* "$OUT/" 2>/dev/null; }

  for TAB in 0 1 2 3 4; do
    xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
    xcrun simctl launch "$UDID" "$BID" -demo -tab "$TAB" $LANG_ARGS > /dev/null 2>&1
    sleep 7
    xcrun simctl io "$UDID" screenshot "$OUT/tab$TAB.png" > /dev/null 2>&1
  done
  xcrun simctl ui "$UDID" appearance dark > /dev/null 2>&1
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
  xcrun simctl launch "$UDID" "$BID" -demo -tab 0 $LANG_ARGS > /dev/null 2>&1; sleep 7
  xcrun simctl io "$UDID" screenshot "$OUT/tab0-dark.png" > /dev/null 2>&1
  for f in "$OUT"/*.png; do sips -Z 1500 "$f" > /dev/null 2>&1; done
  ls "$OUT"/tab*.png > /dev/null 2>&1; stage "screenshots of every tab" $?
else
  note "FAIL  no app bundle to run"; FAILED=1
fi

# ---- 5. the project as the owner receives it
( cd "$ROOT/ipad" && zip -qr "$OUT/Wakeel-iPad.zip" Wakeel.swiftpm -x '*.DS_Store' -x '*/.build/*' -x '*/.swiftpm/*' )
note ""
note "project: Wakeel-iPad.zip · $(wc -c < "$OUT/Wakeel-iPad.zip" | tr -d ' ') bytes · sha256 $(shasum -a 256 "$OUT/Wakeel-iPad.zip" | cut -c1-16) · $(ls "$APP"/*.swift | wc -l | tr -d ' ') Swift files, $(cat "$APP"/*.swift | wc -l | tr -d ' ') lines"
note "$([ $FAILED -eq 0 ] && echo 'ALL STAGES PASSED' || echo 'SOME STAGES FAILED')"
exit $FAILED
