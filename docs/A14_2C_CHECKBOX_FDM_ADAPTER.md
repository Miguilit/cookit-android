# Cookit Android A14.2C — Checkbox/Eutronix FDM adapter boundary

A14.2C prepares the Android POS to become the local fiscal runtime without inventing proprietary/certified device fields.

## Delivered boundary

- persisted non-secret Checkbox/Eutronix LAN configuration (host + TLS port + `/graphql`);
- HTTPS GraphQL transport client;
- permanent `FiscalProviderAdapter` contract;
- `FiscalFdmRuntime` orchestration seam;
- Cookit Fiscal Agent device-auth client for handshake, heartbeat, job claim, submitted and acknowledge endpoints;
- Settings diagnostics for FDM network configuration and adapter readiness.

## Fail-closed rule

The target NORMAL sale mutation is identified as `signSale`, but `CheckboxFiscalProviderAdapter.buildSaleOperation()` deliberately throws `FiscalProviderMappingUnavailable`.

No guessed GraphQL variables, VAT codes or `enrichedEventData` fields are sent to hardware. The exact certified Checkbox/Eutronix POS schema/test device must be validated before replacing this guard with the real mapping.

This means the software architecture is wired, but **FDM fiscalization remains intentionally non-operational** until the certified mapping is installed.

## Transport security

Android currently permits only HTTPS/TLS for the FDM GraphQL transport. Clear-text HTTP is deliberately rejected by the runtime and the application keeps `usesCleartextTraffic=false`.

Provider tokens/client certificates are not stored in ordinary SharedPreferences. The final device-specific security material must be provisioned through a dedicated secure installer/keystore path once the exact Checkbox security profile is confirmed.

## Cookit Fiscal Agent contract

`FiscalAgentClient` supports the already defined device-auth endpoints using:

- `X-Cookit-Device`;
- `X-Cookit-Device-Token`.

The Android app does not auto-handshake because it does not yet own provisioned fiscal-agent credentials. Credentials are caller-supplied and deliberately not persisted by that class.

## Hardware completion gate

Before enabling production fiscalization:

1. obtain/confirm the exact certified Checkbox/Eutronix POS GraphQL schema;
2. map Cookit's canonical immutable snapshot to the exact `signSale` input;
3. provision TLS/device authentication material;
4. register/bind the Cookit fiscal-agent device;
5. validate duplicate replay/idempotence, timeout-after-submit and restart recovery on the real test FDM;
6. validate corrections/refunds and Z reports;
7. only then enable the server fiscal profile for the test branch.
