# Cookit Android A14.2B — Durable fiscal outbox + cloud sync

A14.2B turns the A14.2A installation identity into a crash-safe, tenant-scoped fiscal event pipeline.

## Local evidence written before payment

Before a non-demo POS payment request, Android must persist a `fiscal_outbox` row in Room. The row contains:

- `local_event_id`;
- `idempotency_key`;
- permanent `runtime_id` + `terminal_id`;
- restaurant / branch binding;
- Cookit order id;
- NORMAL (`N`) sale classification;
- order type + payment method;
- deterministic canonical JSON snapshot;
- SHA-256 `snapshot_hash`;
- retry counters / timestamps / last error.

The first state is `prepared`. It is promoted to `pending` only after the payment call succeeds. If the payment response is lost, the row stays `prepared`; the recovery loop reads the remote Cookit order and activates it only when the server confirms a terminal settlement state. This avoids both losing a successful payment and fiscalizing a payment that never completed.

Split billing persists one immutable sale intent for the whole order and activates it only when the order is fully settled. Group/merged-table payment creates one sale intent per canonical Cookit order.

## Idempotence

Android uses one deterministic key per runtime/order sale:

```text
android:<runtime_id>:order:<order_id>:sale
```

The key is sent both as `Idempotency-Key` and `idempotency_key` to CookitFiscal. The local database also enforces unique indexes for `local_event_id` and `idempotency_key`.

## Tenant isolation

Outbox rows persist their restaurant and branch ids. Background reconciliation/sync only reads rows matching the currently bound runtime scope. Logging into another restaurant therefore cannot submit old pending rows under the new tenant token.

## Cloud sync

While the authenticated Android runtime is active, the sync loop:

1. reconciles crash/ambiguous `prepared` rows against the canonical remote order;
2. selects due `pending/retry/blocked_profile_off` rows;
3. calls `POST /api/v1/fiscal/orders/{order}/queue`;
4. marks accepted events `cloud_queued`;
5. applies exponential retry for network/server failures.

A disabled fiscal profile is treated as an expected dormant state and backs off without surfacing a POS checkout failure. Android never toggles the fiscal profile itself.

## Profile OFF invariant

This tranche **does not enable** `fiscal_profiles.enabled`. Local capture can run while the production fiscal profile remains OFF. Formal activation remains a deliberate server-side provisioning action after FDM/provider validation.


## Local fail-closed persistence guard

The server fiscal profile may remain OFF, but the Android payment path does not bypass a local SQLite write failure. A non-demo payment is allowed to continue only after the durable PREPARED event exists. This protects the future production activation path from silent fiscal-evidence gaps.

The current POS order/payment API still requires Cookit connectivity; this tranche provides durable fiscal recovery and replay, not a separate offline PSP/payment engine.
