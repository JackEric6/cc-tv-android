package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.server.Cz4kBrowserSession;

/** 厂长站点正常浏览器会话页：执行站点 JavaScript，并等待其自身验证完成。 */
public class Cz4kBrowserActivity extends BaseActivity {
    private static final String EXTRA_URL = "url";
    private static final long CHECK_DELAY_MS = 1200L;
    private static final long MAX_WAIT_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private long openedAt;
    private boolean finished;

    public static void open(Context context, String url) {
        if (context == null) return;
        Intent intent = new Intent(context, Cz4kBrowserActivity.class)
                .putExtra(EXTRA_URL, url);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected int getLayoutResID() {
        return android.R.layout.simple_list_item_1;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void init() {
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUserAgentString(Cz4kBrowserSession.USER_AGENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                openedAt = openedAt == 0L ? System.currentTimeMillis() : openedAt;
                handler.removeCallbacks(checkPage);
                handler.postDelayed(checkPage, CHECK_DELAY_MS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handler.removeCallbacks(checkPage);
                handler.postDelayed(checkPage, CHECK_DELAY_MS);
            }
        });

        openedAt = System.currentTimeMillis();
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.trim().isEmpty()) url = "https://www.cz4k.com/";
        setTitle(url.toLowerCase(java.util.Locale.ROOT).contains("gaze.red")
                ? "Gaze 验证" : "厂长资源");
        webView.loadUrl(url);
    }

    private final Runnable checkPage = new Runnable() {
        @Override
        public void run() {
            if (finished || webView == null) return;
            webView.evaluateJavascript(
                    "(function(){return JSON.stringify({title:document.title||''," 
                            + "text:(document.body&&document.body.innerText)||''});})()",
                    value -> inspectPage(value));
        }
    };

    private void inspectPage(String value) {
        if (finished || webView == null) return;
        String text = decodeJavascriptString(value);
        if (Cz4kBrowserSession.isChallengeText(text)) {
            if (System.currentTimeMillis() - openedAt < MAX_WAIT_MS) {
                handler.postDelayed(checkPage, CHECK_DELAY_MS);
            }
            return;
        }
        String cookies = CookieManager.getInstance().getCookie(webView.getUrl());
        if (text.length() < 40 && System.currentTimeMillis() - openedAt < MAX_WAIT_MS) {
            handler.postDelayed(checkPage, CHECK_DELAY_MS);
            return;
        }
        finished = true;
        Cz4kBrowserSession.markReady(cookies);
        finish();
    }

    private String decodeJavascriptString(String value) {
        if (value == null || "null".equals(value)) return "";
        try {
            return new org.json.JSONArray("[" + value + "]").getString(0);
        } catch (Throwable ignored) {
            return value;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (!finished) Cz4kBrowserSession.cancelWarmup();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
