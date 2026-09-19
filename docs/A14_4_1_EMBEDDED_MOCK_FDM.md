# Cookit Android A14.4.1 — Embedded Mock FDM

## Goal

A14.4.1 keeps the A14.4 Runtime PASS test contract but runs the Cookit Mock FDM **inside the Android debug APK**. This removes the need for a Windows/Linux host, Python, inbound firewall rules or a permissive Wi-Fi LAN.

This is a Cookit-owned test harness only. It is not an Eutronix/Checkbox emulator and provides no fiscal/legal proof.

## Runtime topology

```text
Cookit Android debug
  ├─ POS / local fiscal runtime
  ├─ Fiscal Agent seam
  └─ Embedded Cookit Mock FDM
        127.0.0.1:8787/graphql
```

The debug Mock server binds strictly to `127.0.0.1`. It does not bind `0.0.0.0` and is therefore not exposed to the restaurant LAN.

## Build boundary

- `BuildConfig.ENABLE_MOCK_FDM=true` only for debug.
- Debug source set provides the real `EmbeddedMockFdmServer`.
- Release source set provides a no-op implementation that never opens a socket.
- Production Checkbox/Eutronix remains HTTPS-only.
- `CheckboxFiscalProviderAdapter` still refuses to build the certified `signSale` until the exact provider schema/dev box is validated.
- Android never enables `profile_enabled`.

## Embedded endpoint

```text
POST http://127.0.0.1:8787/graphql
GET  http://127.0.0.1:8787/health
```

When Mock mode is selected, Android forces:

```text
provider = cookit_mock_fdm_a14_4
host     = 127.0.0.1
port     = 8787
path     = /graphql
TLS      = false
```

The host and port are not editable in Mock mode.

## Integrity and idempotency

For `mockSignSale`, the embedded server:

1. reads the exact immutable `snapshotJson`;
2. recomputes SHA-256;
3. rejects a mismatch;
4. keys the mock ledger by Cookit's `idempotencyKey`;
5. persists the accepted mock receipt in app-private SharedPreferences using synchronous `commit()` before simulating a lost response;
6. returns the same receipt with `duplicate=true` on retry;
7. rejects the same idempotency key if it is reused with a different snapshot hash.

Persisting the mock ledger means the lost-response/idempotency test remains valid even if Cookit is killed and relaunched between attempts.

## Fault scenarios

Android sends:

```text
X-Cookit-Mock-Scenario: <scenario>
```

Supported scenarios remain:

- `success`
- `lost_response`
- `graphql_error`
- `http_500`
- `malformed`
- `auth_required`
- `slow`
- `timeout`

`lost_response` is the important ambiguous-outcome case: the mock persists the sale first and then closes the connection without an HTTP response. Retrying the same event must return the same mock receipt with `duplicate=true`.

## Safety

Mock submissions never mark a local fiscal outbox event as submitted/fiscalized. The harness only validates transport/runtime behavior. A real fiscal state transition still requires the certified provider path.
