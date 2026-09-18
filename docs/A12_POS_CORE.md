# Cookit Android A12 — POS Core consolidation

This wave intentionally consolidates the operational POS before notifications.

## Included
- `status` (billing/settlement) and `order_status` (operational progress) are separated.
- Orders use real server timestamps; the previous hard-coded `0 min` is removed.
- KDS consumes `/pos/kots`, not the orders list.
- KDS progression uses KOT statuses:
  - pending_confirmation
  - in_kitchen
  - food_ready
  - served
- KOT status changes are expected to propagate to canonical order progress through Cookit's backend observer.
- New POS action: **Envoyer en cuisine (KOT)** without payment.
- Unpaid orders can be tapped in Orders and reopened in the POS for later payment.
- Cash checkout accepts a tendered amount greater than the total and displays the change due.
- The API still settles only the actual amount due. Persisting tendered/change on the backend receipt is a later backend extension.

## Deliberately deferred to A13
Cookit already has server-side `DiningSessionService` and `SplitBillService`, but their complete table merge / split-bill capabilities are not yet exposed through the native RestApi facade. A13 will add that facade and Android UI instead of coupling the app to branch web routes.

Planned A13:
- equal split
- custom amount split
- item/quantity split
- merge tables in one dining session
- merge/reconcile orders for a single bill
- pay each split independently
