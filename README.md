# Cookit Android POS — N0/N2

Première baseline Android native de Cookit POS, construite pour devenir le jumeau fonctionnel de CookitPad N9.1.

## Stack
- Kotlin 2.3.21
- Jetpack Compose / Material 3
- AGP 9.4.0
- API 37 / minSdk 26
- JDK 17
- Base API: `https://cookit.be/api/application-integration/`

## Ce qui est livré dans cette vague
- A0: projet Android natif + CI GitHub.
- A1: shell POS responsive téléphone/tablette.
- A1: navigation Dashboard / POS / Orders / KDS / Delivery / Cash / Settings.
- A1: design Cookit orange/vert.
- A1: écran de connexion / mode démo.
- A2: écran POS tablette avec catégories, produits, panier et types de commande.
- A2: commandes entrantes visibles sous forme de bannière/badge.
- A2: écran commandes multi-canaux.
- A2: ouverture de caisse par dénominations.
- A2: visualisation RBAC/native_policy dans les réglages.
- A2: contrat d'API centralisé pour préparer la connexion au backend Cookit.

## Parité CookitPad N9.1 visée

### A3 — Auth + bootstrap production
- Sanctum `/auth/login`
- chargement restaurant/branch/user
- `native_policy` serveur
- persistance sécurisée du token
- fermeture de session

### A4 — Menu / POS réel
- menus, catégories, items, variations/extras
- création/modification commande
- tables / dine-in / takeaway / delivery
- paiement, split payment, tips
- KOT

### A5 — Inbound orders temps réel
- feed branche
- interval/realtime
- déduplication
- alertes sonores
- banner + badge
- reconnect catch-up
- suppression des faux inbound créés localement

### A6 — Cash register complet
- register/session active
- opening count
- cash-in / cash-out / safe-drop
- drawer pulse
- blind closing count
- discrepancy

### A7 — Printing
- StarXpand Android SDK
- ESC/POS LAN / USB / Bluetooth
- mapping imprimante
- KOT / customer ticket / drawer-only pulse

### A8 — Offline-first
- Room/SQLite
- outbox/retry
- cache menu
- commandes locales
- synchronisation

### A9 — KDS / Delivery / waiter / dashboard / settings
- parité fonctionnelle avec CookitPad N9.1
- device registration / MultiPOS
- notifications push

## Mode démo
Au lancement, utilisez **Voir la démo POS**. Aucun compte Cookit n'est requis pour cette vague.

## Build local
```bash
gradle :app:assembleDebug
```

APK:
`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions
Le workflow `.github/workflows/android-build.yml` produit l'APK debug comme artefact.


## A8–A9 parity update
- compact tablet navigation rail
- restaurant logo/brand header from Cookit platform config
- canonical `item_photo_url` support
- KDS and Delivery operational surfaces
- StarIO10 native Android provider + discovery/test/drawer
- RBAC-aware navigation

See `docs/A8_A9_PARITY.md`.

## A10 — workflow stability

A10 moves the active POS draft into the ViewModel and persists it locally, introduces a two-stage create/pay checkout with duplicate protection, makes checkout errors visible, and adds a payment selector for cash and external card terminal payments. See `docs/A10_WORKFLOW_STABILITY.md`.

## A11 — paiement canonique + KDS client-visible

A11 stabilise le contrat de paiement RestApi (compatibilité `amount/method` et `payments[]`), sépare strictement l'état de paiement de l'état opérationnel de préparation et rend le KDS Android actionnable. Les changements de statut KDS utilisent l'`order_status` canonique Cookit afin que les commandes QR et les autres clients puissent suivre la même progression. Les erreurs d'encaissement sont désormais qualifiées par étape (création, envoi cuisine, paiement) au lieu d'afficher une clé de traduction brute. Voir `docs/A11_PAYMENT_KDS.md`.


## A12 — POS Core consolidation
- Real KOT feed for KDS
- Send order to kitchen without payment
- Reopen unpaid orders in POS
- Real relative timestamps
- Cash tendered amount + change calculation
- Separate settlement status from operational order status


## A12.1 — POS recovery hotfix
- Reopened orders use canonical detail items and total.
- Reopened order and idempotency-pending order states are separated.
- Refresh/new-order controls prevent permanent checkout lock.
- Explicit settlement/operational API axes supported.

## A13.4 — final POS split/merge consolidation

- dark readable Android system status bar;
- larger food photography and compact POS product cards;
- removed permanent success/change banners;
- native split-bill UI: equal, custom amount, items/quantities;
- per-split cash/card settlement;
- dining-session table merge/unmerge;
- grouped payment for merged tables;
- normal checkout is protected while split/merge billing is active.

## A14.2 — Android Local Fiscal Runtime FINAL

- Room / SQLite durable runtime identity (`runtime_id` + `terminal_id`)
- Room schema v2 fiscal outbox with deterministic canonical snapshot + SHA-256
- two-phase PREPARED/PENDING guard around live payment
- crash/ambiguous-response reconciliation from the canonical Cookit order
- tenant/branch-scoped background sync to CookitFiscal with `Idempotency-Key`
- retry/backoff and explicit profile-OFF dormant state
- Checkbox/Eutronix HTTPS `/graphql` transport seam
- Cookit Fiscal Agent handshake/heartbeat/job transport contract
- certified `signSale` mapping deliberately fail-closed until exact provider schema + hardware validation
- Android never enables the server fiscal profile

See `docs/A14_2_LOCAL_FISCAL_RUNTIME_FINAL.md`.

## A14.3 — Fiscal readiness hardening

- Fiscal Agent device credentials encrypted with Android Keystore AES/GCM
- explicit authorized handshake / heartbeat controls
- non-mutating TLS/GraphQL FDM connectivity probe + certificate SHA-256 diagnostic
- canonical remote-order snapshot used before live payment to avoid partial-settlement fiscal evidence
- `signSale` remains fail-closed and Android never enables the fiscal profile

See `docs/A14_3_FISCAL_READINESS_HARDENING.md`.

## A14.4 — Mock FDM / Runtime PASS campaign

A14.4 adds a **debug-only** Cookit Mock FDM harness so the Android fiscal runtime can be exercised before the Eutronix/Checkbox dev box arrives.

- version: `0.14.4` / versionCode `11`
- Android debug support for an external Cookit Mock FDM harness (standalone mock distributed separately)
- debug provider: `cookit_mock_fdm_a14_4`
- scenarios: success, lost response, GraphQL error, HTTP 500, malformed JSON, auth required, slow response, timeout
- validates snapshot SHA-256 and idempotency
- repeated identical requests return the same mock receipt with `duplicate=true`
- mock submissions never mutate the local fiscal outbox status
- release builds disable the mock provider and keep clear-text FDM traffic blocked
- the production Checkbox/Eutronix `signSale` mapping remains fail-closed until the certified schema/dev box is validated

See `docs/A14_4_MOCK_FDM_RUNTIME_PASS.md` and `TEST_A14_4_MOCK_FDM.txt`.

## A14.4.1 — Embedded Mock FDM on Android debug

A14.4.1 removes the PC/firewall dependency from the Runtime PASS campaign.

- version: `0.14.4.1` / versionCode `12`
- debug APK starts a Cookit-owned Mock FDM on `127.0.0.1:8787`
- no LAN listener: the embedded server binds only Android loopback
- existing A14.4 scenarios remain available: success, lost response, GraphQL error, HTTP 500, malformed JSON, auth required, slow response, timeout
- the mock ledger is persisted locally so idempotency survives an app/process restart
- reusing an idempotency key with a different snapshot hash is rejected
- release variant contains only a no-op placeholder and never starts a Mock server
- production Checkbox/Eutronix stays HTTPS-only and real `signSale` remains fail-closed

See `docs/A14_4_1_EMBEDDED_MOCK_FDM.md` and `TEST_A14_4_1_EMBEDDED_MOCK.txt`.
