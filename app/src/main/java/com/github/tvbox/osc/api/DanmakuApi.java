package com.github.tvbox.osc.api;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.bean.DanmuSearchResult;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.catvod.crawler.js.Trans;
import com.github.tvbox.osc.util.KV;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DanmakuApi {
    private static final String TAG = DanmakuApi.class.getSimpleName();
//    private static final String BUILTIN_API = "https://saas-oa.shyeguang.cn";
    private static final String BUILTIN_API = "https://logvardanmu.konfan.cn/87654321";
    private static final String USE_DEFAULT_KEY = "danmu_api_use_default";
    private static final long SEARCH_TIMEOUT = TimeUnit.SECONDS.toMillis(8);
    private static final long BUILTIN_TIMEOUT = TimeUnit.SECONDS.toMillis(20);
    private static final int BUILTIN_MAX_RETRY = 1;
    private static final int SEARCH_LIST_MAX_RETRY = 1;
    private static final int COMMENT_MAX_RETRY = 1;
    private static final int EPISODE_QUERY_NUMBER = 0;
    private static final int EPISODE_QUERY_EMPTY = 1;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final AtomicInteger searchSeq = new AtomicInteger();

    public interface SearchCallback {
        void onFound(String url);

        default void onNotFound() {
        }
    }

    public interface SearchListCallback {
        void onSuccess(List<DanmuSearchResult> results);

        void onError(String message);
    }

    public interface SearchResultCallback {
        void onSuccess(String danmu);

        void onError(String message);
    }

    public static boolean canSearch() {
        return DanmuHelper.isOpen() && !TextUtils.isEmpty(getApiUrl());
    }

    public static boolean isUseDefault() {
        return KV.get(USE_DEFAULT_KEY, false);
    }

    public static void setUseDefault(boolean useDefault) {
        KV.put(USE_DEFAULT_KEY, useDefault);
        if (useDefault) KV.put(HawkConfig.DANMU_API, "");
    }

    public static void setCustomApi(String api) {
        KV.put(USE_DEFAULT_KEY, false);
        KV.put(HawkConfig.DANMU_API, api);
    }

    public static void search(String name, String episode, SearchCallback callback) {
        String apiUrl = getApiUrl();
//        LOG.i("echo-danmaku search apiUrl: " + apiUrl);
        if (TextUtils.isEmpty(apiUrl) || callback == null) return;
        try {
            OkHttp.cancel(TAG);
            int seq = searchSeq.incrementAndGet();
//            LOG.i("echo-danmaku search title: " + safeLog(name) + ", episode: " + safeLog(episode));
            if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
                searchBuiltin(apiUrl, name, episode, callback, 0, seq);
                return;
            }
            newCall(apiUrl, name, episode).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    if (!isCurrentSearch(seq)) return;
                    LOG.e("echo-danmaku search error: " + e.getMessage());
                    notifyNotFound(callback, seq);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        if (!isCurrentSearch(seq)) return;
                        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
                        String body = response.body() == null ? "" : response.body().string();
                        String url = parseUrl(body);
                        if (!TextUtils.isEmpty(url) && isCurrentSearch(seq)) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isCurrentSearch(seq)) callback.onFound(url);
                                }
                            });
                        } else {
                            notifyNotFound(callback, seq);
                        }
                    } catch (Throwable th) {
                        LOG.e("echo-danmaku search parse error: " + th.getMessage());
                        notifyNotFound(callback, seq);
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable th) {
            LOG.e("echo-danmaku search start error: " + th.getMessage());
            notifyNotFound(callback, searchSeq.get());
        }
    }

    public static void searchList(String name, String episode, SearchListCallback callback) {
        if (callback == null) return;
        OkHttp.cancel(TAG);
        int seq = searchSeq.incrementAndGet();
        String apiUrl = getApiUrl();
        if (TextUtils.isEmpty(apiUrl)) {
            notifySearchListError(callback, seq, "弹幕搜索接口为空");
            return;
        }
        if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
            searchBuiltinList(apiUrl, name, callback, seq, 0);
            return;
        }
        try {
            newCall(apiUrl, name, episode).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    notifySearchListError(callback, seq, getErrorMessage(e));
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
                        String body = response.body() == null ? "" : response.body().string();
                        notifySearchListSuccess(callback, seq, parseSearchList(body));
                    } catch (Throwable th) {
                        notifySearchListError(callback, seq, getErrorMessage(th));
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable th) {
            notifySearchListError(callback, seq, getErrorMessage(th));
        }
    }

    public static void loadSearchResult(DanmuSearchResult result, SearchResultCallback callback) {
        if (callback == null) return;
        OkHttp.cancel(TAG);
        int seq = searchSeq.incrementAndGet();
        if (result == null || TextUtils.isEmpty(result.getUrl())) {
            notifySearchResultError(callback, seq, "弹幕地址为空");
            return;
        }
        if (!result.isBuiltIn()) {
            notifySearchResultSuccess(callback, seq, result.getUrl());
            return;
        }
        loadBuiltInSearchResult(result, callback, seq, 0, false);
    }

    private static void loadBuiltInSearchResult(DanmuSearchResult result, SearchResultCallback callback,
                                                int seq, int retry, boolean fallbackAttempted) {
        OkHttp.newCall(builtinClient(), result.getUrl(), TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                retryBuiltInSearchResult(result, callback, seq, retry, fallbackAttempted, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        LOG.e("echo-danmaku candidate HTTP " + code + ": " + requestLabel(result.getUrl()));
                        String fallbackUrl = code == 404 && !fallbackAttempted
                                ? builtinCommentUrl(result.getUrl()) : "";
                        if (!TextUtils.isEmpty(fallbackUrl)) {
                            loadBuiltInSearchResult(
                                    new DanmuSearchResult(result.getEpisodeName(), fallbackUrl, true),
                                    callback, seq, 0, true);
                        } else {
                            notifySearchResultError(callback, seq, code == 404
                                    ? "该条目暂无可用弹幕，请选择其他候选"
                                    : "HTTP " + code);
                        }
                        return;
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    String danmu = commentJsonToXml(body);
                    if (TextUtils.isEmpty(danmu)) throw new IOException("未获取到弹幕内容");
                    notifySearchResultSuccess(callback, seq, danmu);
                } catch (Throwable th) {
                    retryBuiltInSearchResult(result, callback, seq, retry, fallbackAttempted, th);
                } finally {
                    response.close();
                }
            }
        });
    }

    private static void retryBuiltInSearchResult(DanmuSearchResult result, SearchResultCallback callback,
                                                  int seq, int retry, boolean fallbackAttempted, Throwable error) {
        if (!isCurrentSearch(seq)) return;
        if (retry < COMMENT_MAX_RETRY) {
            handler.postDelayed(() -> {
                if (isCurrentSearch(seq)) loadBuiltInSearchResult(result, callback, seq, retry + 1, fallbackAttempted);
            }, 350L);
        } else {
            notifySearchResultError(callback, seq, getErrorMessage(error));
        }
    }

    public static void cancel() {
        searchSeq.incrementAndGet();
        OkHttp.cancel(TAG);
    }

    private static void searchBuiltinList(String apiUrl, String name, SearchListCallback callback, int seq, int retry) {
        String baseUrl = normalizeBaseUrl(apiUrl);
        String searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(Trans.t2s(name == null ? "" : name));
        OkHttp.newCall(builtinClient(), searchUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                retryBuiltinSearchList(apiUrl, name, callback, seq, retry, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        LOG.e("echo-danmaku list HTTP " + code + ": " + requestLabel(searchUrl));
                        if (code == 404 && !isBuiltinApiUrl(apiUrl)) {
                            searchBuiltinList(BUILTIN_API, name, callback, seq, 0);
                            return;
                        }
                        throw new IOException("HTTP " + code);
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    List<DanmuSearchResult> results = parseBuiltinSearchList(body, baseUrl);
                    if (results.isEmpty() && retry < SEARCH_LIST_MAX_RETRY) {
                        retryBuiltinSearchList(apiUrl, name, callback, seq, retry,
                                new IOException("弹幕搜索暂时无结果"));
                    } else {
                        notifySearchListSuccess(callback, seq, results);
                    }
                } catch (Throwable th) {
                    retryBuiltinSearchList(apiUrl, name, callback, seq, retry, th);
                } finally {
                    response.close();
                }
            }
        });
    }

    private static void retryBuiltinSearchList(String apiUrl, String name, SearchListCallback callback,
                                               int seq, int retry, Throwable error) {
        if (!isCurrentSearch(seq)) return;
        if (retry < SEARCH_LIST_MAX_RETRY) {
            handler.postDelayed(() -> {
                if (isCurrentSearch(seq)) searchBuiltinList(apiUrl, name, callback, seq, retry + 1);
            }, 350L);
        } else {
            notifySearchListError(callback, seq, getErrorMessage(error));
        }
    }

    private static void searchBuiltin(String apiUrl, String name, String episode, SearchCallback callback, int retry, int seq) {
        searchBuiltin(apiUrl, name, episode, callback, retry, seq, EPISODE_QUERY_NUMBER);
    }

    private static void searchBuiltin(String apiUrl, String name, String episode, SearchCallback callback, int retry, int seq, int queryMode) {
        final String baseUrl = normalizeBaseUrl(apiUrl);
        final String simpleName = Trans.t2s(name == null ? "" : name);
        final String simpleEpisode = Trans.t2s(episode == null ? "" : episode);
        final String episodeQuery = getEpisodeQuery(simpleEpisode, queryMode);
        final String searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(simpleName)
                + (TextUtils.isEmpty(episodeQuery) ? "" : "&episode=" + encode(episodeQuery));
//        LOG.i("echo-danmaku builtin search episodes: " + searchUrl + ", retry=" + retry + ", mode=" + queryMode);
        OkHttp.newCall(builtinClient(), searchUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!isCurrentSearch(seq)) return;
                if (retry < BUILTIN_MAX_RETRY) {
                    LOG.e("echo-danmaku builtin search error: " + e.getMessage() + ", retry later");
                    handler.postDelayed(() -> {
                        if (isCurrentSearch(seq)) searchBuiltin(apiUrl, name, episode, callback, retry + 1, seq, queryMode);
                    }, 300L);
                } else {
                    LOG.e("echo-danmaku builtin search error: " + e.getMessage());
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!isCurrentSearch(seq)) return;
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        LOG.e("echo-danmaku search HTTP " + code + ": " + requestLabel(searchUrl));
                        if (code == 404 && !isBuiltinApiUrl(apiUrl)) {
                            searchBuiltin(BUILTIN_API, name, episode, callback, 0, seq, queryMode);
                            return;
                        }
                        throw new IOException("HTTP " + code);
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    EpisodeMatch episodeMatch = findEpisodeFromSearchEpisodes(body, simpleName, simpleEpisode);
                    if (episodeMatch != null && !TextUtils.isEmpty(episodeMatch.id)) {
                        loadBuiltinComment(baseUrl, simpleName, simpleEpisode, episodeMatch, callback, seq);
                        return;
                    }
                    if (isSearchEpisodesMovieResult(body)) {
                        LOG.i("echo-danmaku builtin movie result not matched, skip anime fallback");
                        notifyNotFound(callback, seq);
                        return;
                    }
                    if (tryNextEpisodeQuery(apiUrl, name, episode, callback, retry, seq, queryMode)) return;
                    LOG.i("echo-danmaku builtin episode not matched, title: " + safeLog(simpleName) + ", episode: " + safeLog(simpleEpisode));
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true);
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin episode parse error: " + th.getMessage());
                    if (retry < BUILTIN_MAX_RETRY) {
                        handler.postDelayed(() -> {
                            if (isCurrentSearch(seq)) {
                                searchBuiltin(apiUrl, name, episode, callback, retry + 1, seq, queryMode);
                            }
                        }, 350L);
                        return;
                    }
                    if (tryNextEpisodeQuery(apiUrl, name, episode, callback, retry, seq, queryMode)) return;
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true);
                } finally {
                    response.close();
                }
            }
        });
    }

    private static boolean tryNextEpisodeQuery(String apiUrl, String name, String episode, SearchCallback callback, int retry, int seq, int queryMode) {
        int nextMode = getNextEpisodeQueryMode(Trans.t2s(episode == null ? "" : episode), queryMode);
        if (nextMode < 0) return false;
//        LOG.i("echo-danmaku builtin retry episodes query mode: " + queryMode + " -> " + nextMode);
        searchBuiltin(apiUrl, name, episode, callback, 0, seq, nextMode);
        return true;
    }

    private static void searchBuiltinAnime(String baseUrl, String name, String episode, SearchCallback callback, int seq, boolean notifyOnEmpty) {
        final String searchUrl = baseUrl + "/api/v2/search/anime?keyword=" + encode(name);
        LOG.i("echo-danmaku builtin search anime: " + searchUrl);
        OkHttp.newCall(builtinClient(), searchUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!isCurrentSearch(seq)) return;
                LOG.e("echo-danmaku builtin anime error: " + e.getMessage());
                if (notifyOnEmpty) notifyNotFound(callback, seq);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!isCurrentSearch(seq)) return;
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        LOG.e("echo-danmaku anime HTTP " + code + ": " + requestLabel(searchUrl));
                        if (code == 404 && !isBuiltinApiUrl(baseUrl)) {
                            searchBuiltin(BUILTIN_API, name, episode, callback, 0, seq);
                            return;
                        }
                        throw new IOException("HTTP " + code);
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    String animeId = findAnimeId(body);
                    if (TextUtils.isEmpty(animeId)) {
                        if (notifyOnEmpty) notifyNotFound(callback, seq);
                        return;
                    }
                    loadBuiltinBangumi(baseUrl, name, animeId, episode, callback, seq);
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin anime parse error: " + th.getMessage());
                    if (notifyOnEmpty) notifyNotFound(callback, seq);
                } finally {
                    response.close();
                }
            }
        });
    }

    private static void loadBuiltinBangumi(String baseUrl, String title, String animeId,
                                           String episode, SearchCallback callback, int seq) {
        final String bangumiUrl = baseUrl + "/api/v2/bangumi/" + animeId;
//        LOG.i("echo-danmaku builtin bangumi: " + bangumiUrl);
        OkHttp.newCall(builtinClient(), bangumiUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!isCurrentSearch(seq)) return;
                LOG.e("echo-danmaku builtin bangumi error: " + e.getMessage());
                notifyNotFound(callback, seq);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!isCurrentSearch(seq)) return;
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        LOG.e("echo-danmaku bangumi HTTP " + code + ": " + requestLabel(bangumiUrl));
                        if (code == 404 && !isBuiltinApiUrl(baseUrl)) {
                            searchBuiltin(BUILTIN_API, title, episode, callback, 0, seq);
                            return;
                        }
                        throw new IOException("HTTP " + code);
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    EpisodeMatch episodeMatch = findEpisode(body, episode);
                    if (episodeMatch != null && !TextUtils.isEmpty(episodeMatch.id)) {
                        loadBuiltinComment(baseUrl, "", episode, episodeMatch, callback, seq);
                    } else {
//                        LOG.i("echo-danmaku builtin bangumi episode not matched, episode: " + safeLog(episode));
                        notifyNotFound(callback, seq);
                    }
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin bangumi parse error: " + th.getMessage());
                    notifyNotFound(callback, seq);
                } finally {
                    response.close();
                }
            }
        });
    }

    private static void loadBuiltinComment(String baseUrl, String title, String episode, EpisodeMatch episodeMatch, SearchCallback callback, int seq) {
        loadBuiltinComment(baseUrl, title, episode, episodeMatch, callback, seq, 0);
    }

    private static void loadBuiltinComment(String baseUrl, String title, String episode, EpisodeMatch episodeMatch,
                                           SearchCallback callback, int seq, int retry) {
        final String commentUrl = baseUrl + "/api/v2/comment/" + episodeMatch.id + "?format=json";
        LOG.i("echo-danmaku builtin load title: " + safeLog(title)
                + ", request episode: " + safeLog(episode)
                + ", matched episode: " + safeLog(episodeMatch.title)
                + ", matched number: " + episodeMatch.number
                + ", episodeId: " + episodeMatch.id);
        LOG.i("echo-danmaku builtin comment: " + commentUrl);
        OkHttp.newCall(builtinClient(), commentUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!isCurrentSearch(seq)) return;
                LOG.e("echo-danmaku builtin comment error: " + e.getMessage());
                retryBuiltinComment(baseUrl, title, episode, episodeMatch, callback, seq, retry, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!isCurrentSearch(seq)) return;
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        LOG.e("echo-danmaku comment HTTP " + code + ": " + requestLabel(commentUrl));
                        if (code == 404) {
                            if (!isBuiltinApiUrl(baseUrl)) {
                                loadBuiltinComment(BUILTIN_API, title, episode, episodeMatch, callback, seq);
                            } else {
                                notifyNotFound(callback, seq);
                            }
                            return;
                        }
                        throw new IOException("HTTP " + code);
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    final String xml = commentJsonToXml(body);
                    if (TextUtils.isEmpty(xml)) throw new IOException("未获取到弹幕内容");
                    if (!isCurrentSearch(seq)) return;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isCurrentSearch(seq)) callback.onFound(xml);
                        }
                    });
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin comment parse error: " + th.getMessage());
                    retryBuiltinComment(baseUrl, title, episode, episodeMatch, callback, seq, retry, th);
                } finally {
                    response.close();
                }
            }
        });
    }

    private static void retryBuiltinComment(String baseUrl, String title, String episode, EpisodeMatch episodeMatch,
                                            SearchCallback callback, int seq, int retry, Throwable error) {
        if (!isCurrentSearch(seq)) return;
        if (retry < COMMENT_MAX_RETRY) {
            handler.postDelayed(() -> {
                if (isCurrentSearch(seq)) {
                    loadBuiltinComment(baseUrl, title, episode, episodeMatch, callback, seq, retry + 1);
                }
            }, 350L);
        } else {
            LOG.e("echo-danmaku builtin comment retries exhausted: " + getErrorMessage(error));
            notifyNotFound(callback, seq);
        }
    }

    private static void notifyNotFound(SearchCallback callback, int seq) {
        if (callback == null || !isCurrentSearch(seq)) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onNotFound();
            }
        });
    }

    private static boolean isCurrentSearch(int seq) {
        return seq == searchSeq.get();
    }

    private static Call newCall(String apiUrl, String name, String episode) {
        return newCall(searchClient(), apiUrl, name, episode);
    }

    private static OkHttpClient searchClient() {
        return OkHttp.client(SEARCH_TIMEOUT).newBuilder()
                .callTimeout(SEARCH_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();
    }

    private static OkHttpClient builtinClient() {
        return OkHttp.client(BUILTIN_TIMEOUT).newBuilder()
                .callTimeout(BUILTIN_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();
    }

    private static Call newCall(OkHttpClient client, String apiUrl, String name, String episode) {
        name = Trans.t2s(name == null ? "" : name);
        episode = Trans.t2s(episode == null ? "" : episode);
        if (hasPlaceholder(apiUrl)) {
            return OkHttp.newCall(client, apiUrl.replace("{name}", name).replace("{episode}", episode), TAG);
        }
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("name", name);
        params.put("episode", episode);
        return client.newCall(new Request.Builder().url(apiUrl).post(OkHttp.toBody(params)).tag(TAG).build());
    }

    private static String getApiUrl() {
        if (isUseDefault()) return BUILTIN_API;
        String custom = KV.get(HawkConfig.DANMU_API, "");
        if (!TextUtils.isEmpty(custom)) return custom.trim();
        String config = ApiConfig.get().getDanmaku().trim();
        if (!TextUtils.isEmpty(config)) return config;
        return BUILTIN_API;
    }

    private static boolean hasPlaceholder(String apiUrl) {
        return !TextUtils.isEmpty(apiUrl) && (apiUrl.contains("{name}") || apiUrl.contains("{episode}"));
    }

    private static boolean isDanmakuSearchApi(String apiUrl) {
        String url = apiUrl == null ? "" : apiUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.endsWith("/danmaku");
    }

    private static String normalizeBaseUrl(String apiUrl) {
        String url = apiUrl == null ? "" : apiUrl.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/87654321")) url = url.substring(0, url.length() - "/87654321".length());
        return url;
    }

    private static boolean isBuiltinApiUrl(String apiUrl) {
        return normalizeBaseUrl(apiUrl).equalsIgnoreCase(normalizeBaseUrl(BUILTIN_API));
    }

    private static String builtinCommentUrl(String sourceUrl) {
        Uri source = Uri.parse(sourceUrl);
        String path = source.getEncodedPath();
        if (TextUtils.isEmpty(path) || !path.startsWith("/api/v2/comment/")) return "";
        Uri fallback = Uri.parse(normalizeBaseUrl(BUILTIN_API)).buildUpon()
                .encodedPath(path)
                .encodedQuery(source.getEncodedQuery())
                .build();
        return isSameOrigin(source, fallback) ? "" : fallback.toString();
    }

    private static boolean isSameOrigin(Uri left, Uri right) {
        return left.getScheme() != null && right.getScheme() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getAuthority() != null && right.getAuthority() != null
                && left.getAuthority().equalsIgnoreCase(right.getAuthority());
    }

    private static String requestLabel(String url) {
        Uri uri = Uri.parse(url);
        String path = uri.getEncodedPath();
        return uri.getScheme() + "://" + uri.getAuthority() + (path == null ? "" : path);
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text == null ? "" : text, "UTF-8");
        } catch (Throwable th) {
            return text == null ? "" : text;
        }
    }

    private static String getEpisodeQuery(String episode, int queryMode) {
        if (queryMode == EPISODE_QUERY_EMPTY) return "";
        int number = DanmuEpisodeMatcher.episodeNumber(episode);
        return number > 0 ? String.valueOf(number) : "";
    }

    private static int getNextEpisodeQueryMode(String episode, int queryMode) {
        if (queryMode == EPISODE_QUERY_NUMBER && !TextUtils.isEmpty(getEpisodeQuery(episode, EPISODE_QUERY_NUMBER))) {
            return EPISODE_QUERY_EMPTY;
        }
        return -1;
    }

    private static String findAnimeId(String body) throws Exception {
        JSONObject object = new JSONObject(body);
        JSONArray array = object.optJSONArray("animes");
        if (array == null) array = object.optJSONArray("anime");
        if (array == null) array = object.optJSONArray("data");
        if (array == null || array.length() <= 0) return "";
        JSONObject item = array.optJSONObject(0);
        if (item == null) return "";
        String id = item.optString("animeId", "");
        if (TextUtils.isEmpty(id)) id = item.optString("id", "");
        return id;
    }

    private static EpisodeMatch findEpisode(String body, String episode) throws Exception {
        return findEpisode(body, episode, true);
    }

    private static EpisodeMatch findEpisode(String body, String episode, boolean allowMovieFallback) throws Exception {
        JSONObject object = new JSONObject(body);
        EpisodeList episodeList = findEpisodeList(object);
        JSONArray episodes = episodeList == null ? null : episodeList.episodes;
        if (episodes == null || episodes.length() <= 0) return null;
        int targetNumber = extractNumber(episode);
        int targetSeason = DanmuEpisodeMatcher.seasonNumber(episode);
        EpisodeMatch first = null;
        EpisodeMatch firstMandarin = null;
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject item = episodes.optJSONObject(i);
            if (item == null) continue;
            String id = firstString(item, "episodeId", "id");
            if (TextUtils.isEmpty(id)) continue;
            String title = firstString(item, "episodeTitle", "title", "name");
            int number = parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort"));
            EpisodeMatch match = new EpisodeMatch(id, title, number);
            int candidateSeason = getSeasonNumber(item, title);
            if (first == null) first = match;
            if (firstMandarin == null && isMandarinTitle(title)) firstMandarin = match;
            if (targetSeason > 0 && targetSeason != candidateSeason) continue;
            if (!TextUtils.isEmpty(episode) && !TextUtils.isEmpty(title) && title.contains(episode)) {
                return match;
            }
            if (targetNumber > 0 && number == targetNumber) return match;
            if (targetNumber > 0 && extractNumber(title) == targetNumber) return match;
        }
        if (TextUtils.isEmpty(episode)) return first;
        if (allowMovieFallback && episodeList.isMovie) return firstMandarin == null ? first : firstMandarin;
        return null;
    }

    private static EpisodeMatch findEpisodeFromSearchEpisodes(String body, String seriesTitle, String episode) throws Exception {
        JSONObject object = new JSONObject(body);
        JSONArray animes = object.optJSONArray("animes");
        if (animes == null) animes = object.optJSONArray("anime");
        if (animes == null) animes = object.optJSONArray("data");
        EpisodeMatch fallback = null;
        if (animes != null) {
            for (int i = 0; i < animes.length(); i++) {
                JSONObject anime = animes.optJSONObject(i);
                if (anime == null) continue;
                EpisodeMatch candidate = findEpisode(anime.toString(), episode, false);
                if (candidate == null) continue;
                String animeTitle = firstString(anime, "animeTitle", "title", "name");
                if (DanmuEpisodeMatcher.matchesSeriesTitle(seriesTitle, animeTitle)) return candidate;
                if (fallback == null) fallback = candidate;
            }
        }
        if (fallback != null) return fallback;
        EpisodeMatch match = findEpisode(body, episode, false);
        if (match != null) return match;
        EpisodeMatch movieFallback = findMovieFallback(object, episode);
        if (movieFallback != null) {
            LOG.i("echo-danmaku episodes movie fallback episode: " + safeLog(movieFallback.title) + ", episodeId: " + movieFallback.id);
        }
        return movieFallback;
    }

    private static EpisodeList findEpisodeList(JSONObject object) {
        JSONArray array = object.optJSONArray("episodes");
        if (array != null) return new EpisodeList(array, isMovieType(object));
        JSONObject bangumi = object.optJSONObject("bangumi");
        if (bangumi != null) {
            array = bangumi.optJSONArray("episodes");
            if (array != null) return new EpisodeList(array, isMovieType(bangumi));
        }
        JSONArray animes = object.optJSONArray("animes");
        if (animes == null) animes = object.optJSONArray("anime");
        if (animes == null) animes = object.optJSONArray("data");
        if (animes != null) {
            for (int i = 0; i < animes.length(); i++) {
                JSONObject item = animes.optJSONObject(i);
                if (item == null) continue;
                array = item.optJSONArray("episodes");
                if (array != null && array.length() > 0) return new EpisodeList(array, isMovieType(item));
            }
        }
        return null;
    }

    private static boolean isMovieType(JSONObject object) {
        if (object == null) return false;
        if (isMovieTypeText(object.optString("type", ""))) return true;
        if (isMovieTypeText(object.optString("typeDescription", ""))) return true;
        JSONObject bangumi = object.optJSONObject("bangumi");
        if (bangumi != null && isMovieTypeText(bangumi.optString("type", ""))) return true;
        JSONArray animes = object.optJSONArray("animes");
        if (animes == null) animes = object.optJSONArray("anime");
        if (animes == null) animes = object.optJSONArray("data");
        if (animes == null) return false;
        for (int i = 0; i < animes.length(); i++) {
            JSONObject item = animes.optJSONObject(i);
            if (item != null && isMovieTypeText(item.optString("type", ""))) return true;
        }
        return false;
    }

    private static boolean isMovieTypeText(String type) {
        return "\u7535\u5f71".equals(type);
    }

    private static boolean isMandarinTitle(String title) {
        return !TextUtils.isEmpty(title) && (title.contains("\u56fd\u8bed") || title.contains("\u666e\u901a\u8bdd"));
    }

    private static boolean isCantoneseTitle(String title) {
        return !TextUtils.isEmpty(title) && title.contains("\u7ca4\u8bed");
    }

    private static boolean prefersCantonese(String episode) {
        return isCantoneseTitle(episode);
    }

    private static boolean isPreferredLanguageTitle(String title, boolean preferCantonese) {
        return preferCantonese ? isCantoneseTitle(title) : isMandarinTitle(title);
    }

    private static boolean isSearchEpisodesMovieResult(String body) {
        try {
            JSONObject object = new JSONObject(body);
            JSONArray animes = object.optJSONArray("animes");
            if (animes == null) animes = object.optJSONArray("anime");
            if (animes == null) animes = object.optJSONArray("data");
            if (animes == null) return false;
            for (int i = 0; i < animes.length(); i++) {
                JSONObject anime = animes.optJSONObject(i);
                if (anime != null && isMovieType(anime) && anime.optJSONArray("episodes") != null) return true;
            }
        } catch (Throwable ignored) {
            LOG.d("DanmakuApi", "search body parse failed, treat as not movie");
        }
        return false;
    }

    private static EpisodeMatch findMovieFallback(JSONObject object, String episode) {
        JSONArray animes = object.optJSONArray("animes");
        if (animes == null) animes = object.optJSONArray("anime");
        if (animes == null) animes = object.optJSONArray("data");
        if (animes == null) return null;
        boolean preferCantonese = prefersCantonese(episode);
        EpisodeMatch firstMovie = null;
        for (int i = 0; i < animes.length(); i++) {
            JSONObject anime = animes.optJSONObject(i);
            if (anime == null || !isMovieType(anime)) continue;
            JSONArray episodes = anime.optJSONArray("episodes");
            if (episodes == null) continue;
            for (int j = 0; j < episodes.length(); j++) {
                JSONObject item = episodes.optJSONObject(j);
                if (item == null) continue;
                String id = firstString(item, "episodeId", "id");
                if (TextUtils.isEmpty(id)) continue;
                String title = firstString(item, "episodeTitle", "title", "name");
                EpisodeMatch match = new EpisodeMatch(id, title, parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort")));
                if (firstMovie == null) firstMovie = match;
                if (isPreferredLanguageTitle(title, preferCantonese)) return match;
            }
        }
        return firstMovie;
    }

    private static int parseEpisodeNumber(String value) {
        try {
            if (value == null || value.trim().isEmpty()) return -1;
            return (int) Float.parseFloat(value);
        } catch (Throwable th) {
            return DanmuEpisodeMatcher.episodeNumber(value);
        }
    }

    private static int getSeasonNumber(JSONObject item, String title) {
        int number = DanmuEpisodeMatcher.seasonNumber(title);
        if (number > 0) return number;
        String value = firstString(item, "seasonNumber", "season", "seasonNo", "season_num");
        number = DanmuEpisodeMatcher.seasonNumber(value);
        return number > 0 ? number : parseEpisodeNumber(value);
    }

    private static int extractNumber(String text) {
        return DanmuEpisodeMatcher.episodeNumber(text);
    }

    private static String commentJsonToXml(String body) throws Exception {
        JSONObject object = new JSONObject(body);
        JSONArray comments = object.optJSONArray("comments");
        if (comments == null) comments = object.optJSONArray("data");
        if (comments == null || comments.length() <= 0) return "";
        StringBuilder builder = new StringBuilder();
        builder.append("<i>");
        for (int i = 0; i < comments.length(); i++) {
            JSONObject item = comments.optJSONObject(i);
            if (item == null) continue;
            String param = item.optString("p", "");
            String text = item.optString("m", "");
            if (TextUtils.isEmpty(text)) text = item.optString("text", "");
            if (TextUtils.isEmpty(param) || TextUtils.isEmpty(text)) continue;
            builder.append("<d p=\"").append(escapeXml(normalizeDanmakuParam(param))).append("\">")
                    .append(escapeXml(text)).append("</d>");
        }
        builder.append("</i>");
        String xml = builder.toString();
        LOG.i("echo-danmaku builtin xml length: " + xml.length());
        return xml;
    }

    private static String normalizeDanmakuParam(String param) {
        String[] values = param.split(",");
        if (values.length < 4) return param;
        String time = values[0];
        String type = values[1];
        String size = values[2];
        String color = values[3];
        if (isColorValue(size)) {
            color = size;
            size = "25";
        } else if (!isColorValue(color)) {
            color = "16777215";
        }
        return time + "," + type + "," + size + "," + normalizeColor(color);
    }

    private static boolean isColorValue(String value) {
        try {
            if (TextUtils.isEmpty(value)) return false;
            String text = value.trim();
            if (text.startsWith("#")) return true;
            if (text.startsWith("0x") || text.startsWith("0X")) return true;
            long color = Long.parseLong(text);
            return color >= 0 && color <= 0x00ffffffL;
        } catch (Throwable th) {
            return false;
        }
    }

    private static String normalizeColor(String color) {
        if (TextUtils.isEmpty(color)) return "16777215";
        String text = color.trim();
        try {
            if (text.startsWith("#")) return String.valueOf(Long.parseLong(text.substring(1), 16));
            if (text.startsWith("0x") || text.startsWith("0X")) return String.valueOf(Long.parseLong(text.substring(2), 16));
        } catch (Throwable ignored) {
            LOG.d("DanmakuApi", "color '" + text + "' parse failed, keep raw");
        }
        return text;
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String safeLog(String text) {
        return TextUtils.isEmpty(text) ? "" : text;
    }

    private static List<DanmuSearchResult> parseSearchList(String body) throws Exception {
        List<DanmuSearchResult> results = new ArrayList<>();
        if (TextUtils.isEmpty(body)) return results;
        String text = body.trim();
        if (text.startsWith("[")) {
            appendSearchResults(results, new JSONArray(text));
            return results;
        }
        if (!text.startsWith("{")) return results;
        JSONObject object = new JSONObject(text);
        JSONArray items = object.optJSONArray("data");
        if (items == null) items = object.optJSONArray("list");
        if (items == null) items = object.optJSONArray("results");
        if (items != null) {
            appendSearchResults(results, items);
            return results;
        }
        String url = object.optString("url", "").trim();
        if (!TextUtils.isEmpty(url)) {
            results.add(new DanmuSearchResult(firstString(object, "name", "title"), url, false));
        }
        return results;
    }

    private static void appendSearchResults(List<DanmuSearchResult> results, JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "").trim();
            if (TextUtils.isEmpty(url)) continue;
            String name = firstString(item, "name", "title", "episode", "vod_name");
            results.add(new DanmuSearchResult(name, url, false));
        }
    }

    private static List<DanmuSearchResult> parseBuiltinSearchList(String body, String baseUrl) throws Exception {
        List<DanmuSearchResult> results = new ArrayList<>();
        JSONObject object = new JSONObject(body);
        appendBuiltinResults(results, baseUrl, object, "");
        JSONObject bangumi = object.optJSONObject("bangumi");
        if (bangumi != null) appendBuiltinResults(results, baseUrl, bangumi, firstString(bangumi, "animeTitle", "title", "name"));
        appendBuiltinAnimeResults(results, baseUrl, object.optJSONArray("animes"));
        appendBuiltinAnimeResults(results, baseUrl, object.optJSONArray("anime"));
        appendBuiltinAnimeResults(results, baseUrl, object.optJSONArray("data"));
        return results;
    }

    private static void appendBuiltinAnimeResults(List<DanmuSearchResult> results, String baseUrl, JSONArray animes) {
        if (animes == null) return;
        for (int i = 0; i < animes.length(); i++) {
            JSONObject anime = animes.optJSONObject(i);
            if (anime == null) continue;
            appendBuiltinResults(results, baseUrl, anime, firstString(anime, "animeTitle", "title", "name"));
            JSONObject bangumi = anime.optJSONObject("bangumi");
            if (bangumi != null) {
                appendBuiltinResults(results, baseUrl, bangumi, firstString(anime, "animeTitle", "title", "name"));
            }
        }
    }

    private static void appendBuiltinResults(List<DanmuSearchResult> results, String baseUrl, JSONObject object, String animeName) {
        JSONArray episodes = object.optJSONArray("episodes");
        if (episodes == null) return;
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.optJSONObject(i);
            if (episode == null) continue;
            String id = firstString(episode, "episodeId", "id");
            if (TextUtils.isEmpty(id)) continue;
            String episodeName = firstString(episode, "episodeTitle", "title", "name");
            String name = TextUtils.isEmpty(animeName) ? episodeName : animeName + " " + episodeName;
            if (TextUtils.isEmpty(name)) name = id;
            String url = baseUrl + "/api/v2/comment/" + id + "?format=json";
            results.add(new DanmuSearchResult(name, url, true));
        }
    }

    private static void notifySearchListSuccess(SearchListCallback callback, int seq, List<DanmuSearchResult> results) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onSuccess(results);
            }
        });
    }

    private static void notifySearchListError(SearchListCallback callback, int seq, String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onError(message);
            }
        });
    }

    private static void notifySearchResultSuccess(SearchResultCallback callback, int seq, String danmu) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onSuccess(danmu);
            }
        });
    }

    private static void notifySearchResultError(SearchResultCallback callback, int seq, String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onError(message);
            }
        });
    }

    private static String getErrorMessage(Throwable th) {
        String message = th == null ? "" : th.getMessage();
        if ("HTTP 404".equals(message)) return "该条目暂无可用弹幕，请选择其他候选";
        if ("HTTP 429".equals(message)) return "弹幕服务请求过于频繁，请稍后重试";
        return TextUtils.isEmpty(message) ? "弹幕搜索失败" : message;
    }

    private static class EpisodeList {
        final JSONArray episodes;
        final boolean isMovie;

        EpisodeList(JSONArray episodes, boolean isMovie) {
            this.episodes = episodes;
            this.isMovie = isMovie;
        }
    }

    private static class EpisodeMatch {
        final String id;
        final String title;
        final int number;

        EpisodeMatch(String id, String title, int number) {
            this.id = id;
            this.title = title;
            this.number = number;
        }
    }

    private static String escapeXml(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace(">", "&gt;")
                .replace("<", "&lt;");
    }

    private static String parseUrl(String body) throws Exception {
        if (TextUtils.isEmpty(body)) return "";
        String text = body.trim();
        if (text.startsWith("[")) {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String url = object.optString("url", "").trim();
                if (!TextUtils.isEmpty(url)) return url;
            }
        } else if (text.startsWith("{")) {
            return new JSONObject(text).optString("url", "").trim();
        } else if (text.startsWith("http") || text.startsWith("file")) {
            return text;
        }
        return "";
    }
}
