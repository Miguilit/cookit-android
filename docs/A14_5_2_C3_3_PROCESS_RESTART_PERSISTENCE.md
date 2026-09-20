# A14.5.2 — C3.3 process-restart persistence

## Goal
Persist Fiscal Agent operator/runtime state across Android process death and APK upgrades without
moving any secret out of Android Keystore.

## Persisted non-secret state
- desired automatic polling state (`auto_enabled`)
- number of successfully processed cloud jobs
- last processed cloud job id
- last receipt number

`FiscalAgentCredentialStore` remains the only store for the device token and keeps AES/GCM backed
by Android Keystore.

## Resume semantics
If automatic polling was ON before process death, Cookit restores it after the app is launched again
once all of the following are available:
- authenticated non-demo session
- role policy allows settings management
- Fiscal Agent credentials exist
- restaurant/branch runtime identity is bound
- selected FDM adapter is ready

An explicit **Arrêter auto** persists OFF. Clearing Fiscal Agent credentials also clears the durable
agent runtime state.

## Android force-stop limitation
Android's package force-stop state prevents the application, alarms and workers from relaunching the
package until the user launches Cookit again. A14.5.2 therefore restores polling immediately after
that user relaunch; it does not attempt to bypass Android force-stop semantics.

## C3.3-B expectation
For `lost_response_once`, the embedded debug Mock FDM already persists its idempotency receipt
registry in SharedPreferences. After force-stop + relaunch, the cloud lease may be reclaimed and the
same receipt must return with `duplicate=true`, while Cloud stores exactly one fiscal receipt.
