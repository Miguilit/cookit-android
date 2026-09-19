# Cookit Android 0.14.3 — consolidated fiscal runtime checkpoint

This is the consolidated Android source checkpoint built from the A13.4 POS baseline.
It includes all A14.2A/B/C work plus A14.3 readiness hardening.

## Included

- durable Room runtime identity (`runtime_id`, `terminal_id`);
- restaurant/branch binding;
- fiscal outbox with canonical JSON, SHA-256, local event id and idempotency key;
- PREPARED -> PENDING guard before live payment;
- crash/ambiguous-payment reconciliation;
- authenticated cloud fiscal queue sync with retry/backoff;
- FDM LAN settings and TLS-only GraphQL transport seam;
- fail-closed Checkbox/Eutronix adapter (`signSale` not implemented until certified schema validation);
- Cookit Fiscal Agent device-auth client;
- Fiscal Agent credentials encrypted with Android Keystore AES/GCM;
- explicit handshake and heartbeat controls;
- non-mutating TLS/GraphQL FDM probe with certificate fingerprint;
- canonical remote-order snapshot before live payment to protect reopened/partially-settled orders.

## Explicitly not enabled

- Android does not set `profile_enabled` / `fiscal_profiles.enabled`;
- no real `signSale` is sent;
- no trust-all TLS behavior exists;
- no Checkbox/Eutronix secret/client certificate is stored in generic settings;
- no automatic fiscal-agent provisioning occurs.

## External gate after this checkpoint

Further real-FDM implementation requires the exact certified Checkbox/Eutronix POS GraphQL schema/security profile plus a real dev box/emulator. At that point the existing adapter boundary can be filled without redesigning the POS, Room outbox, payment guard or cloud sync.
