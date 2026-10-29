# CHK Paiements — Sync 30 Numérique

Application Android privée d’administration des paiements des futures applications Sync 30 Numérique.

## Architecture
- Application Android : com.chk.paiements ; tableau de bord, historique classé, validation propriétaire facultative et surveillance en arrière-plan.
- Moteur paiements isolé : https://github.com/Chasmet/CHK-serveur-Universal-Control/tree/feature/paiements-sync30/payments
- MCP Paiements dédié : à construire séparément du serveur MCP universel.
- Adresse EXISTANTE À NE PAS TOUCHER : https://chk-universal-1.taild9e4de.ts.net/mcp

## Sécurité et déploiement
- Aucun numéro de carte, mot de passe universel ou secret Stripe stocké dans l'application.
- Un paiement doit être confirmé par le prestataire côté serveur. Un essai ne vaut pas un paiement.
- Le serveur de paiements sera exposé sur une URL HTTPS distincte, à configurer plus tard sans toucher à /mcp.
- La surveillance périodique Android utilisera WorkManager (cadence minimale 15 min, avec report possible). FCM sera ajouté pour notifications rapides quand Firebase sera configuré.
- Aucun déploiement ni migration du serveur Windows CHK Universal Control 1.0.4 n'est effectué.

Voir docs/CONNEXION.md.