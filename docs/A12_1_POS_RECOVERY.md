# Cookit Android A12.1 — POS recovery hotfix

Fixes observed during live restaurant testing on 2026-09-18:

- A reopened order now reads the detailed API response at the root instead of descending into the raw `order` row.
- Canonical remote order total is displayed and used for payment.
- A server order can still be paid if item detail is temporarily unavailable but a valid canonical total exists.
- Public order code is shown to staff; internal database IDs are no longer exposed in the payment UI.
- Existing/reopened orders are separated from idempotency-pending newly created orders.
- Reopening an order no longer permanently activates the payment modal when staff tries to start another order.
- Added explicit **Rafraîchir** and **Nouvelle commande** controls.
- If a reopened order is paid from another Cookit surface, polling releases the local POS state automatically.
- Native create-order sends both `id` and `menu_item_id` for RestApi compatibility.
- Order list prefers explicit `settlement_status` / `operational_status` and nested canonical totals.

This patch is designed to be deployed together with the backend A12.1 Native POS API hotfix.
