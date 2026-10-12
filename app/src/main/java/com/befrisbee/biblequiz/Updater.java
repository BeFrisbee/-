package com.befrisbee.biblequiz;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 업데이트 세 가지
 *  - 문제만 바뀜(questions): 퀴즈 파일을 몰래 받아 두었다가 다음에 켤 때 적용
 *  - 기능이 바뀜(feature): 퀴즈 파일을 받아 두고 금색 버튼으로 알림, 누르면 바로 적용
 *  - 앱이 바뀜(native): 금색 버튼으로 알림, 누르면 APK를 받아 설치 화면을 띄움
 * 결과는 퀴즈 화면의 window.BQUpdate 로 알려 준다.
 * APK는 같은 키로 서명된 것만 기존 앱 위에 설치된다.
 */
class Updater {
    private static final String LATEST = "https://api.github.com/repos/BeFrisbee/-/releases/latest";
    private static final String DL = "https://github.com/BeFrisbee/-/releases/download/";

    private final Activity act;
    private final WebView web;
    private volatile boolean busy = false;
    private File pending;   // 설치 허용을 받으러 설정에 다녀오는 동안 기다리는 APK

    Updater(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    // ── 버전 ──────────────────────────────────────────
    static long apkCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static int nativeLevel(Context c) {
        return c.getResources().getInteger(R.integer.native_level);
    }

    long currentCode() { return apkCode(act); }

    String currentName() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 지금 쓰고 있는 퀴즈 파일의 버전 (앱에 든 것과 받아 둔 것 중 새것) */
    static long contentVersion(Context c) {
        return Math.max(apkCode(c), readLong(new File(dir(c), "current.ver")));
    }

    // ── 받아 둔 퀴즈 파일 ─────────────────────────────
    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "web");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 앱을 켤 때: 문제만 바뀐 파일은 적용하고, 앱보다 오래된 파일은 지움. 쓸 파일을 돌려줌(없으면 null) */
    static File prepare(Context c) {
        File d = dir(c);
        File next = new File(d, "next.html"), nextInfo = new File(d, "next.json");
        try {
            if (next.exists() && nextInfo.exists()) {
                JSONObject j = new JSONObject(readText(nextInfo));
                if ("questions".equals(j.optString("kind"))) promote(c);
            }
        } catch (Exception ignored) { }
        File cur = new File(d, "current.html");
        long ver = readLong(new File(d, "current.ver"));
        if (readLongOr(nextInfo) <= Math.max(apkCode(c), ver)) { next.delete(); nextInfo.delete(); }
        if (cur.exists() && ver > apkCode(c)) return cur;
        cur.delete();
        new File(d, "current.ver").delete();
        return null;
    }

    /** 받아 둔 퀴즈 파일의 '바뀐 점' (지금 쓰는 경우에만) */
    static String contentNotes(Context c) {
        if (currentFile(c) == null) return "";
        try { return readText(new File(dir(c), "current.notes")).trim(); } catch (Exception e) { return ""; }
    }

    /** 지금 띄울 퀴즈 파일 (받아 둔 것이 앱에 든 것보다 새것일 때만) */
    static File currentFile(Context c) {
        File d = dir(c), cur = new File(d, "current.html");
        return cur.exists() && readLong(new File(d, "current.ver")) > apkCode(c) ? cur : null;
    }

    /** 받아 둔 다음 파일을 지금 파일로 */
    private static boolean promote(Context c) {
        File d = dir(c);
        File next = new File(d, "next.html"), nextInfo = new File(d, "next.json");
        try {
            JSONObject info = new JSONObject(readText(nextInfo));
            long ver = info.getLong("ver");
            File cur = new File(d, "current.html");
            cur.delete();
            if (!next.renameTo(cur)) return false;
            writeText(new File(d, "current.ver"), String.valueOf(ver));
            writeText(new File(d, "current.notes"), info.optString("notes", ""));
            nextInfo.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 기능 업데이트를 지금 적용하고 화면을 다시 띄움 */
    boolean applyContent() {
        if (!promote(act)) return false;
        act.runOnUiThread(() -> web.reload());
        return true;
    }

    boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    // ── 확인 ──────────────────────────────────────────
    void check() {
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(LATEST));
                String tag = rel.optString("tag_name", "");
                String name = tag.startsWith("v") ? tag.substring(1) : tag;
                long code = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
                String apk = null, quiz = null, metaUrl = null;
                long apkSize = 0;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String n = a.optString("name"), u = a.optString("browser_download_url");
                    if (!u.startsWith(DL)) continue;
                    if (n.endsWith(".apk")) { apk = u; apkSize = a.optLong("size"); }
                    else if (n.equals("quiz.html")) quiz = u;
                    else if (n.equals("quiz-meta.json")) metaUrl = u;
                }
                String body = rel.optString("body", "");
                String firstLine = (body.contains("\n") ? body.substring(0, body.indexOf('\n')) : body).trim();

                if (metaUrl == null) {   // 예전 방식 릴리스: 버전이 더 높으면 APK 업데이트
                    if (apk != null && code > currentCode()) info("native", code, name, firstLine, apk, apkSize);
                    else js("none", null);
                    return;
                }
                JSONObject meta = new JSONObject(get(metaUrl));
                long content = meta.optLong("content", code);
                if (meta.optInt("native", 0) > nativeLevel(act)) {
                    String n = meta.optString("nativeNotes", "");
                    if (apk != null) info("native", content, name, n.isEmpty() ? meta.optString("notes") : n, apk, apkSize);
                    else js("none", null);
                    return;
                }
                long own = contentVersion(act);
                if (content <= own || quiz == null) { js("none", null); return; }
                boolean feature = meta.optLong("featureSince", 0) > own;
                String notes = feature ? meta.optString("featureNotes", meta.optString("notes")) : meta.optString("notes");
                File d = dir(act), nextInfo = new File(d, "next.json");
                if (readLongOr(nextInfo) != content) {
                    File tmp = new File(d, "next.tmp");
                    String html = get(quiz);
                    // 받은 파일이 정말 퀴즈 파일인지 확인
                    if (html.length() < 200000 || !html.contains("성경 퀴즈") || !html.contains("const OT=")) throw new Exception("bad");
                    writeText(tmp, html);
                    File next = new File(d, "next.html");
                    next.delete();
                    if (!tmp.renameTo(next)) throw new Exception("rename");
                    JSONObject j = new JSONObject();
                    j.put("ver", content);
                    j.put("kind", feature ? "feature" : "questions");
                    j.put("notes", notes);
                    writeText(nextInfo, j.toString());
                } else if (!feature) {   // 이미 받아 둔 게 있으면 종류만 맞춤
                    JSONObject j = new JSONObject(readText(nextInfo));
                    j.put("kind", "questions");
                    j.put("notes", notes);
                    writeText(nextInfo, j.toString());
                }
                if (feature) info("feature", content, name, notes, null, 0);
                else js("none", null);   // 문제만 바뀐 건 다음에 켤 때 조용히 적용
            } catch (Exception e) {
                js("fail", JSONObject.quote("check"));
            }
        }).start();
    }

    private void info(String kind, long code, String name, String notes, String url, long size) throws Exception {
        JSONObject info = new JSONObject();
        info.put("kind", kind);
        info.put("code", code);
        info.put("name", name);
        info.put("notes", notes);
        if (url != null) info.put("url", url);
        info.put("size", size);
        js("info", info.toString());
    }

    // ── APK 받기 → 확인 → 설치 화면 ──────────────────
    void download(String url, long expectCode) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                File d = new File(act.getCacheDir(), "update");
                if (!d.exists()) d.mkdirs();
                File f = new File(d, "update.apk");
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

    // ── 도우미 ────────────────────────────────────────
    private void js(String kind, String arg) {
        final String code = "window.BQUpdate&&window.BQUpdate." + kind + "(" + (arg == null ? "" : arg) + ")";
        act.runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private static long readLong(File f) {
        try { return Long.parseLong(readText(f).trim()); } catch (Exception e) { return 0; }
    }

    private static long readLongOr(File info) {
        try { return new JSONObject(readText(info)).getLong("ver"); } catch (Exception e) { return 0; }
    }

    private static String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeText(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
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
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
