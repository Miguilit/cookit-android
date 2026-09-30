#!/usr/bin/env bash
set -euo pipefail

APP="app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt"
CASH="app/src/main/java/be/cookit/pos/android/ui/CashRegisterScreen.kt"
GRADLE="app/build.gradle.kts"

echo "========================================================"
echo " COOKIT ANDROID — IOS-INSPIRED UI FINAL HOTFIX CHECK"
echo "========================================================"

grep -q 'versionCode = 84' "$GRADLE"
grep -q 'versionName = "0.15.0.49"' "$GRADLE"
echo "VERSION_METADATA=yes"

grep -q 'fillMaxHeight().navigationBarsPadding()' "$APP"
echo "TABLET_BOTTOM_SAFE_INSET=yes"

grep -q 'color = CookitInk' "$APP"
grep -q 'contentColor = CookitInk' "$APP"
echo "LIGHT_FOREGROUND=yes"

# Three custom full-width sheets must use dark Cookit surfaces.
count=$(grep -c 'color = CookitSurfaceRaised' "$APP" || true)
if [ "$count" -lt 3 ]; then
  echo "FAIL: expected at least 3 dark custom dialog surfaces, got $count"
  exit 1
fi
echo "DARK_CUSTOM_DIALOGS=yes"

grep -q 'color = if (good) CookitSoftGreen else CookitSoftOrange' "$APP"
echo "DARK_FISCAL_STATUS_TILES=yes"

grep -q 'private val CashInk = Color(0xFFF4F7FA)' "$CASH"
if [ "$(grep -c 'color = CashInk' "$CASH" || true)" -lt 2 ]; then
  echo "FAIL: Cash Register main titles not explicitly readable"
  exit 1
fi
echo "CASH_TITLE_FOREGROUND=yes"

# Guard: this hotfix must remain visual-only.
for forbidden in \
  'app/src/main/java/be/cookit/pos/android/data/fiscal' \
  'app/src/main/java/be/cookit/pos/android/service/Fiscal' \
  'app/src/main/java/be/cookit/pos/android/data/NativePosApi'; do
  if git diff --name-only 2>/dev/null | grep -Fq "$forbidden"; then
    echo "FAIL: non-UI/fiscal runtime file changed: $forbidden"
    exit 1
  fi
done
echo "RUNTIME_SCOPE_GUARD=yes"

echo "IOS_INSPIRED_UI_FINAL_HOTFIX_CHECK=PASS"
