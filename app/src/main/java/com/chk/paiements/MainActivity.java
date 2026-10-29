package com.chk.paiements;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root, activityList;
    private TextView status, counts, perApp;
    private EditText codeField;
    private Button pairButton, refreshButton, updateButton;
    private TextView updateStatus;
    private SkillMajAuto.Release availableRelease;
    private static final int BG=0xFF10192A, TEXT=0xFFF0F5FF, MUTE=0xFFA9B6CB, GREEN=0xFF1B9B86;

    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String s, int size, int color) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(dp(4),dp(5),dp(4),dp(5)); return t;
    }
    private Button button(String title) {
        Button b=new Button(this); b.setText(title); b.setAllCaps(false); return b;
    }
    private EditText field(String hint) {
        EditText e=new EditText(this);
        e.setSingleLine(true); e.setTextColor(TEXT); e.setHintTextColor(MUTE); e.setHint(hint);
        return e;
    }
    private LinearLayout panel() { return panel(root); }
    private LinearLayout panel(LinearLayout parent) {
        LinearLayout p=new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(12),dp(10),dp(12),dp(10));
        p.setBackgroundTintList(null);
        p.setBackgroundColor(0xFF1B2940);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,0,0,dp(10));
        parent.addView(p,params);
        return p;
    }
    private interface Operation { void run() throws Exception; }
    private void run(String step, Operation job) {
        status.setText(step);
        executor.execute(() -> {
            try { job.run(); }
            catch (Exception error) {
                String message=error.getMessage()==null ? "Erreur de connexion" : error.getMessage();
                runOnUiThread(() -> status.setText("Erreur : " + message));
            }
        });
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(22),dp(16),dp(40));scroll.addView(root); setContentView(scroll);
        root.addView(label("CHK PAIEMENTS",26,TEXT));
        root.addView(label("Sync 30 Numérique · Tableau de bord privé",14,MUTE));
        root.addView(label("Serveur universel et /mcp existants préservés",12,0xFF74DECA));

        LinearLayout connection=panel();
        connection.addView(label("Connexion sécurisée au module Paiements",18,TEXT));
        connection.addView(label("Tailscale : recherche automatique du PC Paiements sur HTTPS 10000",13,0xFF74DECA));
        codeField=field("Code d'appairage à usage unique"); connection.addView(codeField);
        pairButton=button("Appairer mon téléphone"); connection.addView(pairButton);
        pairButton.setOnClickListener(v -> pair());
        connection.addView(label("Le serveur universel /mcp sur 443 reste inchangé. Le service Paiements doit être activé sur ton PC.",12,MUTE));
        refreshButton=button("Actualiser les opérations"); connection.addView(refreshButton);
        refreshButton.setOnClickListener(v -> refresh());
        Button logout=button("Dissocier ce téléphone");
        logout.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Révoquer ce téléphone ?")
                .setMessage("Le serveur invalidera son accès avant de supprimer la clé locale.")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Révoquer",(d,w) ->
                    run("Révocation sécurisée en cours…",() -> {
                        if (!new PaymentApi(this).revokeThisDevice())
                            throw new IllegalStateException("Révocation serveur non confirmée");
                        SecureStore.clearToken(this);
                        PaymentSyncWorker.cancel(this);
                        runOnUiThread(() -> status.setText("Accès révoqué côté serveur et jeton effacé."));
                    })
                ).show());
        connection.addView(logout);
        LinearLayout updates=panel();
        updates.addView(label("SkillMajAuto · Mises à jour GitHub Releases",17,TEXT));
        updates.addView(label("Vérification automatique à l’ouverture et en arrière-plan. Aucun réglage nécessaire.",12,MUTE));
        updateStatus=label("Recherche des versions officielles…",12,MUTE);
        updates.addView(updateStatus);
        updateButton=button("Vérifier les mises à jour");
        updates.addView(updateButton);
        updateButton.setOnClickListener(v -> {
            SkillMajAuto updater=new SkillMajAuto(this);
            java.io.File ready=updater.verifiedDownload();
            if(ready!=null) {
                try { updater.install(ready,this); }
                catch(Exception e) { updateStatus.setText(e.getMessage()); }
                return;
            }
            if(availableRelease!=null) {
                try {
                    updater.download(availableRelease);
                    updateStatus.setText("Téléchargement de la version "+availableRelease.version+" en cours…");
                    updateButton.setText("Vérifier après téléchargement");
                    availableRelease=null;
                } catch(Exception e) { updateStatus.setText("Téléchargement impossible : "+e.getMessage()); }
            } else checkUpdates();
        });
        counts=label("Aucune donnée bancaire stockée sur ce téléphone",14,MUTE);root.addView(counts);
        status=label("Serveur Paiements séparé : en attente de disponibilité ou de code d’appairage",13,MUTE);root.addView(status);
        perApp=label("Classement par application : aucune donnée",13,MUTE);root.addView(perApp);
        activityList=new LinearLayout(this);activityList.setOrientation(LinearLayout.VERTICAL);root.addView(activityList);

        if (android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},404);
        }
        if (!SecureStore.getToken(this).isEmpty()) {
            PaymentSyncWorker.schedule(this);
            refresh();
        }
        UpdateCheckWorker.schedule(this);
        SkillMajAutoWorker.schedule(this);
        checkUpdates();
    }
    private void pair() {
        String code=codeField.getText().toString().trim();
        if (!code.matches("[a-fA-F0-9]{48}")) { status.setText("Saisir le code de 48 caractères généré sur ton PC."); return; }
        run("Recherche automatique du PC CHK Paiements sur Tailscale…",() -> {
            JSONObject reply=new PaymentApi(this).pair(code);
            SecureStore.setToken(this,reply.getString("device_token"));
            runOnUiThread(() -> {
                codeField.setText("");
                status.setText("Téléphone appairé à "+SecureStore.getBaseUrl(this)+". Surveillance activée.");
                PaymentSyncWorker.schedule(this);
                refresh();
            });
        });
    }
    private void refresh() {
        if (SecureStore.getToken(this).isEmpty()) { status.setText("Appairage requis.");return; }
        run("Lecture des transactions réelles…",() -> {
            PaymentApi api=new PaymentApi(this);
            JSONArray activity=api.activity(); JSONArray reviews=api.pendingReviews(); JSONArray summary=api.summary();
            if (activity==null || reviews==null || summary==null) throw new IllegalStateException("Réponse incomplète");
            runOnUiThread(() -> render(activity,reviews,summary));
        });
    }
    private void render(JSONArray activity,JSONArray reviews,JSONArray summary) {
        activityList.removeAllViews();
        int attempts=0, paid=0, failed=0, refunded=0;
        List<JSONObject> manual=new ArrayList<>(), attemptsList=new ArrayList<>(), paidList=new ArrayList<>(), failedList=new ArrayList<>();
        for(int i=0;i<activity.length();i++){
            JSONObject e=activity.optJSONObject(i); if(e==null) continue;
            String s=e.optString("status"), a=e.optString("approval_status");
            if("paid".equals(s)) {paid++; if("pending".equals(a)) manual.add(e); else paidList.add(e);}
            else if("pending".equals(s)||"creating".equals(s)){attempts++;attemptsList.add(e);}
            else {if("failed".equals(s))failed++;if("refunded".equals(s))refunded++;failedList.add(e);}
        }
        counts.setText("Paiements confirmés : "+paid+"   ·   Essais : "+attempts+
                "\nÉchecs : "+failed+"   ·   Remboursements : "+refunded+"   ·   À valider : "+reviews.length());
        StringBuilder breakdown=new StringBuilder("CLASSEMENT PAR APPLICATION\n");
        for(int i=0;i<summary.length();i++) {
            JSONObject item=summary.optJSONObject(i);
            if(item==null)continue;
            breakdown.append(item.optString("app_id","Application")).append(" : ")
              .append(item.optInt("confirmed")).append(" paiements, ")
              .append(item.optInt("attempts")).append(" essais, ")
              .append(item.optInt("awaiting_review")).append(" à valider");
            int cents=item.optInt("gross_confirmed_cents",0);
            String currency=item.optString("currency","eur").toUpperCase(java.util.Locale.ROOT);
            breakdown.append("\n  Recettes brutes confirmées : ")
              .append(String.format(java.util.Locale.FRANCE,"%.2f",cents/100.0))
              .append(" ").append(currency).append("\n");
        }
        perApp.setText(breakdown.toString());
        status.setText("Synchronisé avec le serveur · une tentative n'est pas un paiement");
        section("À VALIDER APRÈS PAIEMENT",manual,true);
        section("PAIEMENTS CONFIRMÉS",paidList,false);
        section("TENTATIVES EN COURS",attemptsList,false);
        section("ÉCHECS / REMBOURSEMENTS / LITIGES",failedList,false);
    }
    private void section(String title,List<JSONObject> values,boolean canReview) {
        activityList.addView(label(title+" ("+values.size()+")",15,0xFF74DECA));
        if(values.isEmpty()){activityList.addView(label("Aucune opération",12,MUTE));return;}
        for(JSONObject o:values) {
            LinearLayout p=panel(activityList);
            p.addView(label(o.optString("app_id","Application")+" · "+o.optString("sku","Article"),16,TEXT));
            String state=o.optString("status")+" / "+o.optString("approval_status","—");
            p.addView(label(state,13,0xFFCCD9ED));
            if(o.has("amount_cents"))p.addView(label(String.format(java.util.Locale.FRANCE,"Montant : %.2f %s",o.optInt("amount_cents")/100.0,o.optString("currency","eur").toUpperCase(java.util.Locale.ROOT)),13,0xFF8DE1D5));
            p.addView(label("Commande : "+o.optString("order_id"),11,MUTE));
            if(canReview) {
                String id=o.optString("order_id");
                Button yes=button("Approuver l'accès");p.addView(yes);
                Button no=button("Refuser l'accès");p.addView(no);
                yes.setOnClickListener(v -> confirmReview(id,"approve"));
                no.setOnClickListener(v -> confirmReview(id,"deny"));
            }
        }
    }
    private void confirmReview(String orderId,String choice) {
        String title="approve".equals(choice)?"Autoriser l'accès ?":"Refuser l'accès payé ?";
        String explanation="approve".equals(choice) ?
                "Le paiement doit déjà être confirmé par le serveur. Accorder la licence ?" :
                "Le client a payé. Refuser l'accès nécessite de gérer son remboursement. Continuer ?";
        new AlertDialog.Builder(this).setTitle(title).setMessage(explanation)
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Confirmer",(d,w)->run("Décision en cours…",() -> {
                    new PaymentApi(this).review(orderId,choice);
                    runOnUiThread(this::refresh);
                })).show();
    }
    private void checkUpdates() {
        updateStatus.setText("Vérification GitHub en cours…");
        executor.execute(() -> {
            SkillMajAuto updater=new SkillMajAuto(this);
            java.io.File ready=updater.verifiedDownload();
            if(ready!=null) {
                runOnUiThread(() -> {
                    updateStatus.setText("APK téléchargé, signature compatible et version supérieure vérifiées.");
                    updateButton.setText("Installer la mise à jour");
                });
                return;
            }
            SkillMajAuto.Result result=updater.check();
            runOnUiThread(() -> {
                availableRelease=result.release;
                updateStatus.setText(result.message);
                updateButton.setText(result.release!=null?"Télécharger la version "+result.release.version:"Vérifier les mises à jour");
            });
        });
    }

    @Override protected void onDestroy(){executor.shutdown();super.onDestroy();}
}
