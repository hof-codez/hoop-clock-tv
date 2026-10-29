package com.hof.hoopclock;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Full-screen Hoop Clock for Android TV.
 * Loads the live Netlify site so updates show up without reinstalling.
 * Falls back to the copy bundled in assets when the TV is offline.
 * Every key press goes straight to the page: no browser pointer.
 */
public class MainActivity extends Activity {

    private static final String REMOTE_URL = "https://hof-hoop-clock.netlify.app/";
    private static final String LOCAL_URL = "file:///android_asset/index.html";

    private WebView web;
    private boolean usingLocal = false;
    private long lastBackPress = 0;
    private TextToSpeech tts;
    private volatile boolean ttsOk = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // The TV's own speech engine, so the clock can say player names and recaps.
        startTts(null);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(16, 20, 26));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        // Lets the clock close the app and use the TV's speech engine.
        web.addJavascriptInterface(new AppBridge(), "HoopApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !usingLocal) {
                    usingLocal = true;
                    view.loadUrl(LOCAL_URL);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        setContentView(web);
        hideSystemBars();
        web.requestFocus();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(REMOTE_URL);
        }
    }

    private void hideSystemBars() {
        web.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Back: let the clock close Setup or the mode picker first.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) handleBack();
            return true;
        }
        // Keep focus on the page so every controller press reaches it.
        if (!web.hasFocus()) web.requestFocus();
        return super.dispatchKeyEvent(event);
    }

    private void handleBack() {
        web.evaluateJavascript("(window.hoopBack && window.hoopBack()) ? '1' : '0'", value -> {
            if ("\"1\"".equals(value)) return;
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                finishAndRemoveTask();
            } else {
                lastBackPress = now;
                Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private volatile String ttsInfo = "starting";
    private boolean triedGoogle = false;

    /** Start the default engine; if it fails, try Google's engine by name once. */
    private void startTts(String enginePkg) {
        TextToSpeech.OnInitListener listener = status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.US);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    r = tts.setLanguage(Locale.getDefault());
                }
                ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                ttsInfo = (ttsOk ? "ready" : "no English voice") + " (" + tts.getDefaultEngine() + ")";
            } else {
                ttsOk = false;
                ttsInfo = "engine failed to start" + (enginePkg != null ? " (" + enginePkg + ")" : "");
                if (!triedGoogle) {
                    triedGoogle = true;
                    try { if (tts != null) tts.shutdown(); } catch (Exception e) { }
                    startTts("com.google.android.tts");
                }
            }
        };
        tts = enginePkg == null ? new TextToSpeech(this, listener) : new TextToSpeech(this, listener, enginePkg);
    }

    private class AppBridge {
        @JavascriptInterface
        public String ttsStatus() {
            return ttsInfo;
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(MainActivity.this::finishAndRemoveTask);
        }

        @JavascriptInterface
        public boolean ttsReady() {
            return ttsOk;
        }

        @JavascriptInterface
        public void speak(String text, float rate) {
            if (!ttsOk || text == null) return;
            tts.setSpeechRate(rate > 0 ? rate : 1.0f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hoop");
        }

        @JavascriptInterface
        public void stop() {
            if (tts != null) tts.stop();
        }

        /** English voices on this TV, as JSON: [{name, locale, quality, network}] */
        @JavascriptInterface
        public String voices() {
            JSONArray out = new JSONArray();
            if (!ttsOk) return out.toString();
            try {
                for (Voice v : tts.getVoices()) {
                    if (v.getLocale() == null || !"en".equals(v.getLocale().getLanguage())) continue;
                    if (v.getFeatures() != null && v.getFeatures().contains("notInstalled")) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("locale", v.getLocale().toString());
                    o.put("quality", v.getQuality());
                    o.put("network", v.isNetworkConnectionRequired());
                    out.put(o);
                }
            } catch (Exception e) { /* return what we have */ }
            return out.toString();
        }

        @JavascriptInterface
        public boolean setVoice(String name) {
            if (!ttsOk || name == null) return false;
            try {
                for (Voice v : tts.getVoices()) {
                    if (name.equals(v.getName())) { tts.setVoice(v); return true; }
                }
            } catch (Exception e) { /* keep the current voice */ }
            return false;
        }

        @JavascriptInterface
        public void setPitch(float pitch) {
            if (ttsOk) tts.setPitch(pitch > 0 ? pitch : 1.0f);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        web.destroy();
        super.onDestroy();
    }
}
