# Cookit Android A15.0F2 — delivery fee + deferred payment to Module2 TRAINING

Companion Android phase for CookitFiscal backend A15.0F2.

## Scope

- Reads `adjustments[]` and `financials[]` from the CookitFiscal SHADOW endpoint.
- Keeps the local immutable sale snapshot authoritative for order identity, item quantities/prices and gross total.
- Requires CookitFiscal SHADOW status `OK` and validates that:
  - item gross + supported adjustments == immutable gross total;
  - all fiscal item/adjustment resolutions are complete;
  - financial lines sum exactly to immutable gross total.
- Supports `delivery_fee` as the only adjustment in this training phase.
- Emits the delivery fee as a Module2 `SINGLE_PRODUCT` training line using the resolved VAT bucket.
- Emits backend-resolved financial lines, including `CUSTOMER_CREDIT` for a deferred delivery collection.
- Shows resolved fiscal lines and financials directly in the Android diagnostics UI.
- Remains TRAINING-only. The automatic fiscal agent stays unchanged/stopped.

## Expected validation case — order #220

- Mandi Viande 19.00 -> BE_6 / C / 6%
- Bottle of water 2.00 -> BE_6 / C / 6%
- Delivery fee 5.00 -> BE_6 / C / 6%
- transactionTotal 26.00
- financials: CASH 21.00 + CUSTOMER_CREDIT 5.00

Fail-closed remains in place for mixed-rate delivery-fee allocation or any unknown adjustment.
