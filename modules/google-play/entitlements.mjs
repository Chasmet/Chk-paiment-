// CHK Paiements — prototype isolé pour les droits Google Play.
// Ce module ne réalise aucun encaissement. Il n'accorde jamais d'accès sur la
// seule déclaration d'un client : la fonction verifyPurchase DOIT appeler
// Google Play Developer API depuis un serveur authentifié.
import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const appPattern = /^[a-z][a-z0-9_.-]{2,79}$/;
const pkgPattern = /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*){2,}$/;
const skuPattern = /^[a-zA-Z0-9_.-]{2,100}$/;
const accountPattern = /^[a-zA-Z0-9_.:-]{3,128}$/;
const idPattern = /^[a-zA-Z0-9_.:-]{3,128}$/;
const fail = code => { const e = new Error(code); e.code = code; return e; };
const hash = token => createHash("sha256").update(token).digest("hex");
const entitlementKey = (appId, accountId, sku) => JSON.stringify([appId, accountId, sku]);
const now = () => Date.now();

export class GooglePlayEntitlements {
  constructor(filePath) {
    if (!filePath) throw fail("data_path_required");
    this.path = filePath;
    this.state = { schema: 1, apps: {}, purchases: {}, overrides: {}, audit: [] };
    this.queue = Promise.resolve();
  }

  async init() {
    try {
      const loaded = JSON.parse(await readFile(this.path, "utf8"));
      if (loaded.schema !== 1 || !loaded.apps || !loaded.purchases ||
          !loaded.overrides || !Array.isArray(loaded.audit)) throw fail("corrupt_data");
      this.state = loaded;
    } catch (e) {
      if (e.code !== "ENOENT") throw e; // Ne jamais écraser des données corrompues.
    }
    return this;
  }

  async mutate(change) {
    const job = this.queue.then(async () => {
      const result = await change(this.state);
      await mkdir(dirname(this.path), { recursive: true, mode: 0o700 });
      const temp = this.path + "." + randomUUID() + ".tmp";
      await writeFile(temp, JSON.stringify(this.state), { mode: 0o600 });
      await rename(temp, this.path);
      return result;
    });
    this.queue = job.catch(() => {});
    return job;
  }

  audit(state, action, fields) {
    state.audit.push({ action, at: now(), ...fields });
    if (state.audit.length > 5000) state.audit.splice(0, state.audit.length - 5000);
  }

  // À exposer uniquement dans une API propriétaire authentifiée, jamais à un client.
  async registerApp({ appId, packageName, displayName, operatorId }) {
    if (!appPattern.test(appId || "") || !pkgPattern.test(packageName || "") ||
        typeof displayName !== "string" || !displayName.trim() ||
        !idPattern.test(operatorId || "")) throw fail("invalid_app");
    return this.mutate(state => {
      if (state.apps[appId] || Object.values(state.apps).some(a => a.packageName === packageName))
        throw fail("app_already_registered");
      const app = { appId, packageName, displayName: displayName.trim().slice(0, 100),
        provider: "google_play", newSalesEnabled: false, createdAt: now() };
      state.apps[appId] = app;
      this.audit(state, "app_registered", { appId, operatorId });
      return { ...app };
    });
  }

  // Couper les nouvelles ventes ne supprime JAMAIS les droits déjà achetés.
  async setNewSalesEnabled({ appId, enabled, operatorId }) {
    if (typeof enabled !== "boolean" || !idPattern.test(operatorId || ""))
      throw fail("invalid_admin_action");
    return this.mutate(state => {
      const app = state.apps[appId];
      if (!app) throw fail("unknown_app");
      app.newSalesEnabled = enabled;
      app.updatedAt = now();
      this.audit(state, "new_sales_changed", { appId, enabled, operatorId });
      return { appId, newSalesEnabled: enabled };
    });
  }

  listApps() {
    return Object.values(this.state.apps).map(a => ({ ...a }));
  }

  // verifyPurchase est un adaptateur de confiance côté PC, lié à l'API Google.
  // Il retourne { packageName, sku, purchaseState:"PURCHASED",
  //   productType:"one_time"|"subscription", expiresAt?: epochMillis }.
  async confirmPurchase({ appId, sku, accountId, purchaseToken, productType }, verifyPurchase) {
    if (!appPattern.test(appId || "") || !skuPattern.test(sku || "") ||
        !accountPattern.test(accountId || "") ||
        typeof purchaseToken !== "string" || purchaseToken.length < 16 ||
        !["one_time", "subscription"].includes(productType) ||
        typeof verifyPurchase !== "function") throw fail("invalid_purchase_input");
    const app = this.state.apps[appId];
    if (!app) throw fail("unknown_app");
    const verified = await verifyPurchase({
      packageName: app.packageName, sku, purchaseToken, productType
    }); // L'adaptateur DOIT vérifier avec Google, pas avec un JSON du téléphone.
    if (!verified || verified.packageName !== app.packageName || verified.sku !== sku ||
        verified.productType !== productType || verified.purchaseState !== "PURCHASED")
      throw fail("purchase_not_verified");
    const expiresAt = productType === "subscription" ? Number(verified.expiresAt) : null;
    if (productType === "subscription" && (!Number.isSafeInteger(expiresAt) || expiresAt <= now()))
      throw fail("subscription_not_active");
    const tokenHash = hash(purchaseToken); // Ne jamais enregistrer le token brut dans l'historique.
    return this.mutate(state => {
      if (!state.apps[appId] || state.apps[appId].packageName !== app.packageName)
        throw fail("unknown_app");
      const previous = state.purchases[tokenHash];
      if (previous && (previous.appId !== appId || previous.sku !== sku ||
                       previous.accountId !== accountId || previous.productType !== productType))
        throw fail("purchase_token_reused");
      if (previous?.status === "refunded") throw fail("purchase_revoked");
      state.purchases[tokenHash] = {
        tokenHash, appId, sku, accountId, productType, status: "paid",
        expiresAt, confirmedAt: now()
      };
      this.audit(state, "purchase_verified", { appId, sku, accountId, tokenRef: tokenHash.slice(0, 12) });
      return this.getEntitlement(appId, accountId, sku);
    });
  }

  // verifyRefund doit vérifier la révocation auprès de l'API Google (Voided Purchases,
  // purchases.productsv2 ou subscriptionsv2 selon le cas). Pas de champ client "refunded".
  async confirmRevocation({ purchaseToken }, verifyRefund) {
    if (typeof purchaseToken !== "string" || purchaseToken.length < 16 ||
        typeof verifyRefund !== "function") throw fail("invalid_revocation_input");
    const tokenHash = hash(purchaseToken);
    const current = this.state.purchases[tokenHash];
    if (!current) throw fail("unknown_purchase");
    const verified = await verifyRefund({ purchaseToken, appId: current.appId,
      sku: current.sku, productType: current.productType });
    if (verified !== true) throw fail("revocation_not_verified");
    return this.mutate(state => {
      const purchase = state.purchases[tokenHash];
      if (!purchase) throw fail("unknown_purchase");
      if (purchase.status !== "refunded") {
        purchase.status = "refunded";
        purchase.revokedAt = now();
        this.audit(state, "provider_revocation", {
          appId: purchase.appId, sku: purchase.sku, accountId: purchase.accountId,
          tokenRef: tokenHash.slice(0, 12)
        });
      }
      return this.getEntitlement(purchase.appId, purchase.accountId, purchase.sku);
    });
  }

  // Suspension d'accès pour un motif documenté : ne change PAS le statut financier.
  // Un remboursement éventuel reste à traiter auprès de Google.
  async setSuspended({ appId, accountId, sku, suspended, reason, operatorId }) {
    if (!appPattern.test(appId || "") || !accountPattern.test(accountId || "") ||
        !skuPattern.test(sku || "") || typeof suspended !== "boolean" ||
        typeof reason !== "string" || reason.trim().length < 8 ||
        !idPattern.test(operatorId || "")) throw fail("invalid_suspension");
    return this.mutate(state => {
      if (!state.apps[appId]) throw fail("unknown_app");
      const key = entitlementKey(appId, accountId, sku);
      state.overrides[key] = { suspended, reason: reason.trim().slice(0, 240),
        operatorId, updatedAt: now() };
      this.audit(state, "access_suspension_changed", {
        appId, sku, accountId, suspended, reason: reason.trim().slice(0, 240), operatorId
      });
      return this.getEntitlement(appId, accountId, sku);
    });
  }

  getEntitlement(appId, accountId, sku, timestamp = now()) {
    if (!appPattern.test(appId || "") || !accountPattern.test(accountId || "") ||
        !skuPattern.test(sku || "")) throw fail("invalid_entitlement_query");
    const purchases = Object.values(this.state.purchases).filter(p =>
      p.appId === appId && p.accountId === accountId && p.sku === sku);
    const valid = purchases.filter(p => p.status === "paid" &&
      (p.productType === "one_time" || (p.expiresAt && p.expiresAt > timestamp)));
    const suspended = this.state.overrides[entitlementKey(appId, accountId, sku)]?.suspended === true;
    return {
      appId, accountId, sku, access: valid.length > 0 && !suspended,
      reason: suspended ? "owner_suspension" : valid.length ? "verified_purchase" : "no_active_purchase",
      checkedAt: timestamp
    };
  }

  getSummary() {
    return this.listApps().map(app => {
      const purchases = Object.values(this.state.purchases).filter(p => p.appId === app.appId);
      return { appId: app.appId, displayName: app.displayName,
        newSalesEnabled: app.newSalesEnabled,
        verifiedPurchases: purchases.filter(p => p.status === "paid").length,
        revokedPurchases: purchases.filter(p => p.status === "refunded").length };
    });
  }
}
