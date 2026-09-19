# Cookit Android A14.2A — Local Fiscal Runtime identity

A14.2A establishes the durable Android-side identity required before fiscal outbox/sync and the Checkbox/FDM adapter are activated.

## Scope delivered

- Room / SQLite database: `cookit_local_runtime.db`.
- Singleton local fiscal runtime row.
- Permanent `runtime_id` generated once per app installation.
- Permanent `terminal_id` generated once per app installation.
- Identity survives process death, app restart, logout/login and normal app upgrades.
- Authenticated Cookit restaurant/branch metadata is bound to the local runtime without changing either permanent identifier.
- Restaurant/branch numeric ids are read from the existing `platform/config` response when present.
- Settings exposes the local runtime identity for field diagnostics.

## Identity rules

`runtime_id` format:

```text
andrt_<32 lowercase hex characters>
```

`terminal_id` format:

```text
term_<32 lowercase hex characters>
```

The identifiers are random installation identifiers. They are not based on Android hardware ids, IMEI, MAC address, account email or operator identity.

The identifiers must never be cleared by POS logout. They are replaced only if Android removes the app data / the app is uninstalled, or by an explicit future controlled reprovisioning workflow.

## Local schema v1

Table: `fiscal_runtime_identity`

- `singleton_id` primary key, always `1`
- `runtime_id`
- `terminal_id`
- `created_at_epoch_ms`
- `updated_at_epoch_ms`
- `bound_restaurant_id`
- `bound_branch_id`
- `bound_restaurant_name`
- `bound_branch_name`

No destructive migration fallback is enabled. Future schema changes must use explicit Room migrations.

## Backend/FDM activation state

A14.2A deliberately does **not**:

- enable a Cookit fiscal profile;
- call Fiscal Agent handshake;
- call Fiscal Agent heartbeat;
- queue or transmit fiscal transactions;
- communicate with Checkbox/Eutronix;
- claim certification or production fiscal readiness.

`fiscal_profiles.enabled` therefore remains OFF during this tranche.

## Next tranche — A14.2B

A14.2B will add the durable local fiscal outbox and CookitFiscal synchronization:

- local event id;
- canonical immutable snapshot;
- SHA-256 snapshot hash;
- idempotency key;
- retry/backoff;
- handshake;
- heartbeat;
- offline → cloud sync;
- acknowledgement/reconciliation after ambiguous network outcomes.

The cloud contract already prepared by CookitFiscal includes the Fiscal Agent handshake/heartbeat boundary. A14.2B will consume that contract without changing A14.2A identity semantics.

## Verification checklist

1. Build `:app:assembleDebug`.
2. Install the A14.2A build without deleting app data.
3. Open Réglages → Runtime fiscal local.
4. Record `runtime_id` and `terminal_id`.
5. Force-stop and relaunch: both ids must be unchanged.
6. Logout and login again: both ids must be unchanged.
7. Restart the Android device: both ids must be unchanged.
8. Login to Cookit and verify the diagnostic scope shows the current restaurant/branch ids when `platform/config` provides them.
9. Confirm no Fiscal Agent handshake/heartbeat traffic occurs yet.
10. Confirm the server fiscal profile is still disabled.

## Version

- versionName: `0.14.2-a`
- versionCode: `8`
