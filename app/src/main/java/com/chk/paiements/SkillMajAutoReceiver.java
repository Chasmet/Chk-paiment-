package com.chk.paiements;
import android.content.BroadcastReceiver;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.io.File;

public final class SkillMajAutoReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null||!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()))return;
        final PendingResult finish=goAsync();
        final Context app=context.getApplicationContext();
        long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);
        new Thread(() -> {
            try {
                SkillMajAuto updater=new SkillMajAuto(app);
                if(!updater.ownsDownload(id))return;
                File apk=updater.verifiedDownload();
                if(apk==null)return;
                NotificationManager mgr=(NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);
                String channel="chk_payment_updates";
                mgr.createNotificationChannel(new NotificationChannel(channel,"Mises à jour CHK Paiements",
                    NotificationManager.IMPORTANCE_HIGH));
                Intent open=new Intent(app,MainActivity.class);
                open.setAction("com.chk.paiements.UPDATE_READY");
                PendingIntent pending=PendingIntent.getActivity(app,63,open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                mgr.notify(630,new Notification.Builder(app,channel).setSmallIcon(R.drawable.ic_app)
                    .setContentTitle("CHK Paiements : mise à jour prête")
                    .setContentText("Ouvre l'application pour installer la version signée.")
                    .setContentIntent(pending).setAutoCancel(true).build());
            }catch(Exception ignored){} finally {finish.finish();}
        },"chk-paiements-update-check").start();
    }
}
