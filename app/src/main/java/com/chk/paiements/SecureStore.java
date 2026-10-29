package com.chk.paiements;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String KEY = "chk_payments_owner_v1";
    private static final String FILE = "chk_payments_config";
    // HTTPS dédié au sidecar sur 10000 : ne modifie pas le MCP universel sur 443.
    public static final String DEFAULT_PAYMENTS_URL = "https://chk-universal-1.taild9e4de.ts.net:10000";
    private SecureStore() {}

    private static SharedPreferences preferences(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static void setBaseUrl(Context ctx, String value) {
        String url = value == null ? "" : value.trim().replaceAll("/+$", "");
        if (!url.startsWith("https://") || !url.matches("https://[A-Za-z0-9.-]+(?::[0-9]{2,5})?") ||
                url.equals("https://chk-universal-1.taild9e4de.ts.net"))
            throw new IllegalArgumentException("URL HTTPS du service Paiements uniquement (ne pas utiliser /mcp universel)");
        preferences(ctx).edit().putString("base", url).apply();
    }

    public static String getBaseUrl(Context ctx) {
        return preferences(ctx).getString("base", DEFAULT_PAYMENTS_URL);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY, null);
    }

    public static synchronized void setToken(Context ctx, String token) throws Exception {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("Jeton invalide");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        SharedPreferences.Editor edit = preferences(ctx).edit();
        edit.putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        edit.putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        if (!edit.commit()) throw new IllegalStateException("Enregistrement sécurisé impossible");
    }

    public static synchronized String getToken(Context ctx) {
        String token = preferences(ctx).getString("token", "");
        String iv = preferences(ctx).getString("iv", "");
        if (token.isEmpty() || iv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(token, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void clearToken(Context ctx) {
        preferences(ctx).edit().remove("token").remove("iv").apply();
    }
}
