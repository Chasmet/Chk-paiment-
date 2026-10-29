package com.chk.paiements;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class PaymentSyncWorker extends Worker {
    private static final String CHANNEL = "chk_payments_events";
    private static final String WORK = "chk_payment_periodic_sync";
    public PaymentSyncWorker(Context context, WorkerParameters params) { super(context, params); }

    static void schedule(Context context) {
        Constraints limits = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest job = new PeriodicWorkRequest.Builder(PaymentSyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(limits).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, job);
    }

    static void cancel(Context context) { WorkManager.getInstance(context).cancelUniqueWork(WORK); }

    static void notifyEvent(Context c, String title, String message, int id) {
        NotificationManager manager = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Paiements et validations",
                NotificationManager.IMPORTANCE_DEFAULT));
        if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(c, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(c, 1, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(id, new android.app.Notification.Builder(c, CHANNEL)
                .setContentTitle(title).setContentText(message)
                .setSmallIcon(com.chk.paiements.R.drawable.ic_app).setContentIntent(pending)
                .setAutoCancel(true).build());
    }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        if (SecureStore.getBaseUrl(context).isEmpty() || SecureStore.getToken(context).isEmpty()) return Result.success();
        try {
            JSONArray arr = new PaymentApi(context).activity();
            if (arr == null) return Result.retry();
            android.content.SharedPreferences pref = context.getSharedPreferences("chk_payment_notifications", Context.MODE_PRIVATE);
            String previous = pref.getString("known", "");
            JSONObject known = previous.isEmpty() ? new JSONObject() : new JSONObject(previous);
            JSONObject next = new JSONObject();
            boolean first = !pref.contains("known");
            int count = 0;
            for (int i=0; i<arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("order_id");
                if (id.length() < 12) continue;
                String status = o.optString("status");
                String approval = o.optString("approval_status");
                String state = status + ":" + approval;
                next.put(id, state);
                if (!first && !state.equals(known.optString(id)) && count < 8) {
                    String app = o.optString("app_id", "Application");
                    String title = "CHK Paiements : " + app;
                    String content = "pending".equals(approval) && "paid".equals(status) ?
                            "Paiement confirmé · validation requise" :
                            "pending".equals(status) ? "Nouvelle tentative de paiement" :
                            "paid".equals(status) ? "Paiement confirmé" :
                            "failed".equals(status) ? "Tentative échouée" :
                            "refunded".equals(status) ? "Paiement remboursé" :
                            "disputed".equals(status) ? "Paiement contesté" : "État : " + status;
                    notifyEvent(context, title, content, Math.abs(id.hashCode()));
                    count++;
                }
            }
            pref.edit().putString("known", next.toString()).apply();
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
