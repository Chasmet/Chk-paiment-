import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { GooglePlayEntitlements } from "./entitlements.mjs";

async function setup(t) {
  const dir = await mkdtemp(join(tmpdir(), "chk-trial-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const db = await new GooglePlayEntitlements(join(dir, "licences.json")).init();
  await db.registerApp({ appId: "rush.studio", packageName: "com.chk.rushstudio",
    displayName: "Rush Studio", operatorId: "owner_123" });
  return db;
}
const id = { appId: "rush.studio", accountId: "customer_001", sku: "rush_premium" };
const auth = async () => true;

test("essai gratuit 72h avec identité vérifiée, sans transaction", async t => {
  const db = await setup(t);
  await assert.rejects(db.startTrial(id, async () => false), /trial_identity_not_verified/);
  assert.equal(db.getEntitlement(id.appId, id.accountId, id.sku).access, false);
  const trial = await db.startTrial(id, auth);
  assert.equal(trial.access, true);
  assert.equal(trial.reason, "trial_active");
  assert.equal(db.state.purchases && Object.keys(db.state.purchases).length, 0);
  assert.equal(db.getEntitlement(id.appId,id.accountId,id.sku,trial.trialEndsAt - 1).access, true);
  assert.equal(db.getEntitlement(id.appId,id.accountId,id.sku,trial.trialEndsAt).access, false);
  assert.equal(db.getEntitlement(id.appId,id.accountId,id.sku,trial.trialEndsAt).reason, "trial_expired");
});

test("un nouveau lancement ou une concurrence ne prolonge jamais l'essai", async t => {
  const db = await setup(t);
  const results = await Promise.all(Array.from({ length: 5 }, () => db.startTrial(id, auth)));
  assert.ok(results.every(v => v.trialEndsAt === results[0].trialEndsAt));
  const again = await db.startTrial(id, auth);
  assert.equal(again.trialEndsAt, results[0].trialEndsAt);
  assert.equal(db.state.audit.filter(a => a.action === "trial_started").length, 1);
  const restored = await new GooglePlayEntitlements(db.path).init();
  assert.equal(restored.getEntitlement(id.appId,id.accountId,id.sku).trialEndsAt, again.trialEndsAt);
});

test("achat unique confirmé reste actif après les 3 jours", async t => {
  const db = await setup(t);
  const trial = await db.startTrial(id, auth);
  const purchaseToken = "google-verified-token-for-rush-0001";
  const paid = await db.confirmPurchase({...id, productType: "one_time", purchaseToken},
    async request => ({ packageName: request.packageName, sku: request.sku,
      productType: "one_time", purchaseState: "PURCHASED" }));
  assert.equal(paid.reason, "verified_purchase");
  const future = db.getEntitlement(id.appId,id.accountId,id.sku,trial.trialEndsAt + 1000);
  assert.equal(future.access,true);
  assert.equal(future.reason,"verified_purchase");
});

test("une suspension justifiée bloque l'accès même pendant la période gratuite", async t => {
  const db = await setup(t);
  await db.startTrial(id, auth);
  const result=await db.setSuspended({...id, operatorId:"owner_123",
    suspended:true, reason:"Compte compromis, protection provisoire"});
  assert.equal(result.access,false);
  assert.equal(result.reason,"owner_suspension");
});

test("la base historique sans champ trials reste lisible", async t => {
  const db=await setup(t);
  delete db.state.trials;
  const { writeFile } = await import("node:fs/promises");
  await writeFile(db.path,JSON.stringify(db.state));
  const restored=await new GooglePlayEntitlements(db.path).init();
  assert.equal(Object.keys(restored.state.trials).length,0);
});
