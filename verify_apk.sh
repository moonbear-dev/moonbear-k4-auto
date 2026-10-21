#!/usr/bin/env bash
# Full verification for the Moonbear Auto APK on a KVM-less host.
#
#   ./verify_apk.sh            # verify the APK already in dist/
#   ./verify_apk.sh --rebuild  # rebuild first, then verify
#
# 1. aapt2 badging / manifest tree  -> Android Auto declarations present
# 2. DEX type-table link check      -> every class the dex references is bundled
#    (this is the check that catches the NoClassDefFoundError class of crash)
# 3. Robolectric suite              -> the app's real classes run on an Android
#    runtime: activity launch, MediaBrowserService contract, Car App template
set -euo pipefail

ROOT="/root/moonbear-k4-auto"
APK="$ROOT/dist/moonbear-k4-auto.apk"
BT="/usr/lib/android-sdk/build-tools/35.0.1"
DEXDIR="$ROOT/build/verify/dex"

fail=0

if [ "${1:-}" = "--rebuild" ]; then
  echo "== rebuilding APK =="
  "$ROOT/build_apk.sh"
fi

echo "=================================================================="
echo "1. APK badging  ($APK)"
echo "=================================================================="
"$BT/aapt2" dump badging "$APK" | grep -E "^package:|application-label:|targetSdkVersion|uses-permission.*(RECORD_AUDIO|FOREGROUND)" || true

echo
echo "-- Android Auto declarations --"
"$BT/aapt2" dump xmltree "$APK" --file AndroidManifest.xml > /tmp/verify_manifest.txt
for needle in \
  'com.google.android.gms.car.application' \
  'android.media.browse.MediaBrowserService' \
  'androidx.media.session.MediaButtonReceiver' \
  'com.google.android.projection.gearhead' \
  'androidx.car.app.CarAppService' \
  'androidx.car.app.category' ; do
  if grep -q "$needle" /tmp/verify_manifest.txt; then
    echo "  [ok] $needle"
  else
    echo "  [FAIL] $needle"
    fail=1
  fi
done
for decl in media template; do
  if "$BT/aapt2" dump xmltree "$APK" --file res/xml/automotive_app_desc.xml 2>/dev/null | grep -q "name=\"$decl\""; then
    echo "  [ok] automotive_app_desc declares <uses name=\"$decl\"/>"
  else
    echo "  [FAIL] automotive_app_desc is missing <uses name=\"$decl\"/>"
    fail=1
  fi
done

echo
echo "=================================================================="
echo "2. DEX link check (every referenced class must be bundled)"
echo "=================================================================="
rm -rf "$DEXDIR"; mkdir -p "$DEXDIR"
(cd "$DEXDIR" && unzip -o -q "$APK" 'classes*.dex')
python3 /root/.hermes/tools/dex_link_check.py "$DEXDIR"/classes*.dex || fail=1

echo
echo "=================================================================="
echo "3. Robolectric suite (real app classes on an Android runtime)"
echo "=================================================================="
if [ -d "$ROOT/robolectric-test" ]; then
  "$ROOT/sync_test_classpath.sh" >/dev/null
  (cd "$ROOT/robolectric-test" && mvn -B test 2>&1 | grep -E "Tests run:|<<< (FAILURE|ERROR)|BUILD" | tail -12) || fail=1
else
  echo "  (skipped - robolectric-test project not present)"
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "RESULT: all verification steps passed"
else
  echo "RESULT: FAILURES present (see above)"
fi
exit "$fail"
