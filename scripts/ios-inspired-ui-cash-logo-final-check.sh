#!/usr/bin/env bash
set -euo pipefail

APP="app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt"
CASH="app/src/main/java/be/cookit/pos/android/ui/CashRegisterScreen.kt"
LOGO="app/src/main/res/drawable-nodpi/cookit_logo_mark.png"
BUILD="app/build.gradle.kts"

echo "======================================================"
echo " COOKIT — CASH DARK + LOGO + P3B.2 MERGE CHECK"
echo "======================================================"

grep -q 'versionCode = 84' "$BUILD"
grep -q 'versionName = "0.15.0.49"' "$BUILD"
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

# P3B.2 functional guards: do not regress the refund capability split.
grep -q 'selectedPayment?.fullRefundBlockers.orEmpty()' "$APP"
grep -q 'selectedPayment?.partialRefundBlockers.orEmpty()' "$APP"
grep -q 'selectedPayment?.canFullCustomerRefund == true' "$APP"
grep -q 'selectedPayment?.canPartialCustomerRefund == true' "$APP"
grep -q 'payment.fiscalFullRefundSupported' "$APP"
grep -q 'payment.loyaltyFullReversalSupported' "$APP"
grep -q '"fiscal_partial_refund_not_supported" -> rs.fiscalPartialUnsupported' "$APP"
grep -q '"loyalty_partial_refund_not_supported" -> rs.loyaltyPartialUnsupported' "$APP"
if grep -q 'else -> code' "$APP"; then
  echo "FAIL: raw backend blocker code can leak to the UI"
  exit 1
fi
if grep -q 'payment.blockers.distinct()' "$APP"; then
  echo "FAIL: generic blockers loop would reintroduce P3B.2 regression"
  exit 1
fi
echo "P3B2_REFUND_CONTRACT_PRESERVED=yes"
echo "BUSINESS_LOGIC_SCOPE_GUARD=yes"
echo "CASH_LOGO_FINAL_CHECK=PASS"
