# Cookit Android A6–A7 — Live POS, cash, media, language and ESC/POS

## Included
- Product image retrieval from Cookit API with absolute/relative URL normalization and emoji fallback.
- App language selector in Settings: FR / NL / EN / DE, persisted locally.
- `Accept-Language` forwarded to Cookit API and live data refreshed after language change.
- Responsive mobile cart bar for phones / landscape phones.
- Live table loading for dine-in selection.
- Live order creation via `POST /pos/orders`.
- Best-effort KOT creation via `POST /pos/orders/{id}/kot`.
- Cash payment via `POST /pos/orders/{id}/pay` with the documented `payments` payload.
- Cash-register discovery, denominations and active-session read.
- Cash-session opening wired to the existing Cookit endpoint.
- ESC/POS LAN printer settings (host/port), connectivity test and drawer pulse.

## Notes
- Star Android SDK is intentionally the next hardware tranche; ESC/POS LAN is already usable as the generic provider.
- The Android client keeps Cookit Cloud as source of truth and reuses the existing Application Integration API.
- If a restaurant returns no product image key, the product emoji remains visible.
