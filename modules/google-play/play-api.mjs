// CHK Paiements — vérification serveur des achats Google Play (Node.js 22+).
// JAMAIS dans une APK. Service account dans des variables secrètes du serveur.
import { sign } from "node:crypto";

const scope = "https://www.googleapis.com/auth/androidpublisher";
const audience = "https://oauth2.googleapis.com/token";
const endpoint = "https://androidpublisher.googleapis.com/androidpublisher/v3";
const packagePattern = /^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*){2,}$/;
const skuPattern = /^[A-Za-z0-9_.-]{2,100}$/;
const toBase64 = value => Buffer.from(JSON.stringify(value)).toString("base64url");
const failure = code => { const error = Error(code); error.code = code; return error; };

export function createServiceAccountTokenProvider({
  clientEmail, privateKey, fetchFn = fetch, now = () => Date.now()
}) {
  if (typeof clientEmail !== "string" || !/^[^@\s]+@[^@\s]+\.iam\.gserviceaccount\.com$/.test(clientEmail) ||
      typeof privateKey !== "string" || !privateKey.includes("PRIVATE KEY"))
    throw failure("google_credentials_missing");
  let cached = null;
  let expires = 0;
  let inflight = null;
  return async () => {
    if (cached && expires - now() > 60_000) return cached;
    if (inflight) return inflight;
    inflight = (async () => {
      const issued = Math.floor(now() / 1000);
      const assertion = toBase64({ alg: "RS256", typ: "JWT" }) + "." +
        toBase64({ iss: clientEmail, scope, aud: audience, iat: issued, exp: issued + 3600 });
      const signature = sign("RSA-SHA256", Buffer.from(assertion), privateKey).toString("base64url");
      let response;
      try {
        response = await fetchFn(audience, {
          method: "POST",
          headers: { "content-type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
            assertion: assertion + "." + signature
          }).toString(),
          redirect: "error", signal: AbortSignal.timeout(15000)
        });
      } catch {
        throw failure("google_oauth_unreachable");
      }
      if (!response.ok) throw failure("google_oauth_rejected");
      let data;
      try { data = await response.json(); } catch { throw failure("google_oauth_invalid_json"); }
      if (typeof data.access_token !== "string" || data.access_token.length < 20 ||
          !Number.isFinite(Number(data.expires_in)))
        throw failure("google_oauth_invalid_response");
      cached = data.access_token;
      expires = now() + Math.min(Math.max(Number(data.expires_in), 60), 3600) * 1000;
      return cached;
    })();
    try { return await inflight; } finally { inflight = null; }
  };
}

export function createGooglePlayVerifier({ getAccessToken, fetchFn = fetch, now = () => Date.now() }) {
  if (typeof getAccessToken !== "function") throw failure("google_auth_not_configured");

  async function readGoogle(packageName, purchaseToken, kind) {
    if (!packagePattern.test(packageName || "") ||
        typeof purchaseToken !== "string" || purchaseToken.length < 16 || purchaseToken.length > 8192)
      throw failure("invalid_purchase_input");
    const resource = kind === "subscription" ? "subscriptionsv2" : "productsv2";
    const url = endpoint + "/applications/" + encodeURIComponent(packageName) +
      "/purchases/" + resource + "/tokens/" + encodeURIComponent(purchaseToken);
    const accessToken = await getAccessToken();
    if (typeof accessToken !== "string" || accessToken.length < 20)
      throw failure("google_token_missing");
    let response;
    try {
      response = await fetchFn(url, {
        method: "GET",
        headers: { authorization: "Bearer " + accessToken, accept: "application/json" },
        redirect: "error",
        signal: AbortSignal.timeout(15000)
      });
    } catch { throw failure("google_play_unreachable"); }
    if (!response.ok) {
      if ([400, 404, 410].includes(response.status)) throw failure("google_purchase_not_found");
      if ([401, 403].includes(response.status)) throw failure("google_api_permission_denied");
      throw failure("google_play_error");
    }
    try { return await response.json(); }
    catch { throw failure("google_play_invalid_response"); }
  }

  async function verifyPurchase({ packageName, sku, purchaseToken, productType }) {
    if (!skuPattern.test(sku || "") || !["one_time", "subscription"].includes(productType))
      throw failure("invalid_product_identity");
    const raw = await readGoogle(packageName, purchaseToken, productType);
    if (productType === "one_time") {
      const lineItems = Array.isArray(raw?.productLineItem) ? raw.productLineItem : [];
      // Google fournit purchaseStateContext et productLineItem (PAS purchaseState en racine).
      if (raw?.purchaseStateContext?.purchaseState !== "PURCHASED" ||
          !lineItems.some(item => item?.productId === sku))
        throw failure("google_purchase_not_paid");
      return { packageName, sku, purchaseState: "PURCHASED", productType: "one_time" };
    }
    const permitted = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
      "SUBSCRIPTION_STATE_CANCELED"];
    if (!permitted.includes(raw?.subscriptionState))
      throw failure("google_subscription_not_entitled");
    const expiry = (Array.isArray(raw?.lineItems) ? raw.lineItems : [])
      .filter(item => item?.productId === sku)
      .map(item => Date.parse(item?.expiryTime || ""))
      .filter(value => Number.isSafeInteger(value) && value > now())
      .sort((a, b) => b - a)[0];
    if (!expiry) throw failure("google_subscription_expired");
    return { packageName, sku, purchaseState: "PURCHASED",
      productType: "subscription", expiresAt: expiry };
  }

  // Une révocation est confirmée UNIQUEMENT via la liste officielle des achats annulés.
  // Les développeurs peuvent brancher une tâche RTDN + Voided Purchases API ici.
  // Aucune fonction ne transforme un signal client en preuve de remboursement.
  return { verifyPurchase };
}
