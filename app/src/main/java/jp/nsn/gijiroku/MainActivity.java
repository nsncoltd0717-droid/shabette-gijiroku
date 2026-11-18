package jp.nsn.gijiroku;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * しゃべって議事録 Android版。
 * 画面はWeb版と同じHTML（assets/index.html）を表示し、
 * 音声認識・ファイル保存・コピー・更新の確認はAndroidの機能で行う。
 */
public class MainActivity extends Activity {
    private static final int REQ_MIC = 1;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private SpeechRecognizer recognizer;
    private boolean wantListening = false;
    private boolean pendingStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopListening(true);
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    /* ---------- JavaScript から呼ぶ機能 ---------- */
    private class Bridge {
        @JavascriptInterface public void startVoice() { runOnUiThread(MainActivity.this::requestStart); }
        @JavascriptInterface public void stopVoice() { runOnUiThread(() -> stopListening(false)); }
        @JavascriptInterface public String versionName() { return pkg() == null ? "" : pkg().versionName; }
        @JavascriptInterface public long versionCode() { return pkg() == null ? 0 : pkg().getLongVersionCode(); }
        @JavascriptInterface public void openUrl(String url) {
            if (url != null && url.startsWith("https://")) runOnUiThread(() -> openExternal(url));
        }
        @JavascriptInterface public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("議事録", text));
            });
        }
        @JavascriptInterface public String saveFile(String name, String text) {
            try {
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, "text/markdown");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/しゃべって議事録");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException();
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> share(safe, text));
                return "「ダウンロード/しゃべって議事録」に保存しました。続けて共有先も選べます。";
            } catch (Exception e) {
                return "保存できませんでした。端末の空き容量を確認してください。";
            }
        }
    }

    private PackageInfo pkg() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0); }
        catch (PackageManager.NameNotFoundException e) { return null; }
    }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
    }

    private void share(String name, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, name);
        i.putExtra(Intent.EXTRA_TEXT, text);
        try { startActivity(Intent.createChooser(i, "議事録を共有")); } catch (Exception ignored) { }
    }

    /* ---------- 音声認識 ---------- */
    private void requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("note", "この端末では音声認識が使えません。Googleアプリを最新にするか、キーボードのマイクで入力してください。");
            js("state", "idle", "");
            return;
        }
        wantListening = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        js("state", "listening", "");
        listenOnce();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_MIC) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (ok && pendingStart) requestStart();
        else {
            js("note", "マイクが許可されていません。設定 → アプリ → しゃべって議事録 → 権限 からマイクを許可してください。");
            js("state", "idle", "");
        }
        pendingStart = false;
    }

    private void listenOnce() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        recognizer.startListening(i);
    }

    private void stopListening(boolean quiet) {
        wantListening = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (recognizer != null) recognizer.cancel();
        if (!quiet) js("state", "idle", "stopped");
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) js("partial", r.get(0));
        }

        @Override public void onResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            js("partial", "");
            if (r != null && !r.isEmpty()) js("final", r.get(0));
            if (wantListening) listenOnce();   // 会議中は聞き取りを続ける
        }

        @Override public void onError(int error) {
            if (!wantListening) return;
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                web.postDelayed(() -> { if (wantListening) listenOnce(); }, 300);
                return;
            }
            String msg = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    ? "音声認識にはインターネット接続が必要です。"
                    : error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    ? "マイクが許可されていません。"
                    : "音声認識でエラーが起きました（" + error + "）。もう一度押してください。";
            wantListening = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            js("note", msg);
            js("state", "idle", "");
        }
    }

    /** window.__voice.<fn>(args...) を呼ぶ */
    private void js(String fn, String... args) {
        StringBuilder sb = new StringBuilder("window.__voice&&window.__voice.").append(fn).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(JSONObject.quote(args[i]));
        }
        sb.append(")");
        runOnUiThread(() -> web.evaluateJavascript(sb.toString(), null));
    }
}
