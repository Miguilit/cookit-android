# Cookit Android A14.5.3 — durable provider outcome journal

## Why this patch exists
C3.3-D exposed a real gap in A14.5.2: after a cloud fiscal job was claimed, a provider result existed only in memory until `/submitted` and `/acknowledge` completed. A network loss in that window caused lease retries to call the FDM again. The Mock FDM is idempotent, but the Android agent itself did not durably remember that the provider had already accepted the event.

## A14.5.3 behavior
- Room database version 3 adds `fiscal_agent_outcomes`.
- A normalized provider receipt is persisted locally **before** any cloud `/submitted` or `/acknowledge` call.
- On a later lease retry, the runner validates transaction id/public id/idempotency key/snapshot hash and replays the stored provider result to Cookit Cloud.
- The FDM is not called again when a matching durable local result exists.
- Cloud receipt metadata includes `local_outcome_journal=true` and `replayed_from_local_journal=true|false`.
- Test-only metadata `cloud_submit_delay_ms` (0..15000 ms) creates a deterministic window after durable provider acceptance and before cloud submission. It is honored only for Mock + `test_only=true` jobs.
- Checkbox/Eutronix remains fail-closed. No certified provider mapping is added by this patch.

## Expected C3.3-D proof
First claim with network loss after local provider result:
`queued -> claimed`, provider result stored locally, cloud transition unavailable.

After lease expiry and network recovery:
`claimed (attempt 2) -> submitted -> fiscalized` using the local journal, with one cloud receipt and no second provider call.

Expected receipt metadata:
- `local_outcome_journal: true`
- `replayed_from_local_journal: true`
- `duplicate: false` is valid and expected because the provider was called only once.
