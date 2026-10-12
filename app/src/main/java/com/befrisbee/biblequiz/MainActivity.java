package com.befrisbee.biblequiz;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.AtomicFile;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 퀴즈 HTML을 앱 안에서 그대로 띄우는 화면. 퀴즈는 인터넷 없이 동작하고, 성경 본문 링크는 휴대폰 브라우저로 연다.
 *  인터넷은 새 버전 확인과 받기에만 쓴다. */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";

    private static final int PICK_FILE = 41;

    private WebView web;
    private long lastBack = 0;
    private ValueCallback<Uri[]> pickCallback;
    private AtomicFile record;
    private Updater updater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 기록 파일: 앱의 files 폴더에 두어 안드로이드 자동 백업(구글 드라이브)에 함께 실림
        record = new AtomicFile(new File(getFilesDir(), "bq-record.json"));

        web = new WebView(this);
        web.setBackgroundColor(getResources().getColor(R.color.paper, getTheme()));
        setContentView(web);

        // 받아 둔 퀴즈 파일이 앱에 든 것보다 새것이면 그것을 띄움 (문제·기능 업데이트)
        Updater.prepare(this);
        final WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(this);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", path -> {
                    if ("index.html".equals(path)) {
                        File f = Updater.currentFile(MainActivity.this);
                        if (f != null) {
                            try {
                                return new WebResourceResponse("text/html", "utf-8", new FileInputStream(f));
                            } catch (IOException ignored) { }
                        }
                    }
                    return assets.handle(path);
                })
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // 최고 점수·마지막 범위 저장
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);             // 화면 배치가 깨지지 않도록 글자 크기는 퀴즈 안에서 이미 크게 잡음

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "링크를 열 앱이 없어요", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        // 기록 불러오기: 파일 고르기 창 열기
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "기록 파일 고르기"), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    pickCallback = null;
                    Toast.makeText(MainActivity.this, "파일을 고를 앱이 없어요", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        updater = new Updater(this, web);
        web.addJavascriptInterface(new Bridge(), "BQNative");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START);
        }
    }

    /** 퀴즈 화면(JS)에서 부르는 기록 저장·읽기·보내기 */
    private class Bridge {
        @JavascriptInterface
        public void save(String json) {
            synchronized (MainActivity.this) {
                FileOutputStream out = null;
                try {
                    out = record.startWrite();
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    record.finishWrite(out);
                } catch (IOException e) {
                    if (out != null) record.failWrite(out);
                }
            }
        }

        @JavascriptInterface
        public String load() {
            synchronized (MainActivity.this) {
                try {
                    return new String(record.readFully(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            }
        }

        @JavascriptInterface
        public void share(String name, String json) {
            try {
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name.replaceAll("[/\\\\]", "_"));
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/json");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                final Intent chooser = Intent.createChooser(send, "기록 보내기");
                runOnUiThread(() -> startActivity(chooser));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "기록 파일을 만들지 못했어요", Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public String appVersion() {
            long content = Updater.contentVersion(MainActivity.this);
            return "{\"code\":" + updater.currentCode() + ",\"name\":\"" + updater.currentName()
                    + "\",\"content\":" + content + ",\"contentName\":\"1.0." + content
                    + "\",\"native\":" + Updater.nativeLevel(MainActivity.this) + "}";
        }

        @JavascriptInterface
        public boolean canInstall() {
            return updater.canInstall();
        }

        @JavascriptInterface
        public String contentNotes() {
            return Updater.contentNotes(MainActivity.this);
        }

        @JavascriptInterface
        public boolean applyContent() {
            return updater.applyContent();
        }

        @JavascriptInterface
        public void checkUpdate() {
            updater.check();
        }

        @JavascriptInterface
        public void downloadUpdate(String url, long code) {
            if (url != null && url.startsWith("https://github.com/BeFrisbee/-/releases/download/")) updater.download(url, code);
        }

        @JavascriptInterface
        public void shareText(String text) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            final Intent chooser = Intent.createChooser(send, "신고 보내기");
            runOnUiThread(() -> startActivity(chooser));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE) {
            if (pickCallback != null) {
                pickCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pickCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updater != null) updater.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    /** 실수로 뒤로 가기를 눌러 풀던 판이 날아가지 않게, 두 번 눌러야 종료 */
    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            Toast.makeText(this, "한 번 더 누르면 종료돼요", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
