package com.github.tvbox.osc.server;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 厂长站点的正常浏览器会话。
 *
 * <p>雷池验证依赖浏览器 JavaScript 和 Cookie。这里仅保存用户通过正常 WebView
 * 页面后得到的会话信息，供同一进程内的网页适配器继续请求；不生成、伪造或修改验证凭据。</p>
 */
public final class Cz4kBrowserSession {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
                    + "Chrome/120 Mobile Safari/537.36";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> WAITERS = new ArrayList<>();
    private static String cookieHeader = "";
    private static String sessionHost = "";
    private static boolean ready;
    private static boolean warmupOpening;
    private static WebView webView;
    private static ViewGroup webViewHost;
    private static long openedAt;
    private static boolean finished;

    private static final long CHECK_DELAY_MS = 1200L;
    private static final long MAX_WAIT_MS = 60_000L;

    private Cz4kBrowserSession() {
    }

    public static boolean isReady() {
        return ready && isHost(sessionHost, "cz4k.com");
    }

    public static String getCookieHeader() {
        return isReady() ? cookieHeader : "";
    }

    public static boolean isReadyForUrl(String url) {
        return ready && !TextUtils.isEmpty(cookieHeader)
                && sameHost(url, sessionHost);
    }

    public static String getCookieHeaderForUrl(String url) {
        return isReadyForUrl(url) ? cookieHeader : "";
    }

    public static String userAgentForUrl(String url) {
        return USER_AGENT;
    }

    public static boolean isChallengeText(String text) {
        if (text == null) return false;
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        String[] markers = {
                "雷池", "safeline", "安全检查", "访问验证", "请稍候",
                "正在验证", "验证中", "验证失败", "点击完成验证",
                "checking your browser", "verifying you are human", "challenge",
                "403 forbidden", "access denied", "请输入验证码", "人机验证"
        };
        for (String marker : markers) {
            if (normalized.contains(marker.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * 验证页判定必须同时考虑页面文案和站点的验证组件，不能用正文长度判断。
     */
    public static boolean isValidationPage(String title, String text, String url,
                                           boolean hasChallengeWidget, String widgetState) {
        if (hasChallengeWidget) return true;
        String combined = (title == null ? "" : title) + " "
                + (text == null ? "" : text);
        if (isChallengeText(combined)) return true;
        String normalizedTitle = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        return normalizedTitle.equals("访问验证")
                || normalizedTitle.equals("安全验证")
                || normalizedTitle.equals("verification")
                || normalizedTitle.equals("access verification");
    }

    /**
     * 读取当前页面及厂长站点根域的 Cookie。
     * 调用方必须在主线程执行此方法。
     */
    public static String collectCookies(String url) {
        CookieManager manager = CookieManager.getInstance();
        try {
            manager.flush();
        } catch (Throwable ignored) {
            // 老版本 WebView 可能不支持同步 flush。
        }
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        addCookies(cookies, manager.getCookie(url));
        String host = hostOf(url);
        if (isHost(host, "cz4k.com")) {
            addCookies(cookies, manager.getCookie("https://cz4k.com/"));
            addCookies(cookies, manager.getCookie("https://www.cz4k.com/"));
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    /**
     * 在当前 Activity 的窗口中后台运行正常 WebView；已有会话时直接回调。
     *
     * <p>WebView 仍然使用真实的 Activity 上下文并挂载到窗口，因此站点 JavaScript、
     * Cookie 和雷池验证可以正常执行；容器只有 1x1 像素且完全透明，不会切换前台页面。</p>
     */
    public static void ensureReady(Activity activity, String url, Runnable ready) {
        if (ready == null) return;
        if (isReadyForUrl(url)) {
            MAIN.post(ready);
            return;
        }
        if (activity == null || activity.isFinishing()) {
            cancelWarmup();
            return;
        }
        synchronized (WAITERS) {
            WAITERS.add(ready);
            if (warmupOpening) return;
            warmupOpening = true;
        }
        MAIN.post(() -> startBackgroundWarmup(activity, url));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void startBackgroundWarmup(Activity activity, String url) {
        if (activity == null || activity.isFinishing()
                || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1
                && activity.isDestroyed())) {
            cancelWarmup();
            return;
        }
        if (!isWarmupOpening()) return;

        finished = false;
        openedAt = System.currentTimeMillis();
        sessionHost = hostOf(url);
        FrameLayout hiddenHost = new FrameLayout(activity);
        hiddenHost.setAlpha(0.0f);
        hiddenHost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hiddenHost.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        WebView hiddenWebView = new WebView(activity);
        hiddenHost.addView(hiddenWebView, new FrameLayout.LayoutParams(1, 1));
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            cancelWarmup();
            return;
        }
        ((ViewGroup) decor).addView(hiddenHost, new ViewGroup.LayoutParams(1, 1));
        webViewHost = hiddenHost;
        webView = hiddenWebView;

        WebSettings settings = hiddenWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUserAgentString(USER_AGENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWebView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        hiddenWebView.setWebChromeClient(new WebChromeClient());
        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String pageUrl, Bitmap favicon) {
                super.onPageStarted(view, pageUrl, favicon);
                MAIN.removeCallbacks(checkPage);
                MAIN.postDelayed(checkPage, CHECK_DELAY_MS);
            }

            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                super.onPageFinished(view, pageUrl);
                MAIN.removeCallbacks(checkPage);
                MAIN.postDelayed(checkPage, CHECK_DELAY_MS);
            }
        });

        String loadUrl = TextUtils.isEmpty(url) ? "https://www.cz4k.com/" : url;
        hiddenWebView.loadUrl(loadUrl);
    }

    private static final Runnable checkPage = () -> {
        WebView currentWebView = webView;
        if (finished || currentWebView == null) return;
        currentWebView.evaluateJavascript(
                "(function(){var w=document.querySelector('cap-widget');"
                        + "return JSON.stringify({title:document.title||'',"
                        + "text:(document.body&&document.body.innerText)||'',"
                        + "url:location.href||'',"
                        + "hasChallengeWidget:!!w,"
                        + "widgetState:w?(w.getAttribute('data-state')||''):''});})()",
                value -> inspectPage(currentWebView, value));
    };

    private static void inspectPage(WebView currentWebView, String value) {
        if (finished || currentWebView == null || currentWebView != webView) return;
        String state = decodeJavascriptString(value);
        String title = "";
        String text = "";
        String pageUrl = currentWebView.getUrl();
        boolean hasChallengeWidget = false;
        String widgetState = "";
        try {
            org.json.JSONObject json = new org.json.JSONObject(state);
            title = json.optString("title", "");
            text = json.optString("text", "");
            pageUrl = json.optString("url", pageUrl);
            hasChallengeWidget = json.optBoolean("hasChallengeWidget", false);
            widgetState = json.optString("widgetState", "");
        } catch (Throwable ignored) {
            text = state;
        }
        boolean validationPage = isValidationPage(title, text, pageUrl,
                hasChallengeWidget, widgetState);
        if (validationPage) {
            if (System.currentTimeMillis() - openedAt < MAX_WAIT_MS) {
                MAIN.postDelayed(checkPage, CHECK_DELAY_MS);
            }
            return;
        }
        String cookies = collectCookies(pageUrl);
        if (!markReady(cookies, true, pageUrl)
                && System.currentTimeMillis() - openedAt < MAX_WAIT_MS) {
            MAIN.postDelayed(checkPage, CHECK_DELAY_MS);
        }
    }

    private static String decodeJavascriptString(String value) {
        if (value == null || "null".equals(value)) return "";
        try {
            return new org.json.JSONArray("[" + value + "]").getString(0);
        } catch (Throwable ignored) {
            return value;
        }
    }

    /** 由 WebView 页面在正常加载完成后调用。返回 false 表示仍未满足完成条件。 */
    public static boolean markReady(String cookies) {
        return markReady(cookies, true, sessionHost);
    }

    public static boolean markReady(String cookies, boolean verifiedPage, String pageUrl) {
        if (!verifiedPage || !sameHost(pageUrl, sessionHost)) {
            return false;
        }
        destroyBackgroundWebView();
        synchronized (WAITERS) {
            if (!TextUtils.isEmpty(cookies)) cookieHeader = cookies;
            ready = true;
            warmupOpening = false;
            List<Runnable> callbacks = new ArrayList<>(WAITERS);
            WAITERS.clear();
            for (Runnable callback : callbacks) MAIN.post(callback);
        }
        return ready;
    }

    /** 用户返回或页面被取消时，允许下次搜索重新打开浏览器。 */
    public static void cancelWarmup() {
        destroyBackgroundWebView();
        synchronized (WAITERS) {
            warmupOpening = false;
            WAITERS.clear();
        }
    }

    public static boolean isWarmupOpening() {
        synchronized (WAITERS) {
            return warmupOpening;
        }
    }

    private static void destroyBackgroundWebView() {
        MAIN.removeCallbacks(checkPage);
        WebView currentWebView = webView;
        ViewGroup currentHost = webViewHost;
        webView = null;
        webViewHost = null;
        finished = true;
        if (currentWebView != null) {
            try {
                currentWebView.stopLoading();
                currentWebView.loadUrl("about:blank");
                currentWebView.destroy();
            } catch (Throwable ignored) {
                // 页面退出时 WebView 可能已由系统回收。
            }
        }
        if (currentHost != null) {
            ViewParentHelper.removeFromParent(currentHost);
        }
    }

    private static String hostOf(String url) {
        try {
            return android.net.Uri.parse(url).getHost().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean sameHost(String url, String expected) {
        String actual = hostOf(url);
        return !actual.isEmpty() && !expected.isEmpty()
                && (actual.equals(expected) || actual.endsWith("." + expected)
                || expected.endsWith("." + actual));
    }

    private static boolean isHost(String value, String expected) {
        return !TextUtils.isEmpty(value) && (value.equals(expected)
                || value.endsWith("." + expected));
    }

    private static void addCookies(LinkedHashMap<String, String> target, String header) {
        if (TextUtils.isEmpty(header)) return;
        for (String item : header.split(";")) {
            String cookie = item.trim();
            int separator = cookie.indexOf('=');
            if (separator <= 0) continue;
            String name = cookie.substring(0, separator).trim();
            String value = cookie.substring(separator + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) target.put(name, value);
        }
    }

    private static final class ViewParentHelper {
        private ViewParentHelper() {
        }

        static void removeFromParent(View view) {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }
    }
}
