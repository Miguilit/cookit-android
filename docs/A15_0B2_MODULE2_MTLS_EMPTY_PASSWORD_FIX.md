# Cookit Android A15.0B2 — Module2 mTLS compatibility fix

Version: 0.15.0.3 (versionCode 38)

## Purpose

Fix Module2 simulator status probing on Android devices whose PKCS#12 provider rejects the
Module2 empty-password client PFX with `IllegalArgumentException: password empty`.

## Changes

- Keep the Fiscal Agent stopped while Module2 `signSale` remains intentionally gated.
- Replace runtime loading of the empty-password `client.pfx` with the equivalent Module2
  published PEM client certificate + PKCS#8 private key.
- Build an in-memory client KeyStore with a non-empty local-only password for Android mTLS.
- Continue trusting the published Module2 CA certificate.
- Probe the Module2 manual `status { ... }` GraphQL shape first, with compatibility fallbacks.
- Keep the transport-only GraphQL handshake fallback; no fiscal mutation is sent.

## Expected test

In Fiscality > Module2, press **Tester la connexion**.

Expected nominal result:
- FDM connected
- FDM ID populated (simulator: FDM02000424)
- Firmware populated (simulator observed: 2.2.0.5)
- Buffer populated
- Warnings/errors counts visible

If the exact status schema differs, transport should still remain connected and the UI should
report a transport-only success instead of crashing.
