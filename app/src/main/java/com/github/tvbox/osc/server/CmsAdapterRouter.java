package com.github.tvbox.osc.server;

import android.text.TextUtils;

import java.util.Locale;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;

/**
 * CMS 源适配器选择器。
 *
 * <p>这里保留一个默认兼容适配器，避免配置里新增资源站后请求直接失效；
 * 对已知协议差异较大的站点先给出稳定的独立类型，后续可逐个替换实现，
 * 不需要复制几十份相同的请求处理类。</p>
 */
final class CmsAdapterRouter {
    static final String STANDARD_JSON = "standard-json";
    static final String XML_RSS = "xml-rss";
    static final String YPARSE_JSON = "yparse-json";
    static final String MAOTAI_JSON = "maotai-json";
    static final String IKUN_JSON = "ikun-json";

    private CmsAdapterRouter() {
    }

    static CmsAdapter resolve(String sourceKey, String sourceUrl) {
        final String adapterId = adapterId(sourceKey, sourceUrl);
        return new LegacyCmsAdapter(adapterId);
    }

    /** 公开给单测和日志的纯路由函数，不发起网络请求。 */
    static String adapterId(String sourceKey, String sourceUrl) {
        String key = lower(sourceKey);
        String host = host(sourceUrl);
        if ("xgzy".equals(key) || host.contains("xgzyapi")) return "xgzy";
        if ("yzzy".equals(key) || "m766".equals(key)
                || "api.yzzy-api.com".equals(host) || "m766.com".equals(host)) {
            return XML_RSS;
        }
        if ("yparse".equals(key) || "api.yparse.com".equals(host)) return YPARSE_JSON;
        if ("maotai".equals(key) || host.contains("maotai999")) return MAOTAI_JSON;
        if ("ikunzy".equals(key) || host.contains("ikunzyapi")) return IKUN_JSON;
        return STANDARD_JSON;
    }

    static boolean isDedicatedPath(String fileName) {
        if (TextUtils.isEmpty(fileName)) return false;
        return fileName.equals("/cms") || fileName.startsWith("/cms/");
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String host(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            return HttpUrl.get(value).host().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 兼容适配器只负责把请求交给当前成熟的 CMS 引擎；其外层已经按站点选择，
     * 后续站点专属实现可直接替换本类而无需改 RemoteServer。
     */
    private static final class LegacyCmsAdapter implements CmsAdapter {
        private final String id;

        LegacyCmsAdapter(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session,
                                             String fileName,
                                             java.util.Map<String, String> params,
                                             java.util.Map<String, String> files) {
            // 兼容路径只在专属适配器尚未注册时使用，保证旧配置行为不变。
            return new CmsRequestProcess().doResponse(session, "/cms", params, files);
        }
    }
}
