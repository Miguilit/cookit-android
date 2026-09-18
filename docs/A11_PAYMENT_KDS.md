# Cookit Android A11 — Payment + KDS lifecycle

## Objectif

A11 corrige le premier cycle POS réellement transactionnel et limite volontairement Android aux capacités opérationnelles indispensables.

## Paiement

- Le client Android envoie d'abord le contrat RestApi déployé `{amount, method}`.
- En cas de validation 400/422, il sait encore retenter le contrat documenté `payments[]`.
- La création de commande, la création du KOT et le paiement sont désormais trois étapes explicites dans les erreurs UI.
- Un `pendingRemoteOrderId` existant est réutilisé pour empêcher la création d'une seconde commande au retry.
- `ensureKot()` vérifie qu'un KOT existe avant paiement et ne recrée pas volontairement un KOT déjà présent.

Un patch backend A11 accompagne l'APK. Il rend `/pos/orders/{id}/pay` compatible avec les deux contrats et, surtout, ne confond plus :

- `orders.status` = état commercial du règlement (`paid`, `payment_due`, ...)
- `orders.order_status` = cycle opérationnel (`placed`, `confirmed`, `preparing`, `food_ready`, `served`, ...)

Le paiement ne libère plus une table dine-in à lui seul. La table reste liée au cycle opérationnel jusqu'à la fin de service/annulation.

## KDS Android

Le KDS ne se contente plus d'afficher des cartes. Pour chaque commande active il montre une progression et permet les transitions utiles :

- Reçue -> Confirmée
- Confirmée -> En préparation
- En préparation -> Prête
- Prête -> Servie (sur place)
- Prête -> Livrée (à emporter)
- Livraison : le KDS s'arrête à Prête, puis le flux Livraison prend le relais.

Les actions écrivent via `/pos/orders/{id}/status` dans l'`order_status` canonique. Le backend Cookit garde ainsi KOT, commande web et suivi client QR alignés au lieu d'avoir un état Android isolé.

## Périmètre Android volontaire

Android reste un terminal opérationnel, pas une copie du cloud. Le noyau à conserver est :

1. POS / panier / tables / types de commande.
2. Encaissement et session de caisse.
3. Commandes actives multi-canaux.
4. KDS avec statuts client-visibles.
5. Livraison opérationnelle.
6. Impression / périphériques et réglages strictement nécessaires au poste.

Les fonctions de configuration profonde, reporting avancé, catalogue administratif, billing SaaS et gouvernance restent dans Cookit Cloud.
