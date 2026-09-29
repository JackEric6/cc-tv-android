package com.github.tvbox.osc.server;

import com.github.tvbox.osc.util.thunder.Jianpian;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 荐片 P2P 下载桥接。接口返回 ftp/xg 地址时先交给 P2P 引擎，
 * 再把引擎的本地 HTTP 地址返回给播放器或断点下载器。
 */
final class JianpianP2pRequestProcess implements RequestProcess {
    private static final String PREFIX = "/jianpian/p2p.m3u8";

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return PREFIX.equals(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session,
                                         String fileName,
                                         Map<String, String> params,
                                         Map<String, String> files) {
        String identity = Jianpian.downloadIdentity(params);
        try {
            // 暂停/继续时先复用内存中的原生 P2P 任务，避免动态 access 地址刷新后
            // 重新创建 .ybbk 任务。只有首次下载才向荐片重新换取真实地址。
            String playable = Jianpian.reuseDownloadSource(identity);
            if (playable.isEmpty()) {
                String url;
                try {
                    // 新版荐片对下载场景可能返回专用交付地址；若服务端仍只接受
                    // playback，则回退到官方客户端实际使用的 playback 语义。
                    url = JianpianApi.get().access(value(params, "work_id"),
                            value(params, "episode_key"), value(params, "episode_index"),
                            value(params, "line_key"), "download");
                } catch (Throwable ignored) {
                    url = JianpianApi.get().access(value(params, "work_id"),
                            value(params, "episode_key"), value(params, "episode_index"),
                            value(params, "line_key"), "playback");
                }
                playable = Jianpian.prepareDownloadSource(identity, url);
            }
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.REDIRECT, NanoHTTPD.MIME_PLAINTEXT, "");
            response.addHeader("Location", playable);
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (Throwable throwable) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT, "荐片 P2P 地址获取失败: " + throwable.getMessage());
        }
    }

    private static String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }
}
