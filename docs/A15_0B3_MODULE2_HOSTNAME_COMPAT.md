# A15.0B3 — Module2 TLS hostname compatibility

## Why this patch exists

The Module2 simulator presents a server certificate whose subject is `CN=Server` and whose
Subject Alternative Name list is empty. Android therefore rejects `fdm.module2.be` with
`Hostname ... not verified` even though the mTLS certificate chain itself is accepted.

Module2's own published C# sample explicitly disables server hostname validation for this
endpoint. Cookit does **not** install a global trust-all verifier. Instead it keeps the Module2
CA trust manager and client-certificate mTLS unchanged, then allows the hostname compatibility
exception only for the exact host in the configured Module2 endpoint.

This also prepares the same connector for a physical Module2 FDM reached through a LAN IP or
mDNS name, where the same generic server certificate would otherwise fail Android hostname
verification.

## Safety

- mTLS client identity remains mandatory.
- Server certificate chain must still validate against the bundled Module2 CA.
- No global `HostnameVerifier { _, _ -> true }` is installed.
- The exception applies only to the exact configured Module2 host.
- Fiscal `signSale` remains gated; this phase is status/read-only.
