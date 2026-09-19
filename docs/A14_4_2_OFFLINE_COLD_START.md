# A14.4.2 — Offline cold-start recovery

## Trigger found during Runtime PASS

On a real Samsung tablet, A14.4.1 passed local FDM/outbox tests while the app remained alive offline, but a full app removal from Recents followed by a relaunch with Wi-Fi/data disabled crashed repeatedly. Re-enabling connectivity allowed launch again.

## Fix

A successful online bootstrap now stores a last-known-good local snapshot containing only operational UI/bootstrap data:

- authenticated user identity and role;
- server-derived native policy;
- catalog categories/items;
- last loaded orders;
- tables;
- timestamp.

No Fiscal Agent credential, FDM secret, payment secret or cloud password is stored in this snapshot.

At cold start, the normal cloud bootstrap is attempted first. If it fails and a valid cached snapshot exists, Cookit enters authenticated offline mode with `online=false`, rebinds the permanent fiscal runtime identity to the cached restaurant/branch, restores the local draft against cached products, refreshes fiscal Room health, starts the embedded debug Mock when applicable, and keeps polling/sync in retry-safe mode for automatic recovery.

If no valid cached snapshot exists, Cookit stays unauthenticated and displays the network error instead of inventing tenant data or crashing.

## Scope

This fixes startup resilience and local fiscal availability. It does not turn the existing cloud payment endpoint into a fully offline payment engine. A payment/order action that still requires Cookit Cloud must remain blocked/fail safely while connectivity is unavailable.
