# Cookit Android — A3 à A5

## A3 — Auth / session
- connexion réelle sur `POST /api/application-integration/auth/login`
- token Sanctum persisté localement
- bootstrap `GET /platform/config`
- restauration de session au redémarrage
- logout
- état en ligne / hors ligne

## A4 — Catalogue / commandes réelles
- catégories `GET /pos/categories`
- articles `GET /pos/items`
- commandes `GET /pos/orders`
- parsing tolérant aux wrappers `data/categories/items/orders`
- fallback visuel vers les données démo uniquement si le catalogue réel est vide

## A5 — Inbound orders
- polling branche toutes les 2 secondes
- initial priming des ids
- déduplication par order id
- exclusion des commandes identifiées POS pour l'alerte inbound
- alerte sonore
- badge + bannière
- reprise automatique après coupure réseau

## Limites de cette vague
- création/paiement réel d'une commande: A6
- cash lifecycle réel: A6
- Star/ESC-POS Android: A7
- Room/offline outbox: A8
- native_policy serveur exact: sera branché dès validation de la route bootstrap N9.1; la présente vague applique un profil local prudent dérivé du rôle et reste protégée par l'autorisation serveur sur chaque endpoint.
