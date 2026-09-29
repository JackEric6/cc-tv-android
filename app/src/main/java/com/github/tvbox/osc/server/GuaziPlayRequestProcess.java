package com.github.tvbox.osc.server;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** 播放时实时向瓜子换取动态 m3u8，避免详情页缓存失效地址。 */
final class GuaziPlayRequestProcess implements RequestProcess {
    private static final String PREFIX = "/guazi/play.m3u8";

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return PREFIX.equals(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session,
                                         String fileName,
                                         Map<String, String> params,
                                         Map<String, String> files) {
        try {
            String url = GuaziApi.get().play(value(params, "vod_id"),
                    value(params, "domain_type"), value(params, "vurl_id"),
                    value(params, "resolution"), value(params, "type"));
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.REDIRECT, NanoHTTPD.MIME_PLAINTEXT, "");
            response.addHeader("Location", url);
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (Throwable throwable) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT, "瓜子播放地址获取失败: " + throwable.getMessage());
        }
    }

    private static String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }
}
