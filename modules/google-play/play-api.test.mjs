import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, verify as verifySignature } from "node:crypto";
import { createServiceAccountTokenProvider, createGooglePlayVerifier } from "./play-api.mjs";

const packageName = "com.chk.rushstudio";
const sku = "premium";
const purchaseToken = "play-token-test-123456789";
const now = () => Date.parse("2026-10-30T21:00:00Z");

function verifier(response, { status = 200 } = {}) {
  const calls = [];
  const getAccessToken = async () => "token-bearer-google-123456789";
  const fetchFn = async (url, opts) => {
    calls.push({ url, opts });
    return { ok: status === 200, status, json: async () => response };
  };
  return { api: createGooglePlayVerifier({ getAccessToken, fetchFn, now }), calls };
}
const request = { packageName, sku, purchaseToken, productType: "one_time" };
test("l'achat unique PURCHASED authentifié donne la preuve attendue", async () => {
  const { api, calls } = verifier({
    purchaseStateContext: { purchaseState: "PURCHASED" }, productLineItem: [{ productId: sku }]
  });
  const verified = await api.verifyPurchase(request);
  assert.equal(verified.purchaseState, "PURCHASED");
  assert.equal(verified.sku, sku);
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /purchases\/productsv2\/tokens\//);
  assert.equal(calls[0].opts.method, "GET");
});
test("un PENDING ou un SKU différent ne donne jamais de licence", async () => {
  for (const [purchaseState, productId] of [["PENDING", sku], ["PURCHASED", "other_product"]]) {
    const { api } = verifier({
      purchaseStateContext: { purchaseState }, productLineItem: [{ productId }]
    });
    await assert.rejects(api.verifyPurchase(request), /google_purchase_not_paid/);
  }
});
test("un HTTP 403 sur Google est refusé sans contournement", async () => {
  const { api } = verifier({}, { status: 403 });
  await assert.rejects(api.verifyPurchase(request), /google_api_permission_denied/);
});
test("abonnements actifs, grâce, résiliés mais non expirés respectés", async () => {
  for (const status of ["SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"]) {
    const { api } = verifier({
      subscriptionState: status,
      lineItems: [{ productId: sku, expiryTime: "2026-11-30T12:00:00Z" }]
    });
    const found = await api.verifyPurchase({ ...request, productType: "subscription" });
    assert.equal(found.productType, "subscription");
    assert.equal(found.expiresAt, Date.parse("2026-11-30T12:00:00Z"));
  }
});
test("un abonnement en pause, en attente ou expiré ne passe pas", async () => {
  for (const status of ["SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PENDING",
    "SUBSCRIPTION_STATE_PAUSED", "SUBSCRIPTION_STATE_EXPIRED"]) {
    const { api } = verifier({
      subscriptionState: status,
      lineItems: [{ productId: sku, expiryTime: "2026-11-30T12:00:00Z" }]
    });
    await assert.rejects(api.verifyPurchase({ ...request, productType: "subscription" }),
      /google_subscription_not_entitled/);
  }
  const { api } = verifier({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    lineItems: [{ productId: sku, expiryTime: "2026-10-01T00:00:00Z" }] });
  await assert.rejects(api.verifyPurchase({ ...request, productType: "subscription" }),
    /google_subscription_expired/);
});
test("OAuth échange un JWT RS256 signé sans exposer la clé privée", async () => {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048,
    publicKeyEncoding: { type: "spki", format: "pem" },
    privateKeyEncoding: { type: "pkcs8", format: "pem" } });
  let calls = 0;
  const provider = createServiceAccountTokenProvider({
    clientEmail: "chk@example-project.iam.gserviceaccount.com",
    privateKey, now,
    fetchFn: async (url, opts) => {
      calls++;
      assert.equal(url, "https://oauth2.googleapis.com/token");
      const assertion = new URLSearchParams(opts.body).get("assertion");
      const parts = assertion.split(".");
      assert.equal(parts.length, 3);
      assert.equal(verifySignature("RSA-SHA256", Buffer.from(parts.slice(0, 2).join(".")),
        publicKey, Buffer.from(parts[2], "base64url")), true);
      const claims = JSON.parse(Buffer.from(parts[1], "base64url").toString());
      assert.equal(claims.scope, "https://www.googleapis.com/auth/androidpublisher");
      assert.equal(opts.body.includes(privateKey), false);
      return { ok: true, json: async () => ({ access_token: "valid-google-oauth-token-123456", expires_in: 3600 }) };
    }
  });
  assert.equal(await provider(), "valid-google-oauth-token-123456");
  assert.equal(await provider(), "valid-google-oauth-token-123456");
  assert.equal(calls, 1);
});
