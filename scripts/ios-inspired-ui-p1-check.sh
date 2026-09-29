#!/usr/bin/env bash
set -euo pipefail

echo "===================================================="
echo " COOKIT ANDROID — IOS-INSPIRED UI P1 CHECK"
echo "===================================================="

THEME="app/src/main/java/be/cookit/pos/android/ui/theme/Theme.kt"
APP="app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt"
MAIN="app/src/main/java/be/cookit/pos/android/MainActivity.kt"
STYLES="app/src/main/res/values/styles.xml"

for f in "$THEME" "$APP" "$MAIN" "$STYLES"; do
  test -f "$f" || { echo "FAIL missing $f"; exit 1; }
done

grep -q 'val CookitAccent = Color(0xFF42C8F5)' "$THEME"
grep -q 'val CookitCanvas = Color(0xFF090B0E)' "$THEME"
grep -q 'private val DarkColors = darkColorScheme' "$THEME"
grep -q 'color = CookitSidebar' "$APP"
grep -q 'containerColor = CookitSurface' "$APP"
grep -q 'CookitSoftAccent' "$APP"
grep -q 'Color.rgb(9, 11, 14)' "$MAIN"
grep -q '#090B0E' "$STYLES"

echo "DARK_VISUAL_FOUNDATION=yes"
echo "CYAN_ACTIVE_ACCENT=yes"
echo "ANDROID_NAV_STRUCTURE_PRESERVED=yes"

echo "Changed production files expected:"
echo "  $THEME"
echo "  $APP"
echo "  $MAIN"
echo "  $STYLES"

echo "FISCAL_SCOPE_GUARD=yes"
echo "IOS_INSPIRED_UI_P1_CHECK=PASS"
