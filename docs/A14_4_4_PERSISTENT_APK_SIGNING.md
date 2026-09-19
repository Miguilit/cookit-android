# A14.4.4 — Persistent Android APK signing baseline

## Why this exists
GitHub-hosted runners must not be allowed to choose an ephemeral debug signing identity. Android updates require the installed package and the incoming APK to be signed by the same certificate.

A14.4.4 introduces one persistent Cookit signing identity supplied by GitHub repository secrets. The same signing configuration is attached to debug and release build types when the signing environment is available. The embedded Mock FDM remains debug-only through `ENABLE_MOCK_FDM`; signing does not enable the Mock in release.

## Required GitHub secrets
- `COOKIT_ANDROID_KEYSTORE_B64`
- `COOKIT_ANDROID_STORE_PASSWORD`
- `COOKIT_ANDROID_KEY_ALIAS`
- `COOKIT_ANDROID_KEY_PASSWORD`

The workflow fails closed if any required signing secret is missing. It reconstructs the keystore only inside the GitHub runner temporary directory, validates the alias, builds the APK, then compares the APK certificate SHA-256 fingerprint with the keystore certificate fingerprint using `apksigner`.

## Key ownership
The keystore is a production-significant private credential. Keep an encrypted offline backup outside GitHub. Losing it can make future direct APK updates impossible. Do not commit the keystore, password, or Base64 content to Git.

## P5 upgrade test plan
Because A14.4.3 and older GitHub debug APKs were signed by an ephemeral runner identity, A14.4.4 must be installed once as a clean baseline after uninstalling the old test APK. Then create local fiscal state. A14.4.5 will be built with the same persistent key and installed as an update over A14.4.4; runtime identity, Room/outbox and Mock idempotence must survive.
