# Cookit Android A14.5 — C3 Cloud Fiscal Agent → embedded Mock FDM loop

## Scope

A14.5 closes the first full test-only Fiscal Agent loop on Android:

1. Android authenticates to Cookit Cloud with the provisioned Fiscal Agent device credentials.
2. Android polls `/api/v1/fiscal/agent/jobs/next` with `runtime_id` + `runtime_type=android_pos`.
3. The claimed cloud transaction is checked against the bound restaurant/branch.
4. Its snapshot is canonicalized and SHA-256 checked before any provider call.
5. The selected provider adapter is invoked.
6. In debug with the Cookit embedded Mock FDM selected, the Mock returns a normalized receipt.
7. Android posts `/submitted` using the numeric transaction id expected by Laravel route-model binding.
8. Android posts `/acknowledge` with the normalized receipt.

The production Checkbox/Eutronix adapter remains fail-closed. C3 cannot bypass `FiscalProviderReadiness`.

## New runtime controls

The Fiscal Agent settings card now exposes:

- `Traiter 1 job` — deterministic one-shot processing.
- `Démarrer auto` — foreground polling loop while the POS process is alive.
- `Arrêter auto` — stops the loop.

The automatic loop polls every 5 seconds while idle, waits 1 second after a processed job, and sends a silent heartbeat approximately every 60 seconds.

## Safety gates

- Fiscal Agent credentials stay encrypted by Android Keystore.
- Job restaurant/branch must match the persisted runtime identity.
- Snapshot SHA-256 must match before the FDM adapter is called.
- Mock remains debug-only and loopback-only.
- Release builds do not run the embedded Mock FDM.
- Checkbox/Eutronix real mapping is still disabled until certified provider mapping/hardware validation exists.
- This patch does not enable the cloud fiscal profile.

## Version

- versionName: `0.14.5`
- versionCode: `17`
- persistent APK signing configuration is preserved.

## C3 test expectation

With a synthetic `test_only` queued transaction and the embedded Mock enabled, pressing `Traiter 1 job` should produce:

`queued → claimed → submitted → fiscalized`

and a `fiscal_receipts` row containing the Cookit Mock receipt. This validates transport/orchestration only; it is not Belgian certification and is not a real FDM receipt.
