# A14.7 — C5.1 Fiscal Agent Health + Watchdog

C5.1 durcit le runtime autonome validé en C4 sans modifier le contrat fiscal Cloud/FDM.

Le service conserve désormais un ledger de santé non secret dans SharedPreferences: état de santé, démarrage service, dernier tick de boucle, heartbeat, tentative/poll réussi, dernier succès/erreur, erreurs consécutives, relances watchdog, état du wake-lock et nombre d'outcomes FDM encore non ACK Cloud.

Le watchdog est volontairement fail-safe. Si un stall est détecté pendant une opération fiscale potentiellement en vol (`serviceBusy=true`), il ne démarre jamais une deuxième boucle; il marque le runtime `DEGRADED` et attend la sortie de l'appel borné. Une relance automatique n'est permise que pour une boucle idle/non-busy, afin de ne pas introduire de risque de double appel FDM.

La notification Android expose les états `CONNECTED`, `OFFLINE`, `DEGRADED`, `FDM_ERROR` ou `CONFIG_ERROR`. L'écran Réglages affiche les âges heartbeat/poll/loop, pending outcomes, watchdog restarts et wake-lock.

Le Mock reste limité aux builds debug et le mapping Checkbox/Eutronix réel reste fail-closed.
