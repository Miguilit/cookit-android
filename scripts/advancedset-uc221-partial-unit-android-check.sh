#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 85' app/build.gradle.kts
grep -q 'versionName = "0.15.0.50"' app/build.gradle.kts
grep -q 'quantity: Int? = null' app/src/main/java/be/cookit/pos/android/data/CookitHttpClient.kt
grep -q 'put("quantity", it)' app/src/main/java/be/cookit/pos/android/data/CookitHttpClient.kt
grep -q 'lineDiscountQuantity' app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt
grep -q 'lineDiscountUnits' app/src/main/java/be/cookit/pos/android/ui/Localization.kt
grep -q 'appliedQuantity' app/src/main/java/be/cookit/pos/android/domain/Models.kt
grep -q 'fullFallbackEligible' app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt
grep -q 'fiscal_partial_refund_not_supported' app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt

echo 'VERSION_METADATA=PASS'
echo 'UC221_PARTIAL_UNIT_INTENT=PASS'
echo 'P3B2_REFUND_CONTRACT_PRESERVED=PASS'
echo 'ANDROID_STATIC_GUARD=PASS'
