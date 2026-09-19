# Cookit Android A14.4 — Mock FDM / Runtime PASS

## Purpose

A14.4 is a pre-hardware validation tranche. It does **not** emulate or claim to reproduce the certified Eutronix/Checkbox protocol. It provides a Cookit-owned GraphQL test contract used to exercise the local Android fiscal runtime before the real dev box is available.

## Safety boundary

Production behavior remains unchanged:

- Checkbox/Eutronix stays HTTPS-only.
- `CheckboxFiscalProviderAdapter` still refuses to build a real `signSale` operation.
- `profile_enabled` is not changed by Android.
- Mock FDM is compiled as enabled only in the debug build type.
- Release builds set `BuildConfig.ENABLE_MOCK_FDM=false`.
- Debug clear-text is accepted only when the selected provider is `cookit_mock_fdm_a14_4` and the host is a loopback/private/link-local literal IP.
- A mock response never marks the local outbox event as fiscalized/submitted.

## Mock contract

Endpoint:

```text
POST http://<LAN-IP>:8787/graphql
```

Android operation:

```text
CookitMockSignSale
```

Input carries the local event identity, runtime/terminal IDs, order ID, immutable snapshot JSON and `snapshot_hash`.

The mock recomputes SHA-256 over the exact snapshot string. A mismatch fails with `SNAPSHOT_HASH_MISMATCH`.

Idempotency is keyed by `idempotencyKey`. Retrying the same key/hash returns the exact same mock receipt and `duplicate=true`.

## Fault scenarios

Header used by Android:

```text
X-Cookit-Mock-Scenario: <scenario>
```

Supported values:

- `success`: normal deterministic result.
- `lost_response`: commits the mock sale, then closes the TCP connection without a response. A retry returns `duplicate=true`.
- `graphql_error`: HTTP 200 + GraphQL `errors` envelope.
- `http_500`: HTTP 500.
- `malformed`: HTTP 200 with invalid JSON.
- `auth_required`: HTTP 401.
- `slow`: waits 3 seconds before returning success.
- `timeout`: waits 25 seconds; Android read timeout is 20 seconds.

## Standalone Mock server

The standalone Mock FDM is **not bundled in this Android patch**. This repository contains only the Android debug support/client used to talk to the test harness.

For Windows environments without Python/admin rights, use the separately distributed package:

```text
CookitMockFDM_PowerShell_NoAdmin_A14_4.zip
```

Start `START_MOCK_FDM.cmd` on a computer connected to the same LAN/Wi-Fi as the Android terminal. The default endpoint is:

```text
http://<LAN-IP>:8787/graphql
```

Do not expose port 8787 to the public Internet.

## Android campaign

1. Run the Mock FDM on a computer connected to the same LAN/Wi-Fi as the Android terminal.
2. Install the debug APK produced by GitHub Actions.
3. Log in with a branch account that can manage settings.
4. Complete one paid test order so the local fiscal outbox contains a real event.
5. Open **Réglages → FDM**.
6. Enable **Mode Mock A14.4 (debug uniquement)**.
7. Enter the computer LAN IP and port `8787`, save, then run **Tester GraphQL**.
8. Execute each fault scenario using **Tester le dernier événement fiscal**.
9. For idempotency, run `success` twice: the second result must show `duplicate=true` and the same receipt number.
10. For ambiguous outcome recovery, run `lost_response`, then `success`: the retry must return `duplicate=true`.

## Exit criteria

A14.4 Runtime PASS is reached when:

- runtime/terminal IDs survive app restart;
- outbox evidence survives process/network failure;
- local snapshot hash is stable and accepted by the mock;
- success returns a deterministic mock receipt;
- duplicate retry is idempotent;
- lost response does not create a second mock sale;
- GraphQL/HTTP/malformed/timeout failures surface as controlled errors;
- switching back to Checkbox restores HTTPS-only fail-closed behavior;
- release build does not expose Mock FDM.

The next tranche after PASS is A15: real Eutronix/Checkbox schema, credentials/certificates and dev-box validation.
