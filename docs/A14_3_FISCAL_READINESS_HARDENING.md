# Cookit Android A14.3 — Fiscal readiness hardening

Version: `0.14.3` / versionCode `10`.

A14.3 is a software-only hardening tranche on top of A14.2. It deliberately stops before the certified Checkbox/Eutronix `signSale` mapping and before any production fiscal-profile activation.

## A14.3A — secure Fiscal Agent provisioning

- Cookit Fiscal Agent `device_id` + `device_token` can be provisioned explicitly by an authorized settings user;
- the credential payload is encrypted with AES-256/GCM using a non-exportable Android Keystore key;
- application backup remains disabled, and a preference blob restored without its Keystore key fails closed and is discarded;
- the token is never re-displayed after saving;
- credentials authenticate Android to Cookit Cloud only; Checkbox/Eutronix secrets/certificates remain a separate certified-provider concern.

## A14.3B — explicit handshake / heartbeat

- authorized users can trigger the existing Fiscal Agent handshake and heartbeat contracts from Settings;
- runtime identity (`runtime_id` / `terminal_id`) is reused from the durable A14.2 Room identity;
- there is no automatic agent provisioning and no hidden background handshake before credentials exist;
- these calls do not enable `fiscal_profiles.enabled`.

## A14.3C — safe FDM connectivity diagnostics

- Settings exposes a TLS/GraphQL connectivity probe for the configured local FDM endpoint;
- the probe sends only `query CookitConnectivityProbe { __typename }`;
- it never calls `signSale`, `signOrder`, `signPreBill`, a report mutation, or any financial/social mutation;
- Android default TLS trust validation remains enabled; there is no trust-all certificate bypass;
- diagnostic output includes TLS reachability, HTTP status, GraphQL-envelope detection, latency and the server-certificate SHA-256 fingerprint when available.

## A14.3D — canonical payment guard hardening

A normal live checkout now reloads the canonical Cookit order before persisting its local fiscal evidence. This avoids freezing a partial `amountDue` as the sale gross total when an order has been reopened or partially settled. Split-bill and merged-table payment paths already use the canonical remote order before preparing the one-per-order fiscal event.

The local fiscal idempotency key remains one sale per Cookit order/runtime:

`android:{runtime_id}:order:{order_id}:sale`

## Fail-closed invariants retained

1. Android never changes `profile_enabled` / `fiscal_profiles.enabled`.
2. `CheckboxFiscalProviderAdapter.buildSaleOperation()` still throws until the exact certified schema is installed.
3. Clear-text FDM transport remains blocked.
4. No Checkbox/Eutronix provider secret or client certificate is stored by the generic settings store.
5. Fiscal Agent device credentials are accepted only through an explicit authorized action and are encrypted at rest.
6. A live payment still requires durable local fiscal evidence before the irreversible payment request.

## Remaining external gate

The next step cannot be completed truthfully from software assumptions alone. It requires the exact certified Checkbox/Eutronix POS GraphQL schema/security profile and a test FDM (or official emulator) to validate the concrete `signSale` input, response normalization, corrections/refunds, reports and timeout/replay behavior.
