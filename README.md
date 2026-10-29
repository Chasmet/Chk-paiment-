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
## CHK Paiements.exe — application Windows (sans CMD)

La version Windows possède sa propre fenêtre, une icône de notification, un bouton pour démarrer le moteur Paiements et un bouton pour générer/copier le code d'appairage Android. Elle intègre Node.js et le module Paiements : aucune commande manuelle ni installation préalable de Node n'est nécessaire.

- Source : `windows/CHKPayments.cs` ; compilation GitHub Actions `.github/workflows/windows-exe.yml`.
- EXE autonome publié en version d'essai (si les permissions GitHub Releases le permettent), ou téléchargeable comme artefact GitHub Actions du workflow Windows.
- Démarrage automatique Windows facultatif via HKCU Run (ne touche pas au démarrage de CHK Universal Control).
- Service local séparé : `127.0.0.1:8791`, données privées `%LOCALAPPDATA%/CHK-Paiements/data`.
- Tailscale : vérifie le nom DNS et l'occupation de `8443` avant de tenter d'ajouter uniquement la route HTTPS Paiements. En cas de différence d'identité, le logiciel n'altère aucune route existante.
- **Le MCP universel `https://chk-universal-1.taild9e4de.ts.net/mcp` reste strictement inchangé.**
- Un vrai test d'appairage Android avec le PC du propriétaire reste nécessaire. Aucun paiement bancaire réel n'est activé.

L'ancien installateur CMD fourni dans les releases de test précédentes est remplacé par le programme graphique Windows ; ne pas l'utiliser pour une nouvelle installation.
