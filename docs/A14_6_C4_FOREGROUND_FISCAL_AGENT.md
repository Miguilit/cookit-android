# A14.6 / C4 — Fiscal Agent Android foreground service

## Goal
Move the automatic Fiscal Agent loop out of `CookitPosViewModel` into a device-level Android foreground service so polling, heartbeat, local FDM execution, durable Room outcome journaling and cloud acknowledgements continue while the POS UI is not visible.

## Security / fiscal boundaries
- Fiscal Agent device token remains encrypted by Android Keystore.
- Runtime identity and provider outcomes remain durable in Room/SQLite.
- Mock FDM remains debug-only.
- Production Checkbox/Eutronix mapping remains fail-closed.
- This is resilience/runtime engineering, not Belgian fiscal certification.

## Runtime behavior
- `Démarrer auto` starts `FiscalAgentForegroundService`.
- `Arrêter auto` stops the service and persists Auto=OFF.
- Service is `START_STICKY`: normal process reclamation may be recovered by Android.
- Reopening Cookit calls `resumeIfEnabled()` and resumes the service when Auto was persisted ON.
- A real Android **Force stop** still blocks automatic app/service restart until the user launches Cookit again; this is an Android platform rule.
- Logout/cashier UI lifecycle no longer owns the Fiscal Agent loop: the device-level agent can continue running after a cashier logs out.

## Foreground service type
The service uses `specialUse` because the fiscal device agent is a persistent restaurant-device function not equivalent to generic short-lived data synchronization. Store distribution may require declaring/justifying this FGS subtype in the applicable distribution console.

## UI state
The Fiscal Agent card now shows:
`C4 • auto=ON/OFF • service=RUNNING/STOPPED • traités=N ...`

## Debug Mock coexistence
`EmbeddedMockFdmServer` now uses process-global listener state so the UI and foreground service cannot both try to bind separate servers to `127.0.0.1:8787`.
