package com.github.tvbox.osc.util.thunder;

import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.LocalIPAddress;
import com.github.tvbox.osc.util.LOG;
import com.p2p.P2PClass;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

import okhttp3.Request;
import okhttp3.Response;


public class Jianpian {
    private static final String PLAY_BRIDGE_PATH = "/jianpian/play.m3u8";
    private static final String DOWNLOAD_BRIDGE_PATH = "/jianpian/p2p.m3u8";
    private static final Object LOCK = new Object();
    private static final Map<String, String> DOWNLOAD_SOURCES = new HashMap<>();
    /** identity -> doxdownload 返回的下载任务句柄。句柄不能用 0 代替。 */
    private static final Map<String, Integer> DOWNLOAD_HANDLES = new HashMap<>();
    private static final Map<String, Boolean> PAUSED_DOWNLOADS = new HashMap<>();
    private static String playbackSource = "";

    public static HashMap<String, String> playHeaders(String sourceKey) {
        if (sourceKey == null || sourceKey.trim().isEmpty()) return null;
        String key = sourceKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"jianpian".equals(key) && !"fan_jianpian".equals(key)) return null;
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        headers.put("Referer", "https://fan123.hzhnl.com");
        return headers;
    }

    public static String JPUrlDec(String url) {
        if (App.getp2p() != null) {
            try {
                String decode = URLDecoder.decode(url, "UTF-8");
                if (decode.startsWith("tvbox-xg:")) decode = decode.substring(9);
                if (isBridgeUrl(decode)) {
                    String resolved = resolveBridgeUrl(decode);
                    if (isP2pSourceUrl(resolved)) return startSource(resolved, false, "");
                    return resolved;
                }
                String[] split = decode.split("\\|");
                return startSource(split[0], false, "");
            } catch (Exception e) {
                return e.getLocalizedMessage();
            }
        } else {
            return "";
        }
    }

    public static void finish() {
        if (App.getp2p() != null) {
            try {
                synchronized (LOCK) {
                    if (!TextUtils.isEmpty(playbackSource)
                            && !DOWNLOAD_SOURCES.containsValue(playbackSource)) {
                        stopAndDelete(playbackSource);
                    }
                    playbackSource = "";
                    App.burl = "";
                }
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }

    public static Boolean isJpUrl(String url) {
        return !TextUtils.isEmpty(url) && (url.startsWith("tvbox-xg:")
                || isBridgeUrl(url) || isP2pSourceUrl(url));
    }

    /** 下载入口把荐片播放桥接地址切换为 P2P 桥接地址，仍保留稳定业务键。 */
    public static String downloadUrlFor(String url) {
        if (TextUtils.isEmpty(url)) return url;
        String value = url.trim();
        if (value.startsWith("tvbox-xg:")) value = value.substring(9);
        if (value.contains(PLAY_BRIDGE_PATH)) {
            return value.replace(PLAY_BRIDGE_PATH, DOWNLOAD_BRIDGE_PATH);
        }
        return value;
    }

    public static boolean isDownloadBridgeUrl(String url) {
        return !TextUtils.isEmpty(url) && url.contains(DOWNLOAD_BRIDGE_PATH);
    }

    public static boolean isBridgeUrl(String url) {
        return !TextUtils.isEmpty(url)
                && (url.contains(PLAY_BRIDGE_PATH) || url.contains(DOWNLOAD_BRIDGE_PATH));
    }

    public static boolean isP2pSourceUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String value = url.trim().toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("xg://") || value.startsWith("xgplay://")
                || (value.startsWith("ftp://") && value.contains("gbl.114s"));
    }

    /** 由 HTTP 桥接参数生成稳定身份，供下载暂停/继续关联同一个 P2P 任务。 */
    public static String downloadIdentity(Map<String, String> params) {
        if (params == null) return "";
        return safe(params.get("work_id")) + "|" + safe(params.get("episode_key")) + "|"
                + safe(params.get("episode_index")) + "|" + safe(params.get("line_key"));
    }

    /** 启动或复用荐片 P2P 任务，并返回 P2P 引擎本地 HTTP 地址。 */
    public static String prepareDownloadSource(String identity, String url) throws Exception {
        if (TextUtils.isEmpty(url)) throw new IOException("荐片没有返回下载地址");
        String value = normalizeSource(url);
        if (!isP2pSourceUrl(value)) return value;
        return startSource(value, true, identity);
    }

    /**
     * 继续已有 P2P 下载时优先复用原生任务，不重新请求荐片动态地址。
     * 动态地址可能刷新，但原生引擎的 .ybbk 任务和它绑定；换地址会重新开任务，
     * 表面上继续了，实际却从另一个任务开始。
     */
    public static String reuseDownloadSource(String identity) throws Exception {
        if (TextUtils.isEmpty(identity) || App.getp2p() == null) return "";
        synchronized (LOCK) {
            String source = DOWNLOAD_SOURCES.get(identity);
            if (TextUtils.isEmpty(source)) return "";
            if (PAUSED_DOWNLOADS.remove(identity) != null) {
                resumeDownloadTask(App.getp2p(), source);
            }
            return localUrl(source);
        }
    }

    public static void pauseDownloadUrl(String bridgeUrl) {
        updateDownloadTask(bridgeUrl, true, false);
    }

    public static void resumeDownloadUrl(String bridgeUrl) {
        updateDownloadTask(bridgeUrl, false, false);
    }

    public static void cancelDownloadUrl(String bridgeUrl) {
        updateDownloadTask(bridgeUrl, true, true);
    }

    private static void updateDownloadTask(String bridgeUrl, boolean pause, boolean delete) {
        String identity = identityFromBridgeUrl(bridgeUrl);
        if (TextUtils.isEmpty(identity) || App.getp2p() == null) return;
        synchronized (LOCK) {
            String source = DOWNLOAD_SOURCES.get(identity);
            if (TextUtils.isEmpty(source)) return;
            try {
                if (pause) App.getp2p().P2Pdoxpause(source.getBytes("GBK"));
                else resumeDownloadTask(App.getp2p(), source);
                if (delete) {
                    App.getp2p().P2Pdoxdel(source.getBytes("GBK"));
                    DOWNLOAD_SOURCES.remove(identity);
                    DOWNLOAD_HANDLES.remove(identity);
                    PAUSED_DOWNLOADS.remove(identity);
                } else if (pause) {
                    PAUSED_DOWNLOADS.put(identity, true);
                } else {
                    PAUSED_DOWNLOADS.remove(identity);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String startSource(String url, boolean download, String identity) throws Exception {
        String source = normalizeSource(url);
        P2PClass p2p = App.getp2p();
        if (p2p == null) return "";
        synchronized (LOCK) {
            if (download && !TextUtils.isEmpty(identity)) {
                String old = DOWNLOAD_SOURCES.get(identity);
                if (!TextUtils.isEmpty(old)) {
                    // 同一业务键只允许一个原生任务；地址刷新时也继续使用旧任务。
                    if (PAUSED_DOWNLOADS.remove(identity) != null) {
                        resumeDownloadTask(p2p, old);
                    }
                    return localUrl(old);
                } else {
                    int handle = startDownloadTask(p2p, source);
                    DOWNLOAD_SOURCES.put(identity, source);
                    if (handle > 0) DOWNLOAD_HANDLES.put(identity, handle);
                    PAUSED_DOWNLOADS.remove(identity);
                }
            } else {
                if (!TextUtils.isEmpty(playbackSource) && !playbackSource.equals(source)
                        && !DOWNLOAD_SOURCES.containsValue(playbackSource)) {
                    stopAndDelete(playbackSource);
                }
                p2p.P2Pdoxstart(source.getBytes("GBK"));
                p2p.P2Pdoxadd(source.getBytes("GBK"));
                playbackSource = source;
                App.burl = source;
            }
        }
        return localUrl(source);
    }

    /**
     * 注册并启动一个原生下载任务。
     *
     * doxdownload 返回的是下载任务句柄；doxstart 负责让已经登记的任务运行。
     * 两者不能合并成一次调用，也不能把后续恢复当成新的 doxdownload，否则会
     * 生成新的任务并丢掉原有 .ybbk 断点。
     */
    private static int startDownloadTask(P2PClass p2p, String source)
            throws UnsupportedEncodingException {
        if (p2p == null || TextUtils.isEmpty(source)) return 0;
        byte[] encoded = source.getBytes("GBK");
        int handle = p2p.P2Pdoxdownload(encoded);
        int startResult = p2p.P2Pdoxstart(encoded);
        if (handle <= 0) {
            // 某些原生版本登记接口不返回句柄，启动接口仍可能返回可查询句柄。
            handle = startResult;
        }
        if (handle <= 0 || startResult < 0) {
            LOG.i("jianpian-p2p-download-result=" + handle
                    + " start-result=" + startResult + " source=" + source);
        }
        return handle;
    }

    /** 恢复已经登记的任务，只启动原任务，不重新调用 doxdownload。 */
    private static void resumeDownloadTask(P2PClass p2p, String source)
            throws UnsupportedEncodingException {
        if (p2p == null || TextUtils.isEmpty(source)) return;
        int result = p2p.P2Pdoxstart(source.getBytes("GBK"));
        if (result < 0) {
            LOG.i("jianpian-p2p-resume-result=" + result + " source=" + source);
        }
    }

    /** 读取 P2P 原生缓存的实际字节数，供下载页覆盖 HTTP 桥接的滞后进度。 */
    public static DownloadStats queryDownloadStats(String bridgeUrl) {
        String identity = identityFromBridgeUrl(bridgeUrl);
        if (TextUtils.isEmpty(identity) || App.getp2p() == null) return null;
        synchronized (LOCK) {
            String source = DOWNLOAD_SOURCES.get(identity);
            if (TextUtils.isEmpty(source)) return null;
            try {
                P2PClass p2p = App.getp2p();
                byte[] encoded = source.getBytes("GBK");
                long localBytes = Math.max(0L, p2p.P2Pgetlocalfilesize(encoded));
                Integer handle = DOWNLOAD_HANDLES.get(identity);
                if (handle == null || handle <= 0) {
                    // 旧进程中没有保存句柄时不能把全局句柄 0 当成当前任务，
                    // 否则会把别的任务进度误投影到本任务。
                    return localBytes > 0L ? new DownloadStats(localBytes, 0L, 0L) : null;
                }
                long downloadedBytes = Math.max(localBytes,
                        Math.max(0L, p2p.P2Pgetdownsize(handle)));
                long totalBytes = Math.max(0L, p2p.P2Pgetfilesize(handle));
                long speedBytes = Math.max(0L, p2p.P2Pgetspeed(handle));
                int percent = p2p.P2Pgetpercent();
                if (totalBytes <= 0L && percent > 0 && percent < 100 && downloadedBytes > 0L) {
                    totalBytes = downloadedBytes * 100L / percent;
                }
                return new DownloadStats(downloadedBytes, totalBytes, speedBytes);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static final class DownloadStats {
        public final long downloadedBytes;
        public final long totalBytes;
        public final long speedBytesPerSecond;

        DownloadStats(long downloadedBytes, long totalBytes, long speedBytesPerSecond) {
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.speedBytesPerSecond = speedBytesPerSecond;
        }
    }

    private static void stopAndDelete(String source) throws UnsupportedEncodingException {
        if (TextUtils.isEmpty(source) || App.getp2p() == null) return;
        App.getp2p().P2Pdoxpause(source.getBytes("GBK"));
        App.getp2p().P2Pdoxdel(source.getBytes("GBK"));
    }

    private static String normalizeSource(String url) {
        String value = url == null ? "" : url.trim();
        try { value = URLDecoder.decode(value, "UTF-8"); } catch (Throwable ignored) { }
        return value.replace("xgplay://", "ftp://").replace("xg://", "ftp://");
    }

    private static String localUrl(String source) throws Exception {
        String segment = Uri.parse(source).getLastPathSegment();
        if (TextUtils.isEmpty(segment)) segment = "video.mp4";
        return "http://" + LocalIPAddress.getIP(App.getInstance()) + ":" + P2PClass.port
                + "/" + URLEncoder.encode(segment, "GBK");
    }

    private static String resolveBridgeUrl(String bridge) throws IOException {
        Request request = new Request.Builder().url(bridge).get().build();
        try (Response response = OkHttp.client(45000).newBuilder()
                .followRedirects(false).followSslRedirects(false).build().newCall(request).execute()) {
            String location = response.header("Location", "");
            if (TextUtils.isEmpty(location)) throw new IOException("荐片桥接地址没有返回 Location");
            return location;
        }
    }

    private static String identityFromBridgeUrl(String bridgeUrl) {
        try {
            okhttp3.HttpUrl url = okhttp3.HttpUrl.get(bridgeUrl);
            return safe(url.queryParameter("work_id")) + "|" + safe(url.queryParameter("episode_key"))
                    + "|" + safe(url.queryParameter("episode_index")) + "|"
                    + safe(url.queryParameter("line_key"));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
