# Cookit Android 0.15.0.39 — UC220 backend wiring

Base inspected from the supplied archive:

- Git branch: `feature/android-fiscal-backend-authority`
- Git HEAD: `0592471`
- Previous Android version: `0.15.0.38` / versionCode `73`
- This patch: `0.15.0.39` / versionCode `74`

## Purpose

Wire the native Android POS to the backend-authoritative UC220 line-discount business flow.

Android does **not** calculate or persist the authoritative discounted line amount and does **not** construct an FDM `priceChanges` payload. It sends the operator intent to Cookit Cloud, then reloads the order so `order_items.amount`, totals and subsequent fiscal projection remain backend-authoritative.

## Canonical backend contract used

Existing endpoint:

`PATCH pos/orders/{orderId}/commercial-adjustments`

Apply a line discount:

```json
{
  "line_discount": {
    "order_item_id": 123,
    "type": "percent",
    "value": 50.0
  }
}
```

Clear the line discount:

```json
{
  "line_discount": {
    "order_item_id": 123,
    "type": null,
    "value": 0.0
  }
}
```

There is deliberately **no alias probing** (`line_price_change`, `line_adjustment`, etc.). If the canonical backend contract is unavailable, the Android request fails visibly instead of guessing another semantic contract.

## Android flow

1. Prepare/persist the order in Cookit Cloud.
2. Open **Client & benefits / Client & avantages**.
3. Under the new line-discount section, select a backend-persisted order line.
4. Choose fixed amount or percentage and apply the discount.
5. Android sends only the line-discount intent.
6. Android reloads the authoritative order and commercial snapshot.
7. The displayed line amount and order total therefore come from Cookit Cloud.
8. Payment/fiscalization continues through the existing backend-generated immutable fiscal job/canonical request path.

## Files changed

- `app/build.gradle.kts`
- `app/src/main/java/be/cookit/pos/android/data/CookitHttpClient.kt`
- `app/src/main/java/be/cookit/pos/android/ui/CookitPosViewModel.kt`
- `app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt`
- `app/src/main/java/be/cookit/pos/android/ui/Localization.kt`

## UI

The new line-discount UI is available when the existing manual-discount capability is enabled. It supports:

- selection of a persisted order line (`remoteLineId` / backend `order_item_id`);
- fixed discount;
- percentage discount;
- removal of the line discount;
- FR / NL / EN / DE strings;
- authoritative refresh after every mutation.

## Fiscal boundary intentionally unchanged

This patch does not modify the fiscal outbox, Module2 client, local fiscal runner, provider adapter, canonical fiscal request, rounding logic or provider submission semantics.

UC220 fiscal `priceChanges` remains a backend concern. Android only exposes the commercial operation and then consumes the refreshed backend state.
