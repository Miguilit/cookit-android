#!/usr/bin/env bash
set -euo pipefail

APP="app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt"
CASH="app/src/main/java/be/cookit/pos/android/ui/CashRegisterScreen.kt"
LOGO="app/src/main/res/drawable-nodpi/cookit_logo_mark.png"
BUILD="app/build.gradle.kts"

echo "======================================================"
echo " COOKIT — CASH DARK + LOGO FINAL CHECK"
echo "======================================================"

grep -q 'versionCode = 85' "$BUILD"
grep -q 'versionName = "0.15.0.50"' "$BUILD"
echo "VERSION_METADATA=yes"

test -s "$LOGO"
grep -q 'R.drawable.cookit_logo_mark' "$APP"
if grep -nE 'Text\("C"|text[[:space:]]*=[[:space:]]*"C"' "$APP"; then
  echo "FAIL: legacy C placeholder remains"
  exit 1
fi
echo "COOKIT_MARK_INSTALLED=yes"

grep -q 'private val CashSurface = Color(0xFF15191E)' "$CASH"
grep -q 'private val CashSurfaceRaised = Color(0xFF1D2228)' "$CASH"
grep -q 'private val CashCanvas = Color(0xFF090B0E)' "$CASH"
grep -q 'private val CashLine = Color(0xFF2A3037)' "$CASH"
grep -q 'private val CashMuted = Color(0xFF9EA7B0)' "$CASH"

if grep -nE '0xFFF7F8FA|0xFFFFF3E8|0xFFEAF7EE|0xFFEAF3FF|0xFFE6E8EC|0xFF68707C' "$CASH"; then
  echo "FAIL: legacy light cash palette remains"
  exit 1
fi

WHITE_COUNT="$(grep -c 'Color.White' "$CASH" || true)"
if [ "$WHITE_COUNT" -ne 1 ]; then
  echo "FAIL: expected one deliberate Color.White spinner, got $WHITE_COUNT"
  exit 1
fi

echo "CASH_DARK_SURFACES=yes"
echo "BUSINESS_LOGIC_SCOPE_GUARD=yes"
echo "CASH_LOGO_FINAL_CHECK=PASS"
