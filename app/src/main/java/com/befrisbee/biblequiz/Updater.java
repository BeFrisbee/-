package com.befrisbee.biblequiz;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 앱 안 업데이트: GitHub 릴리스에서 새 버전을 확인하고, APK를 받아 안드로이드 설치 화면을 띄운다.
 * 결과는 퀴즈 화면의 window.BQUpdate 로 알려 준다.
 * 같은 키로 서명된 APK만 기존 앱 위에 설치되므로, 다른 APK가 업데이트로 깔리지는 않는다.
 */
class Updater {
    private static final String LATEST = "https://api.github.com/repos/BeFrisbee/-/releases/latest";

    private final Activity act;
    private final WebView web;
    private volatile boolean busy = false;
    private File pending;   // 설치 허용을 받으러 설정에 다녀오는 동안 기다리는 APK

    Updater(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    long currentCode() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    String currentName() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    /** 최신 릴리스 확인. 새 버전이면 info, 아니면 none, 실패하면 fail 로 알림 */
    void check() {
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(LATEST));
                String tag = rel.optString("tag_name", "");
                long code = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
                String apk = null;
                long size = 0;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url");
                        size = a.optLong("size");
                        break;
                    }
                }
                if (apk == null || code <= currentCode()) {
                    js("none", null);
                    return;
                }
                String body = rel.optString("body", "");
                String notes = body.contains("\n") ? body.substring(0, body.indexOf('\n')).trim() : body.trim();
                JSONObject info = new JSONObject();
                info.put("code", code);
                info.put("name", tag.startsWith("v") ? tag.substring(1) : tag);
                info.put("notes", notes);
                info.put("url", apk);
                info.put("size", size);
                js("info", info.toString());
            } catch (Exception e) {
                js("fail", JSONObject.quote("check"));
            }
        }).start();
    }

    /** APK 받기 → 확인 → 설치 화면 */
    void download(String url, long expectCode) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                File dir = new File(act.getCacheDir(), "update");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "update.apk");
                c = open(url);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int p = (int) (got * 100 / total);
                            if (p != last) { last = p; js("progress", String.valueOf(p)); }
                        }
                    }
                }
                // 받은 파일이 정말 이 앱의 새 버전인지 확인
                PackageInfo pi = act.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                long code = pi == null ? -1 : (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
                if (pi == null || !act.getPackageName().equals(pi.packageName) || code < expectCode) {
                    f.delete();
                    js("fail", JSONObject.quote("bad"));
                    return;
                }
                js("done", null);
                act.runOnUiThread(() -> install(f));
            } catch (Exception e) {
                js("fail", JSONObject.quote("download"));
            } finally {
                if (c != null) c.disconnect();
                busy = false;
            }
        }).start();
    }

    private void install(File f) {
        if (!canInstall()) {
            pending = f;
            js("perm", null);
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (ActivityNotFoundException e) {
                js("fail", JSONObject.quote("perm"));
            }
            return;
        }
        Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException e) {
            js("fail", JSONObject.quote("install"));
        }
    }

    /** 설치 허용 화면에서 돌아왔을 때 */
    void onResume() {
        if (pending == null) return;
        if (canInstall()) {
            File f = pending;
            pending = null;
            install(f);
        } else {
            pending = null;
            js("fail", JSONObject.quote("perm"));
        }
    }

    private void js(String kind, String arg) {
        final String code = "window.BQUpdate&&window.BQUpdate." + kind + "(" + (arg == null ? "" : arg) + ")";
        act.runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private static HttpURLConnection open(String url) throws Exception {
        // GitHub 다운로드는 다른 주소로 넘겨 주므로 직접 따라감
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "bible-quiz-app");
            c.setRequestProperty("Accept", url.startsWith("https://api.github.com") ? "application/vnd.github+json" : "*/*");
            int rc = c.getResponseCode();
            if (rc >= 300 && rc < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || !loc.startsWith("https://")) throw new Exception("redirect");
                url = loc;
                continue;
            }
            if (rc != 200) {
                c.disconnect();
                throw new Exception("http " + rc);
            }
            return c;
        }
        throw new Exception("too many redirects");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
