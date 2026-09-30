# Cookit Android 0.15.0.50 / code 85 — UC221 partial-unit line discount

The existing aggregate cart line remains unchanged. When a persisted line has quantity > 1, the operator can choose how many units the manual line discount applies to.

Example: 2 x 30 EUR, 50% on 1 unit -> commercial line total 45 EUR.

Android sends only operator intent:
`discount.type`, `discount.value`, `discount.quantity`.

Cookit Cloud remains the commercial/fiscal authority and creates any UC221 PRICE_CHANGE fiscal correction structure. Android does not create negative fiscal lines.

The P3B.2 refund UI contract from version 84 is preserved in this patch.
