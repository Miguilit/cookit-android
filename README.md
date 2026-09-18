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
