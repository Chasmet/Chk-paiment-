# CHK Paiements — Apps Google Play, licences et versements (V1 de développement)

> Branche expérimentale : `feature/gestion-licences-google-play-v1` de `Chasmet/Chk-paiment-`.
> Aucun paiement réel n'est activé. Aucun fichier de CHK Universal Control 1.0.4 n'est modifié.

## Objectif pour Sync 30 Numérique

Une application Android distribuée sur Google Play utilise **Google Play Billing** pour vendre
ses fonctionnalités numériques, sauf exception réglementaire applicable et validée.
CHK Paiements Windows est l'autorité serveur pour les droits d'accès, les licences
et le suivi des transactions. CHK Paiements Android est le tableau de bord privé du propriétaire.
ChatGPT reçoit un MCP dédié et limité aux opérations autorisées.

### Cycle d'achat prévu

1. Créer l'application dans Play Console et les produits (ex. `premium`, `pro_monthly`).
2. Enregistrer côté CHK le `applicationId` Android et son `packageName` Play.
3. Dans l'application cliente, appeler Play Billing Library (version actuelle compatible)
   puis envoyer le `purchaseToken` **au serveur sécurisé**, avec l'identité cliente authentifiée.
4. Le backend CHK vérifie ce jeton auprès de **Google Play Developer API** :
   achat unique `purchases.productsv2.getproductpurchasev2`, abonnement
   `purchases.subscriptionsv2.get`, correspondance du package, du produit,
   du client et du statut. Une notification RTDN ne suffit jamais à elle seule.
5. Une fois `PURCHASED` confirmé, enregistrer l'opération de manière idempotente,
   accorder le droit et appliquer la procédure d'acknowledgement appropriée.
6. Les notifications Google Play RTDN (Pub/Sub sécurisé) et les vérifications périodiques
   actualisent renouvellements, expirations et remboursements.
7. Renvoyer à l'application cliente uniquement ses propres droits via une API authentifiée.

**Ne jamais** activer une licence sur une preuve du téléphone seule, sur un achat
`PENDING`, ni sur un clic "paiement validé" depuis Android ou ChatGPT.

## Interrupteurs de gestion

- `newSalesEnabled` : activer/désactiver les nouvelles ventes proposées dans
  le catalogue ou le parcours CHK. Ce champ ne modifie pas le catalogue distant Google
  sans intégration autorisée et ne retire pas les licences déjà payées.
- `setSuspended` : suspendre temporairement et auditer un accès en cas de motif
  documenté ; la suspension ne constitue **ni** un remboursement **ni** une révocation
  du paiement. Prévoir un processus de réclamation et de remboursement conforme.
- Après remboursement ou annulation confirmés par Google, révoquer le droit correspondant.

## Versement des recettes et RIB

**Google Play Billing** : Google encaisse et verse les fonds sur le compte bancaire
configuré dans le **profil de paiement marchand de Play Console**.
Connectez-vous à https://play.google.com/console/ → Paramètres → Monétisation →
Profil de paiement → Mode de paiement (libellés susceptibles de changer).
Le titulaire du compte ajoute/valide le compte dans l'interface sécurisée de Google.

**Stripe** : pour les cas autorisés hors facturation Play, le compte bancaire de versement
se configure dans le Dashboard Stripe : https://dashboard.stripe.com/.
Ne jamais saisir l'IBAN dans les sources GitHub, la configuration locale CHK,
le MCP, ChatGPT ni un formulaire Android personnalisé.

CHK Paiements peut afficher à terme **le fournisseur**, le **statut de configuration**
récupéré via une interface autorisée et un **lien** vers la page officielle, sans RIB brut.

## Applications et sécurité

### API cible à implémenter (PAS ENCORE DISPONIBLE)

| Méthode | Route | Authentification |
|---|---|---|
| GET | /api/v1/admin/apps | propriétaire appairé |
| POST | /api/v1/admin/apps | propriétaire + validation |
| POST | /api/v1/admin/apps/:id/sales-state | propriétaire + confirmation |
| GET | /api/v1/admin/licenses | propriétaire appairé |
| POST | /api/v1/admin/licenses/suspend | propriétaire + justification |
| POST | /api/v1/play/verify-purchase | compte utilisateur de l'application, service vérificateur |
| POST | /api/v1/play/rtdn | Google Cloud Pub/Sub authentifié puis relecture Google API |
| GET | /api/v1/client/entitlements | utilisateur authentifié, autorisé pour son propre compte |

La dernière route ne doit jamais accepter l'accountId d'un autre client :
l'identité est dérivée de son authentification serveur.

Les identifiants des comptes de service Google, clés API, secrets Stripe et
coordonnées bancaires ne doivent exister que dans le coffre de secrets du PC
ou de l'hébergeur sécurisé, jamais dans un APK ou GitHub.

### MCP ChatGPT distinct

Outils de lecture envisagés : `payments_apps`, `payments_sales_summary`,
`payments_license_status`, `payments_refunds`, `payments_payout_setup_status`.
Écritures : propositions explicites avec approbation propriétaire pour
les changements de catalogue ou suspensions ; aucun transfert bancaire autonome.

Pour connecter le MCP dans ChatGPT : **URL HTTPS dédiée**, protocole compatible,
identité et OAuth à finaliser. Aucun port 443/8443/10000 ne doit être repris ou écrasé.
Conserver la route privée Tailscale pour le téléphone.

## Travail de la branche

Le module `modules/google-play/entitlements.mjs` est un **registre de licences
isolé** avec stockage atomique, audit, anti-réutilisation des jetons, abonnement,
révocation confirmée et suspension. Il nécessite une fonction serveur `verifyPurchase`
ou `verifyRefund` réellement branchée à l'API Google avant usage en production.

Tests : `node --test modules/google-play/entitlements.test.mjs`.

**Ce que cette V1 ne fait pas encore** : interface Android Apps,
authentification client, appels réels Google Play Developer API, réception RTDN,
paiements/règlements réels, liaison au sidecar Windows, sortie APK/EXE et MCP public.
Cela évite de présenter un prototype comme une solution opérationnelle.

Sources officielles :
- https://support.google.com/googleplay/android-developer/answer/9858738?hl=fr
- https://developer.android.com/google/play/billing/backend
- https://developer.android.com/google/play/billing/integrate
- https://support.google.com/googleplay/android-developer/answer/13628312?hl=fr
