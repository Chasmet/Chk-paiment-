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
        String base = SecureStore.getBaseUrl(context);
        if (base.isEmpty()) throw new IllegalStateException("Configurer l'URL du serveur de paiements");
        URL url = new URL(base + path);
        if (!url.getProtocol().equals("https")) throw new IllegalArgumentException("HTTPS requis");
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
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

    public JSONObject pair(String pairCode) throws Exception {
        return request("POST", "/api/v1/pair",
                new JSONObject().put("code", pairCode).put("device_name", "CHK Paiements Android"), false);
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

    public JSONArray summary() throws Exception {
        return request("GET", "/api/v1/summary", null, true).optJSONArray("items");
    }

    public JSONObject review(String orderId, String decision) throws Exception {
        return request("POST", "/api/v1/reviews", new JSONObject()
                .put("order_id", orderId).put("decision", decision), true);
    }
}
