# Sync 30 Numérique — État de la monétisation Google Play

Audit en lecture seule du **30 octobre 2026** dans le compte Play Console de Sync 30 Numérique.

## Résultats observés dans Play Console

- Compte développeur : **organisation**, Sync 30 Numérique.
- Application en production : **Rush Studio** (`com.chk.rushstudio`), dernière publication affichée : 17 octobre 2026.
- Profil marchand : page **Manage account > Payments profile** disponible.
- Widget **How you get paid** : Google affiche **Add a payment method to receive your earnings**.
- Montant disponible affiché : **0,00 €** ; seuil de versement : **1,00 €**.
- Bloc **Transactions** : aucune transaction.
- Page **Order management** : aucune commande (filtre courant).
- Google propose une inscription au tarif de service 15 % via création d'un groupe et conditions à accepter. **Non activée**.
- Produits et abonnements : tuiles **Get started** ; la liste détaillée n'a pas été confirmée.

## Blocage immédiat : configurer le compte bancaire marchand

Cette étape se fait exclusivement dans **Play Console**, pas dans l'application CHK.

1. Dans Play Console, choisir **Manage account > Payments profile**.
2. Descendre jusqu'à **How you get paid**.
3. Choisir **Add payment method**, puis renseigner les coordonnées du compte bancaire professionnel via la page sécurisée Google.
4. Suivre l'éventuelle vérification Google (virement test/document bancaire).
5. Vérifier l'apparition d'un moyen de paiement valide. Cette information ne doit être ni dupliquée dans GitHub, ni envoyée à ChatGPT, ni enregistrée dans l'APK.

**Opération non réalisée pendant l'audit** : l'utilisateur doit fournir ses coordonnées uniquement dans l'interface Google et approuver l'éventuelle validation bancaire. Ne jamais créer un compte marchand supplémentaire à l'aveugle : l'association peut être définitive.

## Paramètres à préparer ensuite

- Créer et définir un produit ou abonnement dans Play Console (SKU, tarif, pays, conditions).
- Installer Google Play Billing Library dans l'application cliente après validation du produit. Une APK signée/versionnée doit suivre le canal de test Google approprié.
- Connecter le backend CHK Paiements à la Google Play Developer API avec un compte de service (Android Publisher) approuvé pour l'application concernée.
- Déployer le vérificateur dans le **service Paiements indépendant**, jamais dans le MCP Universal Control 1.0.4.
- Accorder les licences uniquement après vérification du jeton et enregistrer l'identité client de manière authentifiée.
- Confirmer/acknowledger les achats (ou consommer pour les consommables) selon la documentation Google.
- Mettre en place les Real-time Developer Notifications (RTDN) et la vérification des remboursements/renouvellements, puis tester en environnement de licence Google Play.

## Développement engagé (branche séparée, pas en production)

- `modules/google-play/play-api.mjs` : JWT OAuth2 signé côté serveur et vérification des achats uniques / abonnements via Google Play Developer API.
- `modules/google-play/entitlements.mjs` : registre des droits, vérification injectée, audit, suspension distincte du paiement.
- `modules/google-play/*.test.mjs` : tests avec réponses Google simulées ; aucune opération financière.
- Le branchement au serveur Windows, la synchronisation Android, l'API de réception client authentifiée et le déploiement ne sont **pas encore réalisés**.
- Aucune clé Google, ni IBAN, ni donnée bancaire, ni jeton client réel ne doit figurer dans ce dépôt.

## Documentation officielle

- Profil marchand et versements : https://support.google.com/googleplay/android-developer/answer/7161426?hl=fr
- Configurer le compte bancaire : https://support.google.com/googleplay/android-developer/answer/7161440
- Intégration Play Billing : https://developer.android.com/google/play/billing/integrate
- Backend et vérification des achats : https://developer.android.com/google/play/billing/backend
- API produits : https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2/getproductpurchasev2
- API abonnements : https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get

**Invariant :** CHK Universal Control 1.0.4, ses ports, les routes Tailscale et son MCP sont préservés.
