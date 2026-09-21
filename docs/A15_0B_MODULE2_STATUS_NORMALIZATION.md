# Cookit Android A15.0B — Module2 status normalization

Target: `0.15.0.1` / versionCode `36`.

## Scope

- Keeps the successful A15.0A Module2 mTLS + Bearer + GraphQL transport unchanged.
- Replaces the transport-only status fallback behavior with a robust GKS 2.0 status probe.
- Tries the current `fdmSwVersion` field first.
- Supports the historic public-spec typo `fmdSwVersion` through a GraphQL alias.
- Falls back to a minimal standard status query if the firmware field differs.
- Normalizes FDM status into a provider-independent `FiscalProviderStatus` model.
- Exposes FDM id, firmware, FDM time, buffer usage, initialized state, warnings and errors in Fiscality.
- Keeps Module2 `signSale` strictly fail-closed.
- Does not use CloudBridge/BII.

## Expected simulator result

With simulator `FDM02000424` in the clean state:

- transport connected = true
- status available = true
- FDM = `FDM02000424`
- firmware = `2.2.0.5`
- buffer = `0%`
- initialized = according to simulator state
- warnings = `0`
- errors = `0`

The exact live values remain authoritative; the values above are the state observed before A15.0B.

## Safety

No sale/signature mutation is enabled by this patch. Fiscal Agent automatic processing remains gated for Module2 until the `signSale` mapping phase.
