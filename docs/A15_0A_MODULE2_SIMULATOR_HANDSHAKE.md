# Cookit Android A15.0A — Module2 simulator handshake

## Scope

A15.0A adds a read-only Module2/Pracsys integration path to the existing Cookit Fiscal Agent architecture.

This tranche deliberately does **not** enable `signSale`. Fiscal jobs remain fail-closed for Module2 until the SCE/GKS 2.0 field mapping is completed in A15.0B.

## Implemented

- New provider id: `module2_pracsys`.
- Module2 simulator preset: `https://fdm.module2.be:443/graphql/`.
- HTTPS/mTLS using the Module2 client PKCS#12 material and Module2 CA supplied through the developer portal.
- Per-FDM Bearer token encrypted at rest with Android Keystore AES/GCM.
- Read-only Module2 GraphQL status request.
- Safe `__typename` fallback handshake when the translated/manual status schema label cannot be confirmed yet.
- Fiscal Center provider selector: Checkbox / Module2 / Mock (Mock remains debug only).
- Module2 token never appears in logs, diagnostics or non-secure FDM preferences.
- `Process 1 job` and `Start auto` remain disabled while the selected provider's sale mapping is not ready.

## Vendor material validation performed before packaging

- `client.pfx` opens successfully with an empty PKCS#12 password, matching the Module2 sample usage.
- The certificate inside `client.pfx` matches the supplied `client.crt` SHA-256 fingerprint.
- The client certificate validates against the supplied `ca.crt`.
- Only `client.pfx` and `ca.crt` are packaged; the standalone plaintext `client.key` is not included.

## First simulator test

1. In the Module2 remote portal keep the simulator error/warning switches at `No`.
2. Open Cookit > Fiscality.
3. Select **Module2**.
4. Confirm host `fdm.module2.be`, port `443`, then Save.
5. Open the Module2 simulator settings page and copy its Bearer token.
6. Paste the token in Cookit and select **Save securely**. The text field is cleared after storage.
7. Select **Test connection**.

Expected success:

- `Connected`
- `HTTP 200`
- `GraphQL`
- `mTLS + Bearer + GraphQL OK`
- When the documented status field names match the live schema, Cookit also shows FDM id, firmware, buffer and error count.

If Cookit reports `handshake OK; status query needs schema confirmation`, transport/authentication/mTLS are already proven. The remaining task is only to confirm the exact live status query field names before A15.0B.

## Safety

Do not enable a Module2 fiscal mutation in A15.0A. This build is for connectivity/authentication/schema calibration only.

No Module2 Bearer token is included in the patch or source tree.
