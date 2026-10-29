package com.chk.paiements;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class SkillMajAuto {
    private static final String LATEST =
            "https://api.github.com/repos/Chasmet/Chk-paiment-/releases/latest";
    private static final String ASSET_PREFIX =
            "https://github.com/Chasmet/Chk-paiment-/releases/download/";
    private static final String NAME_PREFIX = "CHK-Paiements-";
    private final Context app;
    private final SharedPreferences prefs;

    public static final class Release {
        public final String version, url, fileName;
        Release(String version, String url, String fileName) {
            this.version=version; this.url=url; this.fileName=fileName;
        }
    }

    public static final class Result {
        public final String message;
        public final Release release;
        Result(String message, Release release) { this.message=message; this.release=release; }
    }
    public SkillMajAuto(Context context) {
        app=context.getApplicationContext();
        prefs=app.getSharedPreferences("chk_skillmajauto",Context.MODE_PRIVATE);
    }

    public static boolean newer(String remote, String local) {
        if(!remote.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
           !local.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] r=remote.split("\\."), l=local.split("\\.");
        for(int i=0;i<3;i++) {
            long a=Long.parseLong(r[i]), b=Long.parseLong(l[i]);
            if(a!=b) return a>b;
        }
        return false;
    }

    public Result check() {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(LATEST).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept","application/vnd.github+json");
            c.setRequestProperty("User-Agent","CHK-Paiements-SkillMajAuto/1.0");
            if(c.getResponseCode()==404) return new Result("Aucune mise à jour officielle publiée.",null);
            if(c.getResponseCode()!=200) return new Result("GitHub HTTP "+c.getResponseCode(),null);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()) {
                byte[] buf=new byte[4096]; int n;
                while((n=in.read(buf))!=-1) {
                    if(out.size()+n>250000) throw new IllegalStateException("Métadonnées trop volumineuses");
                    out.write(buf,0,n);
                }
            }
            JSONObject release=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            if(release.optBoolean("draft") || release.optBoolean("prerelease"))
                return new Result("Pas de release stable.",null);
            String remote=release.optString("tag_name","").replaceFirst("^[vV]","");
            if(!remote.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
                return new Result("Version GitHub invalide.",null);
            String local=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;
            if(!newer(remote,local)) return new Result("Version à jour : "+local,null);
            String expected=NAME_PREFIX+remote+".apk";
            JSONArray assets=release.optJSONArray("assets");
            if(assets!=null) for(int i=0;i<assets.length();i++) {
                JSONObject item=assets.optJSONObject(i);
                if(item==null)continue;
                String name=item.optString("name"), uri=item.optString("browser_download_url");
                if(name.equals(expected) && uri.startsWith(ASSET_PREFIX+"v"+remote+"/") &&
                    uri.endsWith("/"+expected)) {
                    return new Result("Nouvelle version "+remote+" disponible.",new Release(remote,uri,name));
                }
            }
            return new Result("APK officiel absent de la release "+remote,null);
        } catch(Exception e) { return new Result("Contrôle indisponible : "+e.getClass().getSimpleName(),null); }
        finally { if(c!=null)c.disconnect(); }
    }

    public long download(Release release) {
        if(release==null ||
            !release.fileName.matches("CHK-Paiements-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk") ||
            !release.url.startsWith(ASSET_PREFIX+"v"+release.version+"/"))
            throw new IllegalArgumentException("Release non reconnue");
        File folder=app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(folder==null) throw new IllegalStateException("Stockage indisponible");
        DownloadManager dm=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);
        long previous=prefs.getLong("download_id",-1);
        if(previous>=0)dm.remove(previous);
        File target=new File(folder,release.fileName);
        if(target.exists()&&!target.delete())throw new IllegalStateException("Ancien APK non supprimé");
        DownloadManager.Request rq=new DownloadManager.Request(Uri.parse(release.url));
        rq.setTitle("CHK Paiements "+release.version);
        rq.setDescription("SkillMajAuto · téléchargement de la version officielle");
        rq.setMimeType("application/vnd.android.package-archive");
        rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        rq.setDestinationInExternalFilesDir(app,Environment.DIRECTORY_DOWNLOADS,release.fileName);
        long id=dm.enqueue(rq);
        prefs.edit().putLong("download_id",id).putString("file",release.fileName).apply();
        return id;
    }

    public boolean ownsDownload(long id) { return id>=0 && prefs.getLong("download_id",-1)==id; }
    public File verifiedDownload() {
        long id=prefs.getLong("download_id",-1);
        if(id<0)return null;
        try(Cursor row=((DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE))
                .query(new DownloadManager.Query().setFilterById(id))) {
            if(row==null||!row.moveToFirst()||row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    != DownloadManager.STATUS_SUCCESSFUL) return null;
            String name=prefs.getString("file","");
            if(!name.matches("CHK-Paiements-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk"))return null;
            File root=app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(root==null)return null;
            File apk=new File(root,name).getCanonicalFile();
            if(!root.getCanonicalFile().equals(apk.getParentFile())||!apk.isFile()||apk.length()<10000)return null;
            return compatible(apk)?apk:null;
        }catch(Exception e){return null;}
    }

    @SuppressWarnings("deprecation")
    public boolean compatible(File apk) {
        try {
            PackageManager manager=app.getPackageManager();
            int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
            PackageInfo current=manager.getPackageInfo(app.getPackageName(),flags);
            PackageInfo candidate=manager.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
            if(candidate==null||!app.getPackageName().equals(candidate.packageName))return false;
            if(candidate.getLongVersionCode()<=current.getLongVersionCode())return false;
            Signature[] currentSigners=signers(current), nextSigners=signers(candidate);
            return currentSigners!=null&&nextSigners!=null&&currentSigners.length>0&&Arrays.equals(currentSigners,nextSigners);
        }catch(Exception e){return false;}
    }
    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        if(Build.VERSION.SDK_INT>=28)return info.signingInfo==null?null:info.signingInfo.getApkContentsSigners();
        return info.signatures;
    }
    public void install(File apk, android.app.Activity activity) {
        if(apk==null||!compatible(apk))throw new IllegalArgumentException("APK non vérifié ou signature différente");
        if(Build.VERSION.SDK_INT>=26&&!app.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+app.getPackageName())));
            return;
        }
        Uri uri=FileProvider.getUriForFile(app,app.getPackageName()+".updates",apk);
        Intent intent=new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri,"application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
    }
}
