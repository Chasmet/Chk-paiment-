package com.chk.paiements;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/**
 * SkillMajAuto : détection périodique des releases officielles.
 * Ne télécharge ni n'installe silencieusement l'APK.
 */
public final class UpdateCheckWorker extends Worker {
    private static final String JOB="chk_paiements_skillmajauto_periodic";
    public UpdateCheckWorker(Context context,WorkerParameters params){super(context,params);}

    public static void schedule(Context context){
        Constraints network=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest job=new PeriodicWorkRequest.Builder(UpdateCheckWorker.class,12,TimeUnit.HOURS)
                .setConstraints(network).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(JOB,ExistingPeriodicWorkPolicy.KEEP,job);
    }

    @Override public Result doWork() {
        Context app=getApplicationContext();
        try{
            SkillMajAuto.Result latest=new SkillMajAuto(app).check();
            if(latest.release==null)return Result.success();
            String version=latest.release.version;
            android.content.SharedPreferences prefs=app.getSharedPreferences("chk_skillmajauto_notifications",Context.MODE_PRIVATE);
            if(version.equals(prefs.getString("alerted","")))return Result.success();
            NotificationManager manager=(NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);
            String channel="chk_payments_updates";
            manager.createNotificationChannel(new NotificationChannel(channel,"Mises à jour CHK Paiements",
                    NotificationManager.IMPORTANCE_DEFAULT));
            if(android.os.Build.VERSION.SDK_INT>=33 &&
                app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                return Result.success();
            Intent open=new Intent(app,MainActivity.class);
            PendingIntent pending=PendingIntent.getActivity(app,651,open,
                    PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            manager.notify(651,new Notification.Builder(app,channel)
                    .setContentTitle("CHK Paiements "+version+" disponible")
                    .setContentText("Version officielle détectée. Ouvre l'application pour la télécharger.")
                    .setSmallIcon(R.drawable.ic_app).setContentIntent(pending)
                    .setAutoCancel(true).build());
            prefs.edit().putString("alerted",version).apply();
            return Result.success();
        }catch(Exception ignored){return Result.retry();}
    }
}
