package com.chk.paiements;

import android.Manifest;
import android.app.Notification;
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
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

public final class SkillMajAutoWorker extends Worker {
    private static final String WORK = "chk_paiements_skillmajauto_daily";
    private static final String CHANNEL = "chk_paiements_updates";
    public SkillMajAutoWorker(Context context, WorkerParameters params) { super(context, params); }

    public static void schedule(Context context) {
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest check = new PeriodicWorkRequest.Builder(
                SkillMajAutoWorker.class, 1, TimeUnit.DAYS)
                .setConstraints(network)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP, check);
    }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        SkillMajAuto updater = new SkillMajAuto(context);
        SkillMajAuto.Result check = updater.check();
        if (check.release == null) return Result.success();

        String version = check.release.version;
        android.content.SharedPreferences p = context.getSharedPreferences(
                "chk_skillmajauto_notifications", Context.MODE_PRIVATE);
        if (version.equals(p.getString("last_notified", ""))) return Result.success();

        NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Mises à jour CHK Paiements", NotificationManager.IMPORTANCE_DEFAULT));

        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return Result.success();

        Intent open = new Intent(context, MainActivity.class);
        open.setAction("com.chk.paiements.NEW_RELEASE");
        PendingIntent pending = PendingIntent.getActivity(context, 81, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("CHK Paiements : nouvelle version " + version)
                .setContentText("Mise à jour disponible sur GitHub Releases via SkillMajAuto.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        manager.notify(810, notice);
        p.edit().putString("last_notified", version).apply();
        return Result.success();
    }
}
