# CHK Paiements — liaison sans modifier le MCP universel

## Adresses et séparation
- Application : https://github.com/Chasmet/Chk-paiment-
- Sidecar paiements : branche feature/paiements-sync30 du dépôt Chasmet/CHK-serveur-Universal-Control, dossier payments/.
- MCP universel existant **protégé** : https://chk-universal-1.taild9e4de.ts.net/mcp

Ne jamais modifier cette URL, le routage Tailscale existant, les clés OAuth, les connexions MCP ou le serveur Windows CHK Universal Control 1.0.4. Le sidecar Paiements est un service indépendant, lié à 127.0.0.1:8791. Il nécessite une nouvelle URL HTTPS **distincte**, établie plus tard avec contrôle d'accès. Ne pas exposer le port 8791 directement à Internet.

## État technique
- APK debug compilé avec GitHub Actions ; disponible comme artefact du workflow de test, sans promesse de mise à jour signée.
- Écran de CHK Paiements : opérations classées par application, recettes confirmées, essais et validation manuelle des licences.
- WorkManager : surveillance en arrière-plan d'au moins 15 minutes, susceptible d'être décalée par Android. Notifications FCM restent à implémenter pour alertes quasi immédiates.
- Android Keystore chiffre le jeton appareil, jamais la clé Stripe.
- Service de paiement : prototype isolé et non déployé. Aucun paiement réel n'a été encaissé.

## Test du service séparé
1. Récupérer la branche feature/paiements-sync30 du dépôt serveur, sans la fusionner.
2. Node.js 22+ : lancer `node payments/sidecar.mjs` sur une machine de test, après avoir configuré PAYMENTS_DATA_FILE et PAYMENTS_DEVICES_FILE vers un dossier privé et sauvegardé.
3. Générer un code unique valable dix minutes avec `node payments/pair-code.mjs`, en utilisant exactement le même PAYMENTS_DEVICES_FILE. Le code ne doit jamais être publié.
4. Exposer via une nouvelle terminaison HTTPS sécurisée distincte du MCP universel, en interdisant l'accès direct au port local.
5. Dans CHK Paiements Android, saisir cette URL HTTPS dédiée (sans suffixe /mcp), puis le code d'appairage.
6. Tester le classement et les demandes de validation ; un paiement pending/failed ne donne jamais accès à une licence.

## MCP CHK Paiements dédié
Sur le sidecar de test uniquement : POST /mcp protégé par PAYMENTS_MCP_READ_TOKEN distinct de tous les jetons MCP universels.
Outils en lecture seule : payments_status, payments_activity, payments_summary, payments_review_queue.
L'ajout en tant que connecteur ChatGPT exigera un HTTPS dédié et une authentification OAuth adaptée, sans réutiliser le serveur universel.

## Mises à jour SkillMajAuto
- Workflow manuel : `.github/workflows/release-skillmajauto.yml`.
- Préparer les quatre secrets GitHub Actions : PAYMENTS_KEYSTORE_BASE64, PAYMENTS_KEYSTORE_PASSWORD, PAYMENTS_KEY_ALIAS, PAYMENTS_KEY_PASSWORD.
- Le propriétaire doit générer et conserver la clé de signature Android en sécurité ; ne jamais l'ajouter au dépôt.
- Spécifier version X.Y.Z et version_code Android toujours croissant.
- Le workflow compile et contrôle l'APK signé, puis publie vX.Y.Z et CHK-Paiements-X.Y.Z.apk. L'app vérifie certificat Android et numéro de version avant de proposer l'installation.
- L'APK debug ne remplace pas la première release signée : sa signature de test ne doit pas servir aux mises à jour stables.

## Avant une mise en production
Stripe/Google Play en mode test, conformité des canaux de distribution, webhooks authentifiés, identités clients et reçus, remboursement, authentification admin, taux d'appels, journaux, sauvegardes, tests Android sur appareil réel et confirmation explicite du propriétaire.
