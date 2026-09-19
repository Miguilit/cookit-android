# Cookit Android A14.2 — Local Fiscal Runtime FINAL

Version: `0.14.2` / versionCode `9`.

A14.2 is the consolidated Android fiscal-runtime tranche built on the A13.4 POS baseline and the existing CookitFiscal backend boundary.

## A — Durable identity

- Room / SQLite `cookit_local_runtime.db`;
- permanent installation `runtime_id`;
- permanent installation `terminal_id`;
- restaurant/branch metadata binding after Cookit login;
- identity survives process death, reboot and logout/login.

## B — Durable outbox / crash recovery / cloud sync

- Room schema v2 `fiscal_outbox`;
- canonical deterministic local sale snapshot;
- SHA-256 snapshot hash;
- deterministic local event + idempotency keys;
- PREPARED -> PENDING two-phase payment guard;
- reconciliation of ambiguous payment outcomes;
- tenant/branch-scoped authenticated runtime sync loop;
- retry/backoff;
- CookitFiscal queue endpoint + `Idempotency-Key`;
- disabled fiscal profile handled as dormant, never auto-enabled.

## C — Local Checkbox/Eutronix boundary

- persistent non-secret FDM LAN settings;
- HTTPS `/graphql` transport;
- provider adapter contract;
- Fiscal Agent device-auth transport contract;
- Settings diagnostics;
- exact Checkbox `signSale` mapping intentionally fail-closed until certified schema/device validation.

## Safety invariants

1. Android never changes `fiscal_profiles.enabled`.
2. Demo payments never create fiscal outbox events.
3. A live non-demo payment is blocked if its local fiscal evidence cannot be persisted first.
4. An ambiguous payment response remains `prepared` until server settlement reconciliation.
5. A logged-in tenant can sync only its own restaurant/branch outbox rows.
6. The FDM network client cannot execute a sale because the provider mapping deliberately refuses to build an unverified mutation.
7. Clear-text FDM transport is rejected.
8. Fiscal Agent secrets are not persisted by the generic Android runtime.

## What A14.2 does NOT claim

- no Checkbox/Eutronix hardware PASS;
- no certified `signSale` payload mapping;
- no Belgian legal certification claim;
- no production fiscal-profile activation;
- no automatic fiscal-agent provisioning.

Those are hardware/certification gates, not missing POS-domain architecture.

## Runtime note

The outbox is offline-durable: a queued/prepared fiscal event survives process death and network loss and resumes synchronization on the next authenticated runtime cycle. A14.2 does not turn the existing Cookit order/payment API itself into a fully offline payment engine; it makes the fiscal evidence/retry path durable around the existing POS workflow.
