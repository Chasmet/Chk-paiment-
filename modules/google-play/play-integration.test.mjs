import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { GooglePlayEntitlements } from "./entitlements.mjs";
import { createGooglePlayVerifier } from "./play-api.mjs";

test("Google Play -> paiement vérifié -> licence CHK Premium, sans crédit sur PENDING", async t => {
  const dir = await mkdtemp(join(tmpdir(), "chk-paiements-flow-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const hub = await new GooglePlayEntitlements(join(dir, "licenses.json")).init();
  await hub.registerApp({
    appId: "rush.studio", packageName: "com.chk.rushstudio",
    displayName: "Rush Studio", operatorId: "owner_123"
  });
  const input = { appId: "rush.studio", sku: "premium",
    accountId: "customer_001", purchaseToken: "google-token-very-long-valid-123",
    productType: "one_time" };
  let state = "PENDING";
  const api = createGooglePlayVerifier({
    getAccessToken: async () => "secure-google-access-token-12345678",
    fetchFn: async () => ({ ok: true, status: 200, json: async () => ({
      purchaseStateContext: { purchaseState: state },
      productLineItem: [{ productId: "premium" }]
    }) })
  });
  await assert.rejects(hub.confirmPurchase(input, api.verifyPurchase), /google_purchase_not_paid/);
  assert.equal(hub.getEntitlement(input.appId, input.accountId, input.sku).access, false);
  state = "PURCHASED";
  const accepted = await hub.confirmPurchase(input, api.verifyPurchase);
  assert.equal(accepted.access, true);
  assert.equal(hub.getSummary()[0].verifiedPurchases, 1);
  await assert.rejects(hub.confirmPurchase({ ...input, accountId: "customer_002" }, api.verifyPurchase),
    /purchase_token_reused/);
});

test("la désactivation des ventes ne retire pas une licence Premium déjà payée", async t => {
  const dir = await mkdtemp(join(tmpdir(), "chk-sales-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const hub = await new GooglePlayEntitlements(join(dir, "licenses.json")).init();
  await hub.registerApp({ appId: "rush.studio", packageName: "com.chk.rushstudio",
    displayName: "Rush Studio", operatorId: "owner_123" });
  await hub.confirmPurchase({
    appId: "rush.studio", sku: "premium", accountId: "customer_001",
    purchaseToken: "google-token-very-long-valid-123", productType: "one_time"
  }, async () => ({ packageName: "com.chk.rushstudio", sku: "premium",
    purchaseState: "PURCHASED", productType: "one_time" }));
  await hub.setNewSalesEnabled({ appId: "rush.studio", enabled: false, operatorId: "owner_123" });
  assert.equal(hub.getEntitlement("rush.studio", "customer_001", "premium").access, true);
});
