# CHK Paiements — Sync 30 Numérique

Application Windows **CHK Paiements.exe** et application Android **CHK Paiements** pour administrer les licences et suivre les paiements des futures applications Sync 30 Numérique.

## Architecture protégée

**Aucune installation ou mise à jour de CHK Paiements ne doit modifier, remplacer ou arrêter :**
- CHK Universal Control **1.0.4**, toujours serveur principal ;
- le MCP universel `https://chk-universal-1.taild9e4de.ts.net/mcp` ;
- les routes et ports Tailscale existants `443`, `8443`, `10000` ;
- les connexions des autres applications.

Le moteur CHK Paiements est un sidecar indépendant sur un port local libre parmi `8791, 8792, 8793, 8795, 8796, 8797`.

## Connexion Android au PC

CHK Paiements.exe lit les routes Tailscale existantes avant toute action. Il réutilise uniquement une route lui appartenant déjà ou choisit un port privé totalement libre entre **11001 et 11004** avec `tailscale serve`, jamais `funnel` ou `reset`.

L'application Android détecte l'API Paiements par HTTPS sur les machines `pc-salon` et `chk-universal-1` aux ports privés dédiés. Un code unique de 10 minutes permet l'appairage ; la connexion est protégée par Tailscale et TLS. Si aucun port n'est disponible ou si Tailscale refuse la création, aucune route n'est remplacée.

La prise en charge de ports HTTPS privés autres que les valeurs standards est soumise aux capacités de la version Tailscale installée : vérifier réellement sur le PC, puis sur Android.

## MCP CHK Paiements pour ChatGPT

Le MCP Paiements possède un service local distinct. **Secure MCP Tunnel** est le chemin recommandé pour le connecter à ChatGPT sans libérer ni remplacer de port public existant. Il nécessite encore la création du tunnel dans OpenAI Platform, la configuration locale de `tunnel-client` et l'installation du connecteur ChatGPT. **Il n'est pas encore connecté.**

Le MCP reste en lecture seule en phase initiale. Les opérations bancaires réelles, OAuth, gestion des paiements, remboursements et licences nécessitent des contrôles supplémentaires avant la mise en production.

Voir [MCP_SANS_REMPLACEMENT.md](docs/MCP_SANS_REMPLACEMENT.md) pour la procédure et l'état réel.

## Distribution

- Version PC : GitHub Releases `pc-test-*`, EXE autonome WinForms, sans fichier CMD à lancer.
- Version Android : GitHub Releases `test-*`, APK de test.
- SkillMajAuto : détection des releases stables signées, publication stable suspendue tant que les secrets de signature Android permanents ne sont pas définis.
- Aucun paiement bancaire réel n'est activé.

## État de validation

La CI vérifie la compilation Android, la compilation et le démarrage Windows, le moteur embarqué et le repli de port local lorsque 8791 est occupé. Il reste à valider sur les machines réelles la connexion HTTPS privée, l'appairage du téléphone et le MCP via Secure MCP Tunnel.

Toutes les modifications en cours concernent le dépôt CHK Paiements ; **le MCP universel de production n'est pas modifié**.
