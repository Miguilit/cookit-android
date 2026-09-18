# Cookit Android — A10 Workflow Stability

## Goals

A10 stabilizes the cashier flow before further feature expansion.

### Persistent draft order

The current cart, order type and selected table are owned by `CookitPosViewModel` instead of the POS composable. They therefore survive navigation to Orders, Kitchen, Delivery, Cash or Settings.

`DraftOrderStore` also persists the draft in Android SharedPreferences, so an unfinished basket can be restored after Activity/process recreation. A pending remote order id is persisted as well.

### Idempotent payment retry

The checkout flow is split into two stages:

1. Create the Cookit order/KOT only once.
2. Pay the existing order.

As soon as order creation succeeds, its id is stored locally. If payment fails, the basket remains visible and retry pays that same order instead of creating another order. Cart editing is locked while a remote order is awaiting payment to avoid local/remote divergence.

### Payment selector

`Encaisser` now opens a payment dialog instead of silently attempting a cash payment.

Supported in A10:

- Cash -> API payment method `cash`
- Card / external terminal -> API payment method `card`

The terminal option is intentionally a manual external-terminal confirmation workflow. The cashier completes the operation on the physical terminal and only then confirms it in Cookit. Hardware-driven Stripe Terminal/Viva/etc. integration is a separate provider layer and is not faked here.

### Visible failures

Checkout errors are now displayed inside the payment dialog and remain visible after navigation. The previous behavior could put the API exception only in global state while the POS screen rendered no error, which looked like an inert button.

## QA targets

1. Add two products, open Kitchen, return to POS: basket must remain unchanged.
2. Kill/reopen the app with a draft: basket must be restored after catalog bootstrap.
3. Tap Encaisser: payment dialog must always open for a valid basket/table.
4. Cash: confirm -> one order only; success clears basket.
5. Card/terminal: perform terminal payment externally, then confirm -> payment recorded as `card`.
6. Force a payment API failure after order creation: basket and pending order id remain; retry must not create a duplicate order.
