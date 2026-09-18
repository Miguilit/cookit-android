# Cookit Android A8–A9 — parité CookitPad N9.1

## Objectif
Cette tranche rapproche le client Android natif du comportement et de la structure opérationnelle de CookitPad N9.1 sans créer un backend Android distinct.

## UI / branding
- rail de navigation tablette conservé à gauche (pattern POS landscape) mais réduit à 88dp ;
- respect des barres système Android en haut/bas ;
- Réglages ancré en bas du rail ;
- navigation filtrée selon la politique/role ;
- le `C` Cookit reste l'identité de l'application dans le rail ;
- le logo du restaurant est récupéré depuis `platform/config.restaurant.logo_url` et affiché dans le header ;
- le nom du restaurant et la branche restent les données métier du tenant ;
- aucun recoloring automatique avec des couleurs restaurant : cela doit rester une capacité white-label explicite.

## Images catalogue
Cookit expose historiquement l'image article par `item_photo_url`. Le parseur Android accepte maintenant :
- `item_photo_url` / `itemPhotoUrl` ;
- `item_photo` / `itemPhoto` ;
- `image_url` / `imageUrl` ;
- `item_image` / `itemImage` ;
- `photo_url`, `thumbnail_url`, et objets image/photo/media imbriqués.

Les URL relatives sont normalisées sur l'origine `https://cookit.be`.

## Parité opérationnelle
- Accueil : métriques de session à partir du flux de commandes ;
- Caisse : catalogue, panier, table/type de commande, création/KOT/paiement ;
- Commandes : feed multi-canaux ;
- Cuisine : vue KDS dédiée à partir des commandes actives ;
- Livraison : vue dédiée aux commandes delivery ;
- Fond de caisse : session/dénominations et ouverture tiroir ;
- Réglages : langues, synchronisation, compte/RBAC, imprimantes.

## Star Micronics natif
Provider StarIO10 ajouté en parallèle d'ESC/POS :
- StarXpand SDK Android (`com.starmicronics:stario10:1.13.0`) ;
- LAN, Bluetooth Classic, Bluetooth LE, USB ;
- test de connexion ;
- impulsion tiroir ;
- recherche/découverte Star par interface ;
- sélection et persistance de l'identifiant détecté ;
- permissions runtime LAN Android 17 et Bluetooth Android 12+.

## Parité restante après A9
La parité fonctionnelle majeure avec CookitPad est atteinte sur l'UI opérationnelle, le flux live, la caisse et les providers d'impression. Restent à durcir dans A10+ :
- offline-first persistant (Room/outbox/replay) ;
- device registration / MultiPOS ;
- mapping imprimantes cuisine/client par device ;
- impression réelle de tickets/KOT avec templates Android ;
- fermeture de caisse aveugle complète et transactions cash-in/out/safe-drop ;
- notifications push natives en complément du polling/realtime.
