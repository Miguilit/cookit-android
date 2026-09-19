# A14.4.5 — Local DB health semantics + P5 signed upgrade target

## Why this patch exists
A14.4.4 field testing showed `SQLite indisponible` together with `StandaloneCoroutine was cancelled`, while runtime identity, outbox counts and Mock replay still worked. This was a UI/state false positive: coroutine cancellation and cloud sync errors were allowed to populate the local DB error field.

## Fix
- CancellationException is always rethrown and never converted into a Room/SQLite fault.
- Cloud/network/provider sync failures only update fiscal sync state (`retry`).
- A successful local outbox health read clears stale local DB errors.
- Actual Room/SQLite failures still populate `fiscalLocalDbError` and remain visible/fail-closed where required.

## P5 purpose
This version is also the first signed upgrade target after the persistent-signing baseline A14.4.4.
Install A14.4.5 directly over A14.4.4 without uninstalling or clearing app data. Verify that runtime_id, terminal_id, Room/outbox data and Mock idempotence survive the upgrade.
