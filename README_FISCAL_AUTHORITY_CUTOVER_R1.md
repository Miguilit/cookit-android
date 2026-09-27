# Cookit Android 0.15.0.41 / code 76 — Fiscal Authority Cutover R1

Purpose: stop the obsolete Android SHADOW/manual-training/mock path from participating in normal fiscal processing and switch automatic paid-order queueing to the cloud-authority prepared FiscalTransaction pipeline.

Changed runtime behavior:
- Android queue request sends order/idempotency + runtime_id + device_id + terminal_id + POS version.
- Android no longer treats `profile_not_enabled` as a special retry state.
- Android no longer builds VAT/priceChanges/provider lines in the active queue intent. Local outbox schema becomes `cookit.android.fiscal.intent.v3`.
- Room migration 5 -> 6 quarantines every pre-cutover unsent local outbox row as `legacy_quarantined`; rows are preserved, never deleted.
- This intentionally quarantines the old #1040 `blocked_profile_off` event so it can never wake and fiscalize later.
- Mock provider is disabled in both debug and release builds; a stored legacy mock provider is normalized to Module2.
- Manual `Module2 TRAINING signSale`, `Cookit cart -> Module2 TRAINING`, and `Paid order snapshot -> Module2 TRAINING` controls are removed from the active Fiscal UI.
- Fiscal health no longer calls the SHADOW resolver.
- Fiscal Agent, Room/SQLite durability, provider outcome journal, idempotency, retry/manual-hold, canonical request verification, Module2 transport, and cloud acknowledgement remain unchanged.

R1 deliberately leaves some now-unreachable legacy classes/methods in source to minimize cutover risk. After one clean official UC220 pass, R2 physically deletes FiscalShadowResolution, manual Module2 builders, Module2TrainingReceiptStore, EmbeddedMockFdmServer and related dead code.
