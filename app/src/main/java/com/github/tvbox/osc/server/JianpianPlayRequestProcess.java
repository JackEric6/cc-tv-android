package com.github.tvbox.osc.server;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** 播放时向荐片动态换取指定集数、指定线路的 HLS 地址。 */
final class JianpianPlayRequestProcess implements RequestProcess {
    private static final String PREFIX = "/jianpian/play.m3u8";

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
            String url = JianpianApi.get().access(value(params, "work_id"),
                    value(params, "episode_key"), value(params, "episode_index"),
                    value(params, "line_key"));
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.REDIRECT, NanoHTTPD.MIME_PLAINTEXT, "");
            response.addHeader("Location", url);
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (Throwable throwable) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT, "荐片播放地址获取失败: " + throwable.getMessage());
        }
    }

    private static String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }
}
