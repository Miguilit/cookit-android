# Backend Cookit utilisé

La cible Android ne crée pas de backend parallèle.

Base:
`/api/application-integration`

Sécurité:
- Sanctum
- auth:sanctum
- contrôle de fonctionnalité POS côté serveur

Principaux endpoints déjà disponibles côté Cookit:
- POST `/auth/login`
- GET `/pos/menus`
- GET/POST `/pos/orders`
- PUT `/pos/orders/{id}`
- POST `/pos/orders/{id}/pay`
- POST `/pos/orders/{id}/kot`
- GET `/pos/kots`
- GET `/pos/cash-register/registers`
- GET `/pos/cash-register/sessions/active`
- POST `/pos/cash-register/sessions/open`
- POST `/pos/cash-register/sessions/{id}/close`
- GET `/pos/cash-register/denominations`
- POST `/pos/cash-register/transactions/cash-in`
- POST `/pos/cash-register/transactions/cash-out`
- POST `/pos/cash-register/transactions/safe-drop`
- POST `/pos/notifications/register-token`

Le bootstrap natif fournit les capacités/modules, rôles et native_policy.
