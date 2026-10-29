package com.chk.paiements;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

public final class PaymentApi {
    private final Context context;
    PaymentApi(Context context) { this.context = context.getApplicationContext(); }

    private JSONObject request(String method, String path, JSONObject data, boolean needsAuth) throws Exception {
        return requestAt(SecureStore.getBaseUrl(context), method, path, data, needsAuth);
    }

    private JSONObject requestAt(String base, String method, String path, JSONObject data, boolean needsAuth) throws Exception {
        if (base.isEmpty()) throw new IllegalStateException("Configurer l'URL du serveur de paiements");
        URL url = new URL(base + path);
        if (!url.getProtocol().equals("https")) throw new IllegalArgumentException("HTTPS requis");
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        c.setConnectTimeout(path.equals("/health") ? 1800 : 12000);
        c.setReadTimeout(path.equals("/health") ? 2300 : 15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (needsAuth) {
            String token = SecureStore.getToken(context);
            if (token.isEmpty()) throw new IllegalStateException("Appairer l'application");
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        try {
            if (data != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] b = data.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = c.getOutputStream()) { out.write(b); }
            }
            int status = c.getResponseCode();
            if (status >= 300 && status < 400) throw new IllegalStateException("Redirection interdite");
            if (status == 401) throw new IllegalStateException("Appairage refusé ou révoqué");
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (stream == null) throw new IllegalStateException("Réponse vide");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            try (InputStream in = stream) {
                while ((n = in.read(buf)) != -1) {
                    bytes.write(buf, 0, n);
                    if (bytes.size() > 1024 * 1024) throw new IllegalStateException("Réponse trop volumineuse");
                }
            }
            JSONObject result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (status < 200 || status >= 300) throw new IllegalStateException("Serveur " + status + " : " + result.optString("error", "Erreur"));
            return result;
        } finally { c.disconnect(); }
    }

    // The PC can be pc-salon or chk-universal-1. Discover the real CHK Payments
    // service using TLS and /health; never use the universal /mcp port 443.
    public JSONObject pair(String pairCode) throws Exception {
        if (pairCode == null || !pairCode.trim().matches("[a-fA-F0-9]{48}"))
            throw new IllegalArgumentException("Code d'appairage invalide (48 caracteres hexadecimaux).");
        // Discover only the dedicated private payment routes. Never probe
        // the Universal Control /mcp public route or change any Tailscale setting.
        String[] hosts = {
                "https://pc-salon.taild9e4de.ts.net:11001",
                "https://pc-salon.taild9e4de.ts.net:11002",
                "https://pc-salon.taild9e4de.ts.net:11003",
                "https://pc-salon.taild9e4de.ts.net:11004",
                "https://chk-universal-1.taild9e4de.ts.net:11001",
                "https://chk-universal-1.taild9e4de.ts.net:11002",
                "https://chk-universal-1.taild9e4de.ts.net:11003",
                "https://chk-universal-1.taild9e4de.ts.net:11004"
        };
        JSONObject body = new JSONObject().put("code", pairCode.trim().toLowerCase(java.util.Locale.ROOT))
                .put("device_name", "CHK Paiements Android");
        boolean foundService = false;
        Exception lastPairError = null;
        for (String host : hosts) {
            try {
                JSONObject health = requestAt(host, "GET", "/health", null, false);
                if (!"CHK Paiements Sidecar".equals(health.optString("service"))) continue;
                foundService = true;
                try {
                    JSONObject response = requestAt(host, "POST", "/api/v1/pair", body, false);
                    String deviceToken = response.optString("device_token", "");
                    if (!deviceToken.startsWith("chkpay_") || deviceToken.length() < 32)
                        throw new IllegalStateException("Reponse d'appairage du serveur incorrecte.");
                    SecureStore.setBaseUrl(context, host);
                    return response;
                } catch (Exception e) {
                    lastPairError = e;
                }
            } catch (javax.net.ssl.SSLException e) {
                // Never bypass TLS certificate verification or downgrade to HTTP.
                lastPairError = e;
            } catch (Exception e) {
                lastPairError = e;
            }
        }
        if (foundService)
            throw new IllegalStateException("Serveur Paiements trouve, mais code refuse ou expire. Generer un nouveau code sur le PC.", lastPairError);
        throw new IllegalStateException("Aucun service CHK Paiements HTTPS prive accessible sur Tailscale. Verifier la connexion du PC et du telephone.", lastPairError);
    }

    public JSONArray activity() throws Exception {
        return request("GET", "/api/v1/activity", null, true).optJSONArray("items");
    }

    public JSONArray pendingReviews() throws Exception {
        return request("GET", "/api/v1/review-queue", null, true).optJSONArray("items");
    }

    public JSONArray summary() throws Exception {
        return request("GET", "/api/v1/summary", null, true).optJSONArray("items");
    }

    public boolean revokeThisDevice() throws Exception {
        return request("DELETE", "/api/v1/device", null, true).optBoolean("revoked");
    }

    public JSONObject review(String orderId, String decision) throws Exception {
        return request("POST", "/api/v1/reviews", new JSONObject()
                .put("order_id", orderId).put("decision", decision), true);
    }
}
