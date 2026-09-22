# Cookit Android A15.0E — finalized Cookit order → Module2 TRAINING

Version: **0.15.0.8** (`versionCode 43`)

## Goal

Move beyond fiscalizing the current cart. A15.0E creates an immutable **fiscal snapshot v2** from the real Cookit order immediately before payment, activates it only after Cookit confirms payment, and allows that paid snapshot to be sent manually to the Module2 simulator with `isTraining=true`.

The automatic fiscal agent remains intentionally stopped for Module2.

## Snapshot v2

`cookit.android.fiscal.sale.v2` now records:

- Cookit order ID and order type;
- runtime and terminal IDs;
- cashier ID/name;
- payment method, amount and currency;
- item product ID/name;
- department/category ID/name;
- quantity and immutable minor-unit amounts;
- VAT rate/label;
- booking/pos date-time;
- canonical SHA-256 hash.

The existing two-phase rule is preserved: PREPARED before the irreversible payment call, then activated only after successful settlement.

## Module2 TRAINING bridge

A new `trainingSaleFromFinalizedEvent()` path:

1. verifies the canonical snapshot SHA-256;
2. requires snapshot schema v2;
3. requires an activated (paid) event;
4. fails closed on missing VAT;
5. fails closed when lines do not reconcile exactly with the gross total (charges/discounts/rounding are deferred to a later phase);
6. supports CASH and CARD for this phase;
7. maps the immutable lines to GKS `SaleInput`;
8. sends `signSale(..., isTraining: true)` to Module2.

The Module2 simulator VAT/establishment/POS employee identity stays simulator-only. Cookit terminal/order/cashier evidence is captured in the local immutable snapshot.

## Duplicate guard

A successful manual TRAINING submission is persisted locally by `localEventId + snapshotHash`. The same paid snapshot cannot be sent again accidentally from the app.

This is a **training-only local guard**, not the final production idempotency mechanism. Production must reconcile the certified FDM/provider outcome.

## Test

1. Keep the Module2 automatic fiscal agent **STOPPED**.
2. Install A15.0E and test Module2 status first.
3. Create a **new** simple Cookit order after installing A15.0E. Use the VAT-configured items already validated (e.g. Mandi Viande / Haneed Viandes).
4. Pay the order normally in the POS with CASH first.
5. Open Fiscalité.
6. The new section `Paid order snapshot → Module2 TRAINING` should show:
   - the real Cookit order ID;
   - lines and total;
   - payment method;
   - snapshot `v2`;
   - cashier;
   - guard `Ready`.
7. Press `Send finalized order TRAINING` once.
8. Expect `TRAINING signSale accepted`, event `T`, FDM `FDM02000424`, incremented counters and VAT calculation.
9. The guard must switch to `Sent` and the button must no longer allow a second submission.

## Deliberately not included yet

- automatic Module2 transmission at settlement;
- production `isTraining=false`;
- production VAT/establishment/POS/employee provisioning;
- discounts, service charges, delivery fees, rounding lines;
- split/merged-bill fiscal decomposition;
- refunds/corrections.
