# CHK Paiements : connexions totalement indépendantes

**Règle non négociable : ne jamais remplacer ou arrêter un service existant.**

## Ce qui est conservé
- CHK Universal Control 1.0.4 : aucun changement dans ce projet.
- MCP universel : `https://chk-universal-1.taild9e4de.ts.net/mcp`, inchangé.
- Tailscale Funnel/Serve existants sur 443, 8443, 10000 : aucun reset, aucun changement de cible, aucune conversion du mode privé au mode public.
- Toutes les applications connectées à CHK Universal Control : inchangées.

## Connexion CHK Paiements Android ↔ PC (privée)
1. CHK Paiements.exe démarre ou reconnaît son moteur local, port d'écoute libre 8791/8792/8793/8795 etc.
2. Il lit d'abord `tailscale serve status --json` sans écrire dans la configuration.
3. Il reconnaît une éventuelle route déjà dirigée vers son moteur ; sinon il recherche un port inédit parmi **11001, 11002, 11003, 11004**.
4. Uniquement s'il a confirmé la disponibilité du port choisi, il tente `tailscale serve --bg --https=<port> http://127.0.0.1:<port_local>`. Il n'utilise JAMAIS `funnel`, `reset` ou les ports 443/8443/10000.
5. Il relit la configuration pour vérifier la destination. Si Tailscale refuse le port, le logiciel affiche une erreur sans modifier les routes existantes.
6. CHK Paiements Android recherche exclusivement son API `/health` sur ces nouveaux ports de `pc-salon` et `chk-universal-1`. Il conserve la vérification HTTPS TLS. Le code d'appairage reste valide dix minutes.

**Important** : Tailscale Serve est privé au réseau Tailscale ; il ne rend PAS un MCP joignable directement par ChatGPT. Un port HTTPS personnalisé peut être refusé par la version/configuration de Tailscale : dans ce cas conserver le service local et ne rien forcer.

## MCP dédié accessible à ChatGPT (sans exposition publique du port)
La solution recommandée est **Secure MCP Tunnel**, distinct du VPN Tailscale. Le client tunnel OpenAI sur le PC réalise une connexion **sortante** HTTPS vers OpenAI, puis transmet les appels au MCP Paiements local. Aucun port public ne doit être libéré, remplacé ou exposé.

Documentation officielle :
- https://developers.openai.com/api/docs/guides/secure-mcp-tunnels
- https://help.openai.com/en/articles/12584461-connecting-custom-mcp-servers-in-chatgpt

Étapes ultérieures nécessaires et non exécutées :
1. Créer le tunnel dans le compte OpenAI Platform, avec les permissions du compte/espace de travail compatibles.
2. Configurer `tunnel-client` sur la machine CHK Paiements ; conserver la clé de tunnel dans un magasin de secrets local, jamais sur GitHub ni dans l'APK.
3. Faire passer uniquement `/mcp` de CHK Paiements vers le tunnel, et NON la route universelle `/mcp`.
4. Terminer l'authentification du MCP de Paiements (outil de lecture seul en première phase) et valider les échanges avec le client tunnel.
5. Dans ChatGPT > Plugins > Ajouter un serveur MCP personnalisé > Tunnel, choisir l'identifiant du tunnel créé.
6. Tester réellement `initialize`, `tools/list` puis `payments_status` et une lecture sans données financières sensibles.

**État actuel** : le MCP Paiements local est un prototype sur un port privé ; le client Secure MCP Tunnel, le tunnel_id et l'authentification de connexion ChatGPT ne sont PAS encore déployés, configurés ou testés.

## Pour distribuer un MCP publiquement à d'autres utilisateurs
Secure MCP Tunnel n'est pas un serveur HTTPS public destiné à la distribution. Un MCP public demanderait **une nouvelle origine HTTPS indépendante** (par exemple un service cloud / tunnel dédié avec nom de domaine distinct) et une authentification OAuth appropriée. Ne jamais réutiliser ni modifier le Funnel public du serveur universel.

## Sécurité des paiements
- Aucune activation d'accès premium sur une tentative non réglée.
- Validation du paiement par Stripe ou Google Play côté serveur.
- MCP initialement en lecture seule : pas de capture de cartes, remboursements, exécution bancaire ni modification de droits depuis ChatGPT.
- Garder les clés de paiement hors du téléphone, des outils MCP, des logs et des dépôts.
