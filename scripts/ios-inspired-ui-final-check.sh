#!/usr/bin/env bash
set -euo pipefail

APP="app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt"
GRADLE="app/build.gradle.kts"

echo "================================================="
echo " COOKIT ANDROID — iOS-INSPIRED UI FINAL CHECK"
echo "================================================="

test -f "$APP"
test -f "$GRADLE"

grep -q 'versionCode = 83' "$GRADLE"
grep -q 'versionName = "0.15.0.48"' "$GRADLE"
echo "VERSION_METADATA=yes"

grep -q 'Modifier.width(78.dp)' "$APP"
grep -q 'height(if (compact) 60.dp else 64.dp)' "$APP"
echo "COMPACT_NAV_CHROME=yes"

grep -q 'GridCells.Adaptive(minSize = 160.dp)' "$APP"
grep -q '\.height(206.dp)' "$APP"
grep -q 'selectedContainerColor = CookitSoftAccent' "$APP"
echo "DENSE_POS_CATALOG=yes"

grep -q 'modifier = Modifier.size(34.dp)' "$APP"
grep -q 'modifier = Modifier.fillMaxWidth().height(44.dp)' "$APP"
echo "COMPACT_CART_CONTROLS=yes"

grep -q 'Icon(icon, null, tint = CookitAccent)' "$APP"
echo "CYAN_ACTIVE_ACCENT=yes"

# The final pass intentionally does not touch fiscal sources/services.
if git diff --name-only HEAD -- 2>/dev/null | grep -E 'data/fiscal|service/Fiscal|CookitFiscal|Module2' >/dev/null; then
  echo "FAIL: fiscal/runtime source changed in this UI pass"
  exit 1
fi
echo "FISCAL_SCOPE_GUARD=yes"

# Standalone Kotlin parse gate: unresolved Android/Compose symbols are expected
# without the Android classpath; syntax/parser errors are not.
if command -v kotlinc >/dev/null 2>&1; then
  TMPLOG="$(mktemp)"
  kotlinc "$APP" -d "$(mktemp -u).jar" >"$TMPLOG" 2>&1 || true
  if grep -E 'expecting|unexpected tokens|syntax error' "$TMPLOG" >/dev/null; then
    cat "$TMPLOG"
    rm -f "$TMPLOG"
    echo "KOTLIN_PARSE=FAIL"
    exit 1
  fi
  rm -f "$TMPLOG"
  echo "KOTLIN_PARSE=yes"
else
  echo "KOTLIN_PARSE=skipped"
fi

echo "IOS_INSPIRED_UI_FINAL_CHECK=PASS"
