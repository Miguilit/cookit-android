# A14.8 — C5.2 Fiscal Control Center

## Scope

A14.8 moves operational fiscal supervision out of the general Settings screen into a dedicated Fiscality screen for authorized manager/admin profiles. Settings keeps only a compact health summary and an entry point to the fiscal center.

## Local diagnostic journal

Room schema is upgraded from version 3 to 4 with `fiscal_agent_diagnostics`.

The journal stores non-secret operational metadata only:

- timestamp
- health state
- event type
- cloud job id and phase when applicable
- provider name
- runtime id
- connectivity state
- bounded message/error class
- retry count
- pending outcome count
- watchdog count

Device tokens and credential material are never written to this table or to exports.

The local journal is bounded to the latest 250 events and deduplicates identical events occurring inside a short window.

## Fiscality screen

The screen contains:

- system overview
- Cloud/FDM/agent/job state
- Fiscal Agent provisioning and lifecycle controls
- FDM connection configuration and connectivity probe
- recent local diagnostic history
- support-safe JSON export
- device resilience indicators
- advanced non-secret runtime identifiers

The dedicated tablet navigation entry is only shown to profiles that can manage settings and when fiscal functionality is relevant to the device. On smaller screens the fiscal center remains reachable from the Settings summary card.

## Diagnostic export

The export is generated in app cache and shared through Android FileProvider. It contains runtime and diagnostic metadata but no device token, password, access token, signing secret or Android Keystore material.

## Localization

New C5.2 user-facing text is provided through the existing FR/NL/EN/DE localization layer. Development-only explanatory text is not added to the production-facing fiscal center.

## Provider boundary

The real Checkbox/Eutronix provider remains fail-closed until its exact certified mapping is available. The embedded Mock remains debug-only.
