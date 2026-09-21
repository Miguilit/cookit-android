# A15.0D — Cookit cart → Module2 TRAINING SaleInput

Version: 0.15.0.6 / versionCode 41

## Goal

Map the actual Cookit POS cart/order lines into the GKS 2.0 `SaleInput` sent to the Module2 simulator with `isTraining=true`.

This phase remains manual and TRAINING-only. The automatic Fiscal Agent stays stopped.

## Mapping

Cookit now maps:
- real menu item id/name;
- real quantity and unit price;
- Cookit category id/name to GKS department id/name;
- VAT metadata from Cookit API payloads (`vat_rate`, `vat_percentage`, `tax_rate`, nested `vat`/`tax`, or an explicit VAT label);
- CASH to GKS `CASH`;
- Cookit generic card terminal to GKS `CARD_UNKNOWN` with `MANUAL` input method;
- the actual Cookit cart total.

The standard Belgian VAT mapping is fail-closed:
- A = 21%
- B = 12%
- C = 6%
- D = 0%
- X = outside VAT scope (only when explicitly supplied by the backend)

Cookit never infers a VAT code from product/category names. If VAT metadata is missing, the TRAINING send button is disabled and the missing products are shown.

## Still simulator-only

The simulator identity values (`vatNo`, `estNo`, `posId`, employee id) remain the Module2 training values. Production provisioning is intentionally not enabled in this phase.

## Test

1. Keep Module2 selected and the Fiscal Agent automatic processing STOPPED.
2. Test Module2 status first; it must be green.
3. Go to POS and add one or more real Cookit products to the current cart, then return to Fiscalité.
4. In `Cookit cart → Module2 TRAINING`, verify line count, total, and `VAT mapping = Ready`.
5. Send once with CASH.
6. Repeat with CARD if desired.
7. Expect `Event = T`, `Operation = SALE`, incremented counters, and VAT calculation returned by Module2.

If `VAT mapping` reports missing items, do not bypass it. That means the Cookit API response used by Android does not yet expose the fiscal VAT metadata required for certification.
