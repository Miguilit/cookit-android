# A15.0F1 — Android CookitFiscal SHADOW → Module2 TRAINING

## Goal

Make CookitFiscal's multi-country shadow resolver authoritative for VAT selection during manual
Module2 TRAINING submission of a finalized paid Cookit order.

## Safety model

- The immutable Android snapshot remains authoritative for order id, lines, quantities, prices,
  payment, terminal/cashier context and commercial gross total.
- Android fetches `GET /api/v1/fiscal/orders/{order}/shadow-resolution` immediately before signSale.
- The backend response must be `mode=shadow`, `mutation=none`, `status=ok`.
- Order id, line count, menu item id, quantity, line gross and commercial/resolved gross must match
  the immutable local snapshot exactly.
- Module2 TRAINING is still Belgium-only in this phase, so the resolver country must be `BE`.
- VAT rate/label is derived from the resolver response, not from the static product tax metadata.
- Any mismatch fails closed before GraphQL signSale.
- Existing duplicate-send guard remains active.
- Automatic fiscal agent remains STOPPED / profile LIVE disabled.

## Why this matters

The same product may legitimately resolve to different VAT treatment based on country, regime and
order context. Example for the current Belgian horeca template:

- dine_in + FOOD_STANDARD -> BE_12 / 12% / Module2 B
- dine_in + BEVERAGE_NON_ALCOHOLIC -> BE_21 / 21% / Module2 A
- pickup + FOOD_STANDARD -> BE_6 / 6% / Module2 C
- pickup + BEVERAGE_NON_ALCOHOLIC -> BE_6 / 6% / Module2 C
- delivery follows the same current template as pickup for these classes.

## Production boundary

This is still TRAINING validation. Production fiscalization must persist the resolved treatment
inside the immutable fiscal transaction snapshot instead of re-resolving the order after payment.
Provider mappings must also replace the temporary rate->Module2-label compatibility mapping before
certification/live use.
