# Cookit Android 0.14.9 — C5.3 retry policy

C5.3 bounds failure recovery around the durable provider-outcome journal introduced earlier.

## Invariants

1. A persisted provider outcome is never sent to the FDM again. Cloud synchronization is retried from the local journal.
2. Explicit provider rejection is treated as terminal rather than as an endless lease retry.
3. Ambiguous provider failures are retried with the same idempotency key and are capped at three Cloud claims.
4. Integrity, provider mapping and exhausted ambiguous failures enter `MANUAL_HOLD`.
5. `MANUAL_HOLD` stops new fiscal job claims while heartbeat/health monitoring remains alive.
6. An administrator must explicitly resume processing from the Fiscality control center.

## Dispositions

- `TERMINAL_FAILURE`: explicit rejection that is safe to mark failed.
- `RETRY_PROVIDER`: ambiguous FDM outcome; retry after the Cloud lease is safely reusable.
- `RETRY_CLOUD_SYNC`: provider outcome exists locally; retry only `/submitted` and `/acknowledge`.
- `MANUAL_HOLD`: unsafe or exhausted state requiring operator review.

## Backoff

Provider retry delays are 15s, 30s and 60s, but a provider retry is never scheduled before the current Cloud claim lease has expired plus a small grace period. Cloud synchronization retries use 30s intervals and do not require another provider call.

## Diagnostics

The runtime state and diagnostic export now include retry disposition, retry timestamp, retry attempt, terminal failure count, last terminal job and manual-hold state. The Fiscality control center exposes these values and an admin-only resume action.

This tranche validates runtime resilience with the debug Mock. It is not a certification statement and does not implement or infer the proprietary Eutronix/Checkbox certified transaction mapping.
