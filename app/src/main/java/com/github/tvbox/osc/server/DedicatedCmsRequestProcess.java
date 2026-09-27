package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.google.gson.JsonObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 按资源站选择 CMS 适配器的本地入口。
 *
 * <p>兼容旧配置的 {@code /cms?source=} 也由这里接管；新配置可以使用
 * {@code /cms/&lt;sourceKey&gt;?source=}，这样适配器选择不再只依赖上游域名。</p>
 */
final class DedicatedCmsRequestProcess implements RequestProcess {
    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return CmsAdapterRouter.isDedicatedPath(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session,
                                         String fileName,
                                         Map<String, String> params,
                                         Map<String, String> files) {
        Map<String, String> requestParams = params == null
                ? new HashMap<String, String>() : new HashMap<>(params);
        String sourceKey = sourceKey(fileName, requestParams);
        String source = value(requestParams, "source");
        CmsAdapter adapter = CmsAdapterRouter.resolve(sourceKey, source);
        // sourceKey 仅用于本地路由，不应被转发给第三方 CMS。
        requestParams.remove("sourceKey");
        return adapter.doResponse(session, fileName, requestParams, files);
    }

    static String sourceKey(String fileName, Map<String, String> params) {
        String explicit = value(params, "sourceKey");
        // 不使用 Android TextUtils:纯 JVM 单测的 Android 桩会把空串判成非空,
        // 从而让 /cms/{source} 路由被空的 sourceKey 提前截断。
        if (explicit != null && !explicit.isEmpty()) return explicit;
        if (fileName == null) return "";
        String prefix = "/cms/";
        if (!fileName.startsWith(prefix)) return "";
        String tail = fileName.substring(prefix.length());
        int slash = tail.indexOf('/');
        if (slash >= 0) tail = tail.substring(0, slash);
        try {
            return URLDecoder.decode(tail, StandardCharsets.UTF_8.name());
        } catch (Throwable ignored) {
            return tail;
        }
    }

    private static String value(Map<String, String> params, String key) {
        if (params == null) return "";
        String value = params.get(key);
        return value == null ? "" : value.trim();
    }
}
