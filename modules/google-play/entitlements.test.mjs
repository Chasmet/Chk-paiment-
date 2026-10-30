import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { GooglePlayEntitlements } from "./entitlements.mjs";

async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), "chk-play-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const store = await new GooglePlayEntitlements(join(dir, "private", "licences.json")).init();
  await store.registerApp({
    appId: "app.demo", packageName: "com.sync30.demo",
    displayName: "Application démonstration", operatorId: "admin_123"
  });
  return store;
}
const args = {
  appId: "app.demo", accountId: "client_123", sku: "premium",
  productType: "one_time", purchaseToken: "google-play-purchase-token-unique-000001"
};
const verified = async req => ({
  packageName: req.packageName, sku: req.sku,
  productType: req.productType, purchaseState: "PURCHASED"
});

test("aucun accès sans achat vérifié", async t => {
  const store = await fixture(t);
  assert.equal(store.getEntitlement("app.demo", "client_123", "premium").access, false);
  await assert.rejects(store.confirmPurchase(args, async req => ({
    ...await verified(req), purchaseState: "PENDING"
  })), /purchase_not_verified/);
  assert.equal(store.getEntitlement("app.demo", "client_123", "premium").access, false);
});

test("achat validé par le fournisseur => accès actif et sauvegarde", async t => {
  const store = await fixture(t);
  assert.equal((await store.confirmPurchase(args, verified)).access, true);
  assert.equal(store.getSummary()[0].verifiedPurchases, 1);
  const second = await new GooglePlayEntitlements(store.path).init();
  assert.equal(second.getEntitlement("app.demo", "client_123", "premium").access, true);
  const raw = (await import("node:fs/promises")).readFile;
  assert.equal((await raw(store.path, "utf8")).includes(args.purchaseToken), false);
});

test("les jetons d'achat ne se réutilisent pas sur d'autres comptes", async t => {
  const store = await fixture(t);
  await store.confirmPurchase(args, verified);
  await assert.rejects(store.confirmPurchase({ ...args, accountId: "client_456" }, verified),
    /purchase_token_reused/);
});

test("un abonnement expiré ne doit jamais être activé", async t => {
  const store = await fixture(t);
  const subscription = {
    ...args, sku: "pro_monthly", productType: "subscription",
    purchaseToken: "google-play-subscription-token-unique-333"
  };
  await assert.rejects(store.confirmPurchase(subscription, async req => ({
    ...await verified(req), expiresAt: Date.now() - 1000
  })), /subscription_not_active/);
  await store.confirmPurchase(subscription, async req => ({
    ...await verified(req), expiresAt: Date.now() + 200000
  }));
  assert.equal(store.getEntitlement("app.demo", "client_123", "pro_monthly").access, true);
  assert.equal(store.getEntitlement("app.demo", "client_123", "pro_monthly", Date.now() + 300000).access, false);
});

test("désactiver les nouvelles ventes conserve les droits déjà payés", async t => {
  const store = await fixture(t);
  await store.confirmPurchase(args, verified);
  await store.setNewSalesEnabled({ appId: "app.demo", enabled: false, operatorId: "admin_123" });
  assert.equal(store.listApps()[0].newSalesEnabled, false);
  assert.equal(store.getEntitlement("app.demo", "client_123", "premium").access, true);
});

test("un remboursement non vérifié ne révoque rien", async t => {
  const store = await fixture(t);
  await store.confirmPurchase(args, verified);
  await assert.rejects(store.confirmRevocation({ purchaseToken: args.purchaseToken }, async () => false),
    /revocation_not_verified/);
  assert.equal(store.getEntitlement("app.demo", "client_123", "premium").access, true);
  assert.equal((await store.confirmRevocation({ purchaseToken: args.purchaseToken }, async () => true)).access, false);
  assert.equal(store.getSummary()[0].revokedPurchases, 1);
  await assert.rejects(store.confirmPurchase(args, verified), /purchase_revoked/);
});

test("suspension manuelle auditée, distincte du remboursement", async t => {
  const store = await fixture(t);
  await store.confirmPurchase(args, verified);
  const target = { appId: "app.demo", accountId: "client_123", sku: "premium",
    operatorId: "admin_123", reason: "Suspicion de fraude à examiner" };
  assert.equal((await store.setSuspended({ ...target, suspended: true })).access, false);
  assert.equal(store.getSummary()[0].verifiedPurchases, 1);
  assert.equal((await store.setSuspended({ ...target, suspended: false })).access, true);
  assert.equal(store.state.audit.some(a => a.action === "access_suspension_changed"), true);
});

test("interdit les RIB et secrets dans le registre d'applications", async t => {
  const store = await fixture(t);
  const serialized = JSON.stringify(store.state);
  assert.equal(serialized.includes("iban"), false);
  assert.equal(serialized.includes("secret_key"), false);
});
