package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.components.VodImages;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 多个标准 CMS 采集站的本地适配接口,补齐列表海报并统一请求头。 */
final class CmsRequestProcess implements RequestProcess {
    private static final String PREFIX = "/cms";
    private static final String SOURCE_PARAM = "source";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private static final int SEARCH_SCAN_PAGE_LIMIT = 30;
    private static final int SEARCH_LEAF_PAGE_LIMIT = 3;
    private static final long SEARCH_LEAF_CATEGORY_TIMEOUT_MILLIS = 7000L;
    private static final long SEARCH_PAGE_DEADLINE_MILLIS = 12000L;
    private static final int CATEGORY_SCAN_PAGE_LIMIT = 40;
    private static final int DISPLAY_LIMIT = 20;
    private static final int FETCH_CHILDREN_LIMIT = 4;
    private static final int FETCH_CHILDREN_TIMEOUT_SECONDS = 5;
    private static final Gson GSON = new Gson();
    private static final OkHttpClient DIRECT_CLIENT = new OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient SEARCH_CLIENT = DIRECT_CLIENT.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient SEARCH_PROBE_CLIENT = DIRECT_CLIENT.newBuilder()
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    /** 详情首屏使用独立预算，避免慢源把一次点击拖到通用 25 秒超时。 */
    private static final OkHttpClient DETAIL_CLIENT = DIRECT_CLIENT.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient IMAGE_DETAIL_CLIENT = DIRECT_CLIENT.newBuilder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final ExecutorService SEARCH_POOL = Executors.newFixedThreadPool(8);
    /** 海报详情补齐与搜索隔离，避免慢源把搜索线程池队列占满。 */
    private static final ExecutorService IMAGE_DETAIL_POOL = Executors.newFixedThreadPool(4);
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "api.ffzyapi.com",
            "cj.ffzyapi.com",
            "cj.lziapi.com",
            "suoniapi.com",
            "haiwaikan.com",
            "api.kuaifan.tv",
            "leshiapi.com",
            "www.hongniuzy2.com",
            "api.wujinapi.me",
            "jszyapi.com",
            "cj.yayazy.net",
            "api.niuniuzy.me",
            "sdzyapi.com",
            "jyzyapi.com",
            "m3u8.apiyhzy.com",
            "api.guangsuapi.com",
            "www.huyaapi.com",
            "360zyzz.com",
            "api.juliang.live",
            "iqiyizyapi.com",
            "api.maoyanapi.top",
            "caiji.dbzy5.com",
            "api.okzyw.net",
            "bfzyapi.com",
            "tyyszyapi.com",
            "zuidazy.co",
            "api.yzzy-api.com",
            "caiji.kuaichezy.org",
            "hhzyapi.com",
            "api.xinlangapi.com",
            "api.wsyzy.net",
            "caiji.dyttzyapi.com",
            "api.ukuapi88.com",
            "www.mdzyapi.com",
            "subocj.com",
            "api.apibdzy.com",
            "apiyutu.com",
            "cdn.dzzyapi.com",
            "cj.rycjapi.com",
            "api.yparse.com",
            "caiji.maotai999.vip",
            "m766.com",
            "ikunzyapi.com"
    ));

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return fileName != null && (PREFIX.equals(fileName) || fileName.startsWith(PREFIX + "/"));
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                         Map<String, String> params, Map<String, String> files) {
        try {
            if (params == null) params = session.getParms();
            String source = value(params, SOURCE_PARAM);
            if (!isAllowedSource(source)) {
                return jsonResponse(error("source 参数不是已适配的 CMS 地址"));
            }
            Map<String, String> upstreamParams = new HashMap<>();
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    if (!SOURCE_PARAM.equals(entry.getKey()) && entry.getValue() != null) {
                        upstreamParams.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (TextUtils.isEmpty(value(upstreamParams, "ac"))) {
                upstreamParams.put("ac", "list");
            }
            String action = value(upstreamParams, "ac");
            String type = value(upstreamParams, "t");
            Map<String, String> requestParams = new HashMap<>(upstreamParams);
            // SourceViewModel uses the legacy CMS convention ac=detail&t=... for categories.
            // A standard CMS treats that as an empty detail query, so translate it to list
            // while keeping real detail/search requests unchanged.
            if (!TextUtils.isEmpty(type)
                    && "detail".equalsIgnoreCase(action)
                    && TextUtils.isEmpty(value(upstreamParams, "ids"))
                    && TextUtils.isEmpty(value(upstreamParams, "wd"))) {
                requestParams.put("ac", "list");
                action = "list";
            }
            boolean detailById = "detail".equalsIgnoreCase(action)
                    && !TextUtils.isEmpty(value(requestParams, "ids"))
                    && TextUtils.isEmpty(value(requestParams, "wd"));
            JsonObject result = !TextUtils.isEmpty(value(requestParams, "wd"))
                    ? search(source, requestParams)
                    : fetchObject(source, requestParams, detailById ? DETAIL_CLIENT : DIRECT_CLIENT);
            result = guardYzzyDetailResult(source, requestParams, result);
        if (!TextUtils.isEmpty(type) && !"detail".equalsIgnoreCase(action)) {
            result = normalizeCategoryResult(source, requestParams, result, type);
        }
            // 标准 CMS 的列表接口经常省略 vod_pic；仅向当前资源站请求详情补齐，
            // 不跨源查图，也不改变已有海报，避免空图直接进入首页图片加载器。
            if (shouldHydrateImages(action, value(requestParams, "wd"))
                    && result != null
                    && result.has("list")
                    && result.get("list").isJsonArray()) {
                hydrateImages(source, result);
            }
            result = normalizeResult(source, result);
            // 外部补图默认关闭;启用时仅为空海报排队,缓存命中才写回当前响应。
            // 该调用不等待网络,不会把首页/搜索主响应绑定到外部 provider。
            // 真实详情响应已经交付给详情页；此时再排外部补图不会回写这次响应，
            // 只会制造无效网络请求。搜索/列表仍保留可选的异步补图能力。
            if (!detailById) scheduleExternalPosterLookups(source, result);
            return jsonResponse(result.toString());
        } catch (Throwable th) {
            return jsonResponse(error("CMS 资源适配失败: " + safeMessage(th)));
        }
    }

    /** 原生 CMS 搜索不可用时,按近期分页建立轻量本地回退结果。 */
    private JsonObject search(String source, Map<String, String> params) {
        String keyword = value(params, "wd");
        if (TextUtils.isEmpty(keyword)) return errorObject("搜索关键词为空");

        // 标准 CMS 文档把 wd 搜索放在列表接口下；部分站点兼容 detail，
        // 但也有站点只接受 list/videolist。按协议尝试有限的变体，失败后再走本地回退。
        ArrayList<Map<String, String>> probes = new ArrayList<>();
        Map<String, String> listParams = new HashMap<>(params);
        listParams.put("ac", "list");
        probes.add(listParams);
        Map<String, String> defaultParams = new HashMap<>(params);
        defaultParams.remove("ac");
        probes.add(defaultParams);
        Map<String, String> videoListParams = new HashMap<>(params);
        videoListParams.put("ac", "videolist");
        probes.add(videoListParams);
        for (Map<String, String> probe : probes) {
            try {
                JsonObject result = fetchObject(source, probe, SEARCH_PROBE_CLIENT);
                if (containsSearchHit(result, keyword)) return result;
            } catch (Throwable ignored) {
                // 部分采集站对 wd 返回纯文本或 HTTP 403,进入本地分类回退。
            }
        }
        return searchByPages(source, keyword);
    }

    private JsonObject searchByPages(String source, String keyword) {
        JsonObject first;
        try {
            first = fetchObject(source, mapOf("ac", "list", "pg", "1"), SEARCH_CLIENT);
        } catch (Throwable ignored) {
            return errorObject("搜索失败");
        }

        JsonArray matches = new JsonArray();
        Set<String> matchedIds = new HashSet<>();
        collectMatches(first, keyword, matches, matchedIds);

        // 部分站点明确返回“暂不支持搜索”，且父类 t=1/2/3/4 本身为空。旧逻辑只取
        // 每个父类最先完成的少量子类，影片落在其他子类时会出现“分类可见、搜索不到”。
        // 改为在固定总预算内按完成顺序扫描全部叶子分类，命中后停止并取消余下任务。
        if (matches.size() == 0) {
            collectLeafCategoryMatches(source, first, keyword, matches, matchedIds);
        }
        if (matches.size() > 0) return searchResult(first, matches);

        // 首页/父类均未命中时回退到全站分页扫描。多数采集站每页响应 2~3s,
        // 顺序扫 30 页会累加等待以致搜索整体 40s+;这里并行扫描剩余页,命中即全部终止。
        int reportedPageCount = intValue(first, "pagecount");
        // 某些 API 返回 pagecount=0/缺失，但 pg=2、pg=3 仍然可用；
        // 不能把未知总页数误判成“只有第一页”，否则上游关闭搜索的源永远只能搜到首页。
        int pageCount = reportedPageCount > 0
                ? Math.min(reportedPageCount, SEARCH_SCAN_PAGE_LIMIT)
                : SEARCH_SCAN_PAGE_LIMIT;
        if (pageCount > 1) {
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.ExecutorCompletionService<JsonObject> completion =
                    new java.util.concurrent.ExecutorCompletionService<>(SEARCH_POOL);
            ArrayList<java.util.concurrent.Future<JsonObject>> futures = new ArrayList<>();
            for (int page = 2; page <= pageCount; page++) {
                final int current = page;
                futures.add(completion.submit(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
                        if (stop.get()) return null;
                        try {
                            return fetchObject(source,
                                    mapOf("ac", "list", "pg", String.valueOf(current)), SEARCH_CLIENT);
                        } catch (Throwable ignored) {
                            return null;
                        }
                    }
                }));
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    SEARCH_PAGE_DEADLINE_MILLIS);
            int completed = 0;
            while (completed < futures.size() && matches.size() < DISPLAY_LIMIT) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    java.util.concurrent.Future<JsonObject> future = completion.poll(
                            remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                    if (future == null) break;
                    completed++;
                    JsonObject item = future.get();
                    if (item != null) {
                        int before = matches.size();
                        collectMatches(item, keyword, matches, matchedIds);
                        if (matches.size() > before) stop.set(true);
                    }
                } catch (Throwable ignored) {
                    // 单页被限流或超时时保留已经找到的结果,不让搜索整体失败。
                }
            }
            for (java.util.concurrent.Future<JsonObject> future : futures) {
                if (!future.isDone()) future.cancel(true);
            }
        }

        return searchResult(first, matches);
    }

    private void collectLeafCategoryMatches(String source, JsonObject first, String keyword,
                                            JsonArray matches, Set<String> matchedIds) {
        JsonArray classes = first != null && first.has("class") && first.get("class").isJsonArray()
                ? first.getAsJsonArray("class") : null;
        ArrayList<String> typeIds = searchableTypeIds(classes);
        if (typeIds.isEmpty()) return;

        java.util.concurrent.ExecutorCompletionService<JsonObject> completion =
                new java.util.concurrent.ExecutorCompletionService<>(SEARCH_POOL);
        ArrayList<Future<JsonObject>> futures = new ArrayList<>();
        for (String typeId : typeIds) {
            for (int page = 1; page <= SEARCH_LEAF_PAGE_LIMIT; page++) {
                final String currentTypeId = typeId;
                final int currentPage = page;
                futures.add(completion.submit(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        try {
                            return fetchObject(source, mapOf("ac", "list", "t", currentTypeId,
                                    "pg", String.valueOf(currentPage)), SEARCH_CLIENT);
                        } catch (Throwable ignored) {
                            return null;
                        }
                    }
                }));
            }
        }

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                SEARCH_LEAF_CATEGORY_TIMEOUT_MILLIS);
        int completed = 0;
        try {
            while (completed < futures.size() && matches.size() < DISPLAY_LIMIT) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<JsonObject> future = completion.poll(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                if (future == null) break;
                completed++;
                try {
                    JsonObject page = future.get();
                    if (page != null) collectMatches(page, keyword, matches, matchedIds);
                } catch (Throwable ignored) {
                    // 单个叶子分类失败时继续等待其余分类，保留已命中的结果。
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            for (Future<JsonObject> future : futures) {
                if (!future.isDone()) future.cancel(true);
            }
        }
    }

    static ArrayList<String> searchableTypeIds(JsonArray classes) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (classes == null) return new ArrayList<>(ids);
        Set<String> parentIds = new HashSet<>();
        for (JsonElement element : classes) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = stringValue(item, "type_id");
            String parentId = categoryParentId(item);
            if (!TextUtils.isEmpty(parentId) && !isRootParentId(parentId)
                    && !parentId.equals(id)) {
                parentIds.add(parentId);
            }
        }
        for (JsonElement element : classes) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = stringValue(item, "type_id");
            if (!id.isEmpty() && !parentIds.contains(id)) ids.add(id);
        }
        // Some sites fill type_id_1/parent_id with the item's own ID. In that
        // shape every category is mistaken for a parent; keep the original IDs
        // as a bounded fallback instead of skipping category search entirely.
        if (ids.isEmpty()) {
            for (JsonElement element : classes) {
                if (!element.isJsonObject()) continue;
                String id = stringValue(element.getAsJsonObject(), "type_id");
                if (!id.isEmpty()) ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private static String categoryParentId(JsonObject item) {
        String parentId = stringValue(item, "type_pid");
        if (parentId.isEmpty()) parentId = stringValue(item, "type_id_1");
        if (parentId.isEmpty()) parentId = stringValue(item, "parent_id");
        if (parentId.isEmpty()) parentId = stringValue(item, "pid");
        return parentId;
    }

    private static boolean isRootParentId(String parentId) {
        return TextUtils.isEmpty(parentId)
                || "0".equals(parentId)
                || "-1".equals(parentId)
                || "null".equalsIgnoreCase(parentId);
    }

    private JsonObject searchResult(JsonObject first, JsonArray matches) {
        JsonObject result = new JsonObject();
        result.addProperty("code", matches.size() > 0 ? 1 : 0);
        result.addProperty("msg", matches.size() > 0 ? "数据列表" : "暂无搜索结果");
        result.addProperty("page", "1");
        result.addProperty("pagecount", "1");
        result.addProperty("limit", "20");
        result.addProperty("total", matches.size());
        if (first.has("class") && first.get("class").isJsonArray()) {
            result.add("class", first.getAsJsonArray("class"));
        }
        result.add("list", matches);
        return result;
    }

    private void collectMatches(JsonObject page, String keyword, JsonArray matches,
                                Set<String> matchedIds) {
        if (page == null || !page.has("list") || !page.get("list").isJsonArray()) return;
        for (JsonElement element : page.getAsJsonArray("list")) {
            if (matches.size() >= DISPLAY_LIMIT || !element.isJsonObject()) break;
            JsonObject vod = element.getAsJsonObject();
            String id = stringValue(vod, "vod_id");
            if (!id.isEmpty() && !matchedIds.add(id)) continue;
            if (containsKeyword(vod, keyword)) matches.add(vod);
            else if (!id.isEmpty()) matchedIds.remove(id);
        }
    }

    private boolean containsSearchHit(JsonObject result, String keyword) {
        if (result == null || !result.has("list") || !result.get("list").isJsonArray()) return false;
        for (JsonElement element : result.getAsJsonArray("list")) {
            if (element.isJsonObject() && containsKeyword(element.getAsJsonObject(), keyword)) return true;
        }
        return false;
    }

    private boolean containsKeyword(JsonObject vod, String keyword) {
        String needle = normalizeSearchText(keyword);
        if (needle.isEmpty()) return false;
        String[] fields = {"vod_name", "vod_sub", "vod_en", "vod_actor", "vod_director",
                "vod_content", "vod_tag", "vod_class"};
        for (String field : fields) {
            if (normalizeSearchText(stringValue(vod, field)).contains(needle)) return true;
        }
        return false;
    }

    private String normalizeSearchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** 多数采集站列表接口不返回 vod_pic,用详情接口补齐当前页海报。 */
    private void hydrateImages(String source, JsonObject result) {
        if (result == null || !result.has("list") || !result.get("list").isJsonArray()) return;
        JsonArray list = result.getAsJsonArray("list");
        ArrayList<String> ids = new ArrayList<>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            if (stringValue(vod, "vod_pic").isEmpty()) {
                String id = stringValue(vod, "vod_id");
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        }
        if (ids.isEmpty()) return;

        Map<String, JsonObject> byId = new HashMap<>();
        ArrayList<Future<JsonObject>> batchFutures = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 10) {
            int end = Math.min(start + 10, ids.size());
            ArrayList<String> batch = new ArrayList<>(ids.subList(start, end));
            batchFutures.add(IMAGE_DETAIL_POOL.submit(() -> fetchObject(source,
                    mapOf("ac", "detail", "ids", join(batch)), IMAGE_DETAIL_CLIENT)));
        }

        // 批量详情并发请求，整个补图阶段有统一预算，不能按批次串行叠加慢源延迟。
        long batchDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500L);
        for (Future<JsonObject> future : batchFutures) {
            long remaining = batchDeadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                collectDetails(future.get(remaining, TimeUnit.NANOSECONDS), byId);
            } catch (Throwable ignored) {
                // 部分站点限制批量 ids,下面逐条并发降级,不能让整页列表失败。
                future.cancel(true);
            }
        }

        ArrayList<Future<JsonObject>> singleFutures = new ArrayList<>();
        for (String id : ids) {
            if (byId.containsKey(id)) continue;
            singleFutures.add(IMAGE_DETAIL_POOL.submit(() -> fetchObject(source,
                    mapOf("ac", "detail", "ids", id), IMAGE_DETAIL_CLIENT)));
        }
        long singleDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500L);
        for (Future<JsonObject> future : singleFutures) {
            long remaining = singleDeadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                collectDetails(future.get(remaining, TimeUnit.NANOSECONDS), byId);
            } catch (Throwable ignored) {
                // 单条详情也可能被站点临时限流,保留原列表结果。
                future.cancel(true);
            }
        }
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            JsonObject detail = byId.get(stringValue(vod, "vod_id"));
            if (detail == null) continue;
            // 详情接口也可能使用 pic/image/cover 或对象形式，先按当前资源站规则归一化，
            // 再复制回列表项，避免只认 vod_pic 导致搜索海报继续为空。
            normalizeImageAliases(detail, source);
            copyImageField(detail, vod, "vod_pic");
            copyImageField(detail, vod, "vod_pic_thumb");
            copyImageField(detail, vod, "vod_pic_slide");
            copyImageField(detail, vod, "vod_pic_screenshot");
            if (stringValue(vod, "vod_pic").isEmpty()) {
                copyImageField(detail, vod, "vod_pic_original");
                copyImageAlias(vod, "vod_pic", "vod_pic_original");
            }
        }

    }

    private void collectDetails(JsonObject details, Map<String, JsonObject> byId) {
        if (details == null || !details.has("list") || !details.get("list").isJsonArray()) return;
        for (JsonElement element : details.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            JsonObject detail = element.getAsJsonObject();
            String id = stringValue(detail, "vod_id");
            if (!id.isEmpty()) byId.put(id, detail);
        }
    }

    private void scheduleExternalPosterLookups(String source, JsonObject result) {
        if (result == null || !result.has("list") || !result.get("list").isJsonArray()) return;
        for (JsonElement element : result.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            // vod_pic 为空但 thumb/slide/screenshot 已存在时,AbsJson 仍能展示海报;
            // 这种条目不能触发外部请求。
            if (hasAnyImage(vod)) continue;
            String id = stringValue(vod, "vod_id");
            String title = stringValue(vod, "vod_name");
            String poster = VodImages.INSTANCE.resolvePosterOrSchedule("", source, id, title);
            if (!poster.isEmpty()) vod.addProperty("vod_pic", poster);
        }
    }

    private boolean hasAnyImage(JsonObject vod) {
        String[] fields = {"vod_pic", "pic", "poster", "vod_pic_original", "vod_pic_thumb",
                "thumb", "poster_thumb", "vod_pic_slide", "vod_pic_screenshot"};
        for (String field : fields) {
            if (!stringValue(vod, field).isEmpty()) return true;
        }
        return false;
    }

    /** 统一处理父类、子类以及不支持 t 参数的采集站分类。 */
    private JsonObject normalizeCategoryResult(String source, Map<String, String> params,
                                               JsonObject result, String type) throws IOException {
        if (isParentType(result, type)) return aggregateParent(source, params, result, type);
        if (containsType(result, type)) return result;

        JsonObject filtered = filterByType(result, type);
        if (!isEmptyList(filtered)) return filtered;
        return scanCategory(source, params, type, result);
    }

    /** 父类必须合并全部子类：展示列表最多 20 条，但 total/pagecount 不能在 20 条时提前截断。 */
    private JsonObject aggregateParent(String source, Map<String, String> params,
                                       JsonObject result, String parentId) throws IOException {
        JsonArray classes = result != null && result.has("class") && result.get("class").isJsonArray()
                ? result.getAsJsonArray("class") : null;
        if (classes == null || classes.size() == 0) {
            JsonObject home = fetchObject(source, mapOf("ac", "list", "pg", "1"));
            if (home.has("class") && home.get("class").isJsonArray()) {
                classes = home.getAsJsonArray("class");
            }
        }
        ArrayList<String> childIds = childIds(classes, parentId);
        if (childIds.isEmpty()) return result;

        int limit = intValue(result, "limit");
        if (limit <= 0) limit = DISPLAY_LIMIT;
        HashMap<String, JsonObject> children = fetchChildrenParallel(source, params, childIds);
        int total = 0;
        int pageCount = 1;
        JsonArray merged = new JsonArray();
        Set<String> mergedIds = new HashSet<>();
        for (String childId : childIds) {
            JsonObject child = children.get(childId);
            if (child == null) {
                // One unavailable subtype must not blank the entire parent category.
                continue;
            }
            total += intValue(child, "total");
            pageCount = Math.max(pageCount, intValue(child, "pagecount"));
            if (!child.has("list") || !child.get("list").isJsonArray()) continue;
            for (JsonElement item : child.getAsJsonArray("list")) {
                if (!item.isJsonObject()) continue;
                String id = stringValue(item.getAsJsonObject(), "vod_id");
                if (!id.isEmpty() && !mergedIds.add(id)) continue;
                if (merged.size() >= limit) continue;
                merged.add(item);
            }
        }

        if (merged.size() == 0 && !isEmptyList(result)) return result;

        JsonObject aggregated = new JsonObject();
        aggregated.addProperty("code", 1);
        aggregated.addProperty("msg", "数据列表");
        aggregated.addProperty("page", value(params, "pg").isEmpty() ? "1" : value(params, "pg"));
        aggregated.addProperty("pagecount", Math.max(pageCount, (total + limit - 1) / limit));
        aggregated.addProperty("limit", String.valueOf(limit));
        aggregated.addProperty("total", total);
        if (classes != null) aggregated.add("class", classes);
        aggregated.add("list", merged);
        return aggregated;
    }

    /** 并行拉取父类下的全部子类第一页,规避串行 20+ 个子类累计超时。
     * 用 {@link ExecutorCompletionService} 按完成顺序收取,凑够 {@value #FETCH_CHILDREN_LIMIT}
     * 个非空子类即返回,不会因慢子类或故障子类拖到 App 超时。 */
    private HashMap<String, JsonObject> fetchChildrenParallel(String source, Map<String, String> params,
                                                              ArrayList<String> childIds) {
        HashMap<String, JsonObject> children = new HashMap<>();
        if (childIds.isEmpty()) return children;
        java.util.concurrent.ExecutorCompletionService<ChildEntry> completion =
                new java.util.concurrent.ExecutorCompletionService<>(SEARCH_POOL);
        for (String childId : childIds) {
            final String id = childId;
            completion.submit(new Callable<ChildEntry>() {
                @Override
                public ChildEntry call() throws Exception {
                    Map<String, String> childParams = new HashMap<>(params);
                    childParams.put("ac", "list");
                    childParams.put("t", id);
                    try {
                        JsonObject child = fetchObject(source, childParams, SEARCH_CLIENT);
                        if (!containsType(child, id)) {
                            child = filterByType(child, id);
                            if (isEmptyList(child)) child = scanCategory(source, childParams, id, child);
                        }
                        return new ChildEntry(id, child);
                    } catch (Throwable ignored) {
                        return new ChildEntry(id, null);
                    }
                }
            });
        }
        int received = 0;
        int got = 0;
        while (received < childIds.size() && got < FETCH_CHILDREN_LIMIT) {
            try {
                java.util.concurrent.Future<ChildEntry> future =
                        completion.poll(FETCH_CHILDREN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                if (future == null) break; // 全部超过等待窗口,不让父类聚合继续累积等待
                received++;
                ChildEntry entry = future.get();
                if (entry == null || entry.child == null) continue;
                children.put(entry.id, entry.child);
                if (!isEmptyList(entry.child)) got++;
            } catch (Throwable ignored) {
                received++;
                // 单子类超时或失败时保留已拉到的其他子类结果。
            }
        }
        // 并行窗口收不齐(子类全超时/上游限流)时,父类聚合不能整屏空白:
        // 按子类顺序串行补拉,长超时兜底,凑够下限即停,防止父类返回空列表。
        if (got < FETCH_CHILDREN_LIMIT) {
            for (String childId : childIds) {
                if (got >= FETCH_CHILDREN_LIMIT) break;
                if (children.containsKey(childId) && !isEmptyList(children.get(childId))) continue;
                try {
                    Map<String, String> childParams = new HashMap<>(params);
                    childParams.put("ac", "list");
                    childParams.put("t", childId);
                    JsonObject child = fetchObject(source, childParams, DIRECT_CLIENT);
                    if (!containsType(child, childId)) {
                        child = filterByType(child, childId);
                        if (isEmptyList(child)) child = scanCategory(source, childParams, childId, child);
                    }
                    if (isEmptyList(child)) continue;
                    children.put(childId, child);
                    got++;
                } catch (Throwable ignored) {
                    // 单个子类连串行补拉都失败:继续下一条,不影响其它子类。
                }
            }
        }        return children;
    }
    private static final class ChildEntry {
        final String id;
        final JsonObject child;
        ChildEntry(String id, JsonObject child) {
            this.id = id;
            this.child = child;
        }
    }
    private boolean isParentType(JsonObject result, String type) {
        if (result == null || TextUtils.isEmpty(type)
                || !result.has("class") || !result.get("class").isJsonArray()) return false;
        for (JsonElement element : result.getAsJsonArray("class")) {
            if (!element.isJsonObject()) continue;
            if (type.equals(categoryParentId(element.getAsJsonObject()))) return true;
        }
        return false;
    }

    private boolean containsType(JsonObject result, String type) {
        if (result == null || !result.has("list") || !result.get("list").isJsonArray()) return false;
        for (JsonElement element : result.getAsJsonArray("list")) {
            if (element.isJsonObject() && matchesType(element.getAsJsonObject(), type)) return true;
        }
        return false;
    }

    private boolean matchesType(JsonObject vod, String type) {
        if (vod == null || TextUtils.isEmpty(type)) return false;
        String[] fields = {"type_id", "type_id_1", "type_id_2"};
        for (String field : fields) {
            if (type.equals(stringValue(vod, field))) return true;
        }
        return false;
    }

    private JsonObject filterByType(JsonObject source, String type) {
        JsonObject result = new JsonObject();
        if (source != null) {
            for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                if (!"list".equals(entry.getKey())) result.add(entry.getKey(), entry.getValue());
            }
        }
        JsonArray list = new JsonArray();
        if (source != null && source.has("list") && source.get("list").isJsonArray()) {
            for (JsonElement element : source.getAsJsonArray("list")) {
                if (element.isJsonObject() && matchesType(element.getAsJsonObject(), type)) list.add(element);
            }
        }
        result.add("list", list);
        result.addProperty("total", list.size());
        result.addProperty("page", value(source == null ? null : toStringMap(source), "page").isEmpty()
                ? "1" : value(source == null ? null : toStringMap(source), "page"));
        result.addProperty("pagecount", list.size() == 0 ? 0 : 1);
        result.addProperty("limit", String.valueOf(DISPLAY_LIMIT));
        return result;
    }

    /** t 被上游忽略时按普通列表页本地筛选，避免把首页误显示成子分类。 */
    private JsonObject scanCategory(String source, Map<String, String> params, String type,
                                    JsonObject original) throws IOException {
        JsonArray matches = new JsonArray();
        Set<String> ids = new HashSet<>();
        String requestedPage = value(params, "pg");
        int startPage = requestedPage.isEmpty() ? 1 : Math.max(1, parseInt(requestedPage, 1));
        int pageCount = Math.min(CATEGORY_SCAN_PAGE_LIMIT, Math.max(1, intValue(original, "pagecount")));
        if (pageCount <= 1) pageCount = CATEGORY_SCAN_PAGE_LIMIT;
        for (int page = startPage; page <= pageCount && matches.size() < DISPLAY_LIMIT; page++) {
            Map<String, String> pageParams = new HashMap<>(params);
            pageParams.put("ac", "list");
            pageParams.put("pg", String.valueOf(page));
            pageParams.remove("t");
            try {
                JsonObject item = fetchObject(source, pageParams, SEARCH_CLIENT);
                if (!item.has("list") || !item.get("list").isJsonArray()) continue;
                for (JsonElement element : item.getAsJsonArray("list")) {
                    if (!element.isJsonObject() || !matchesType(element.getAsJsonObject(), type)) continue;
                    String id = stringValue(element.getAsJsonObject(), "vod_id");
                    if (!id.isEmpty() && !ids.add(id)) continue;
                    matches.add(element);
                    if (matches.size() >= DISPLAY_LIMIT) break;
                }
            } catch (Throwable ignored) {
                // 单页失败继续扫描，保留已经找到的分类结果。
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", matches.size() > 0 ? 1 : 0);
        result.addProperty("msg", matches.size() > 0 ? "数据列表" : "暂无分类数据");
        result.addProperty("page", String.valueOf(startPage));
        result.addProperty("pagecount", matches.size() > 0 ? 1 : 0);
        result.addProperty("limit", String.valueOf(DISPLAY_LIMIT));
        result.addProperty("total", matches.size());
        if (original != null && original.has("class")) result.add("class", original.get("class"));
        result.add("list", matches);
        return result;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private Map<String, String> toStringMap(JsonObject object) {
        Map<String, String> result = new HashMap<>();
        if (object == null) return result;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            try {
                if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
                    result.put(entry.getKey(), entry.getValue().getAsString());
                }
            } catch (Throwable ignored) {
                // Metadata is optional.
            }
        }
        return result;
    }

    private ArrayList<String> childIds(JsonArray classes, String parentId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (classes == null) return new ArrayList<>(ids);
        boolean hasHierarchy = false;
        for (JsonElement element : classes) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = stringValue(item, "type_id");
            String pid = categoryParentId(item);
            if (!TextUtils.isEmpty(pid)) hasHierarchy = true;
            if (!id.isEmpty() && parentId.equals(pid)) ids.add(id);
        }
        if (!hasHierarchy) ids.clear();
        return new ArrayList<>(ids);
    }

    private boolean isEmptyList(JsonObject object) {
        return object == null || !object.has("list") || !object.get("list").isJsonArray()
                || object.getAsJsonArray("list").size() == 0;
    }

    private int intValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private JsonObject fetchObject(String source, Map<String, String> params) throws IOException {
        return fetchObject(source, params, DIRECT_CLIENT);
    }

    private JsonObject fetchObject(String source, Map<String, String> params, OkHttpClient client) throws IOException {
        IOException failure = null;
        long deadline = client == DETAIL_CLIENT
                ? System.nanoTime() + TimeUnit.SECONDS.toNanos(8L)
                : Long.MAX_VALUE;
        for (String candidate : sourceCandidates(source)) {
            if (System.nanoTime() >= deadline) break;
            try {
                JsonObject result = fetchObjectOnce(candidate, params, client);
                // 某些 CMS 详情接口会返回非请求 ID 的首页条目,先按详情语义清空,
                // 再判断是否需要继续尝试备用地址,避免把假成功当成有效结果。
                result = guardYzzyDetailResult(candidate, params, result);
                if (!isUnavailableResult(result, params)) return result;
                failure = new IOException("empty CMS result from " + candidate);
                LOG.d("cms", "echo-cms-fallback source=" + candidate + " reason=empty-result");
            } catch (IOException e) {
                failure = e;
                LOG.d("cms", "echo-cms-fallback source=" + candidate + " reason=" + safeMessage(e));
            }
        }
        if (failure != null) throw failure;
        throw new IOException("empty CMS source");
    }

    private JsonObject fetchObjectOnce(String source, Map<String, String> params, OkHttpClient client) throws IOException {
        String url = buildRequestUrl(source, params);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Referer", referer(source))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            String body = response.body().string();
            try {
                JsonObject object = GSON.fromJson(body, JsonObject.class);
                if (object != null) return object;
            } catch (Throwable ignored) {
                // 优质资源使用 XML RSS，JSON 解析失败后走对应转换器。
            }
            if (isCmsXmlHost(source) || body.trim().startsWith("<")) return convertYzzyXml(body);
            throw new IOException("invalid JSON");
        }
    }

    private boolean isUnavailableResult(JsonObject result, Map<String, String> params) {
        if (result == null) return true;
        if (!result.has("list") || !result.get("list").isJsonArray()) return true;
        // 详情/搜索空结果才触发备用接口;分类接口的合法空页不反复打备用入口。
        String action = value(params, "ac");
        return result.getAsJsonArray("list").size() == 0
                && ("detail".equalsIgnoreCase(action) || !TextUtils.isEmpty(value(params, "wd")));
    }

    /**
     * 按稳定顺序构造 CMS 候选地址:主地址、同站协议变体、配置备用 API、备用域名映射。
     * LinkedHashSet 同时保证顺序和去重,避免配置重复时重复请求同一地址。
     */
    List<String> sourceCandidates(String source) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (TextUtils.isEmpty(source)) return new ArrayList<>(candidates);
        addCandidate(candidates, source);
        try {
            HttpUrl url = HttpUrl.get(source);
            String host = url.host().toLowerCase(Locale.ROOT);
            if ("caiji.dbzy5.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/from/dbm3u8/at/json/"));
            } else if ("cj.lziapi.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/from/lzyun/"));
            } else if ("cj.ffzyapi.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/from/ffm3u8/"));
            } else if ("haiwaikan.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/from"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod"));
            } else if ("suoniapi.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/at/xml/"));
            } else if ("cj.rycjapi.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/from/rym3u8/"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/from/ryyun/"));
            } else if ("api.xinlangapi.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/xinlangapi.php/provide/vod/"));
                addCandidate(candidates, replacePath(url, "/xinlangapi.php/provide/vod/from/xlyun/"));
                addCandidate(candidates, replacePath(url, "/xinlangapi.php/provide/vod/from/xlm3u8/"));
            } else if ("api.yparse.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api/m3u8/json"));
            } else if ("caiji.maotai999.vip".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/from/mtm3u8/at/josn/"));
            } else if ("m766.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/at/json"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/at/xml"));
            } else if ("ikunzyapi.com".equals(host)) {
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/from/ikm3u8/at/json"));
                addCandidate(candidates, replacePath(url, "/api.php/provide/vod/"));
            }

            for (String backupApi : SourceBean.getBackupApisForApi(source)) {
                addCandidate(candidates, backupApi);
            }
            for (String backupDomain : SourceBean.getBackupDomainsForApi(source)) {
                addCandidate(candidates, backupDomainUrl(url, backupDomain));
            }
        } catch (Throwable ignored) {
            // 主地址在请求阶段再报告格式错误,不在候选生成阶段打断。
        }
        return new ArrayList<>(candidates);
    }

    private void addCandidate(LinkedHashSet<String> candidates, String value) {
        if (TextUtils.isEmpty(value)) return;
        String candidate = value.trim();
        if (!candidate.isEmpty()) candidates.add(candidate);
    }

    /** 备用域名通常只给站点根地址,沿用主 API 路径生成可请求候选。 */
    private String backupDomainUrl(HttpUrl primary, String backupDomain) {
        try {
            HttpUrl domain = HttpUrl.get(backupDomain.trim());
            String path = domain.encodedPath();
            if (TextUtils.isEmpty(path) || "/".equals(path)) path = primary.encodedPath();
            if (TextUtils.isEmpty(path)) path = "/";
            return domain.newBuilder().encodedPath(path).query(null).fragment(null).build().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String replacePath(HttpUrl url, String path) {
        return url.newBuilder().encodedPath(path).build().toString();
    }

    /** 构造上游请求 URL。传入参数覆盖 source URL 中同名参数。 */
    static String buildRequestUrl(String source, Map<String, String> params) {
        HttpUrl.Builder builder = HttpUrl.get(source).newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                // Request parameters are overrides. Removing existing values avoids
                // duplicate ac/pg/wd/t keys when a source URL contains defaults.
                builder.removeAllQueryParameters(entry.getKey());
                if (!TextUtils.isEmpty(entry.getValue())) {
                    builder.addQueryParameter(entry.getKey(), entry.getValue());
                }
            }
        }
        return builder.build().toString();
    }

    private boolean isYzzyHost(String source) {
        try {
            return "api.yzzy-api.com".equalsIgnoreCase(HttpUrl.get(source).host());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCmsXmlHost(String source) {
        try {
            String host = HttpUrl.get(source).host().toLowerCase(Locale.ROOT);
            return "api.yzzy-api.com".equals(host) || "m766.com".equals(host)
                    || "suoniapi.com".equals(host);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 优质资源偶尔忽略 ac=detail&ids 并返回首页列表,避免误把首页条目当详情。 */
    static JsonObject guardYzzyDetailResult(String source, Map<String, String> params,
                                             JsonObject result) {
        if (result == null || !isYzzyDetailRequest(source, params)
                || !result.has("list") || !result.get("list").isJsonArray()) return result;
        Set<String> requestedIds = new HashSet<>();
        for (String id : params.get("ids").split(",")) {
            id = id.trim();
            if (!id.isEmpty()) requestedIds.add(id);
        }
        if (requestedIds.isEmpty()) return result;
        boolean hasMatch = false;
        boolean allIdsRequested = true;
        for (JsonElement element : result.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            String id = stringValue(element.getAsJsonObject(), "vod_id");
            if (id.isEmpty()) continue;
            if (!requestedIds.contains(id)) {
                allIdsRequested = false;
            } else {
                hasMatch = true;
            }
        }
        if (result.getAsJsonArray("list").size() > 0 && hasMatch && allIdsRequested) {
            return result;
        }
        result.add("list", new JsonArray());
        return result;
    }

    private static boolean isYzzyDetailRequest(String source, Map<String, String> params) {
        if (params == null || !"detail".equalsIgnoreCase(params.get("ac"))
                || TextUtils.isEmpty(params.get("ids"))) return false;
        try {
            return "api.yzzy-api.com".equalsIgnoreCase(HttpUrl.get(source).host());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 将优质资源 RSS XML 转换为 App 使用的标准 CMS JSON。 */
    static JsonObject convertYzzyXml(String xml) throws IOException {
        if (TextUtils.isEmpty(xml)) throw new IOException("empty XML");
        final Document document;
        try {
            document = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
        } catch (Throwable th) {
            throw new IOException("invalid XML", th);
        }
        Element root = document.children().first();
        if (root == null) throw new IOException("empty XML");
        Element metadata = root.selectFirst("list");
        if (metadata == null) metadata = root;

        JsonObject result = new JsonObject();
        addXmlAttribute(result, "page", root, metadata, "page", "1");
        addXmlAttribute(result, "pagecount", root, metadata, "pagecount", "1");
        addXmlAttribute(result, "limit", root, metadata, "limit", "20");
        addXmlAttribute(result, "total", root, metadata, "recordcount", "0");
        result.addProperty("code", 1);
        result.addProperty("msg", "success");

        JsonArray classes = new JsonArray();
        Elements typeNodes = root.select("class ty");
        if (typeNodes.isEmpty()) typeNodes = root.select("ty");
        for (Element type : typeNodes) {
            String id = type.attr("id");
            if (id.isEmpty()) id = type.attr("type_id");
            if (id.isEmpty()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("type_id", id);
            item.addProperty("type_name", type.text().trim());
            classes.add(item);
        }
        result.add("class", classes);

        JsonArray list = new JsonArray();
        for (Element video : root.select("video")) {
            JsonObject item = new JsonObject();
            putXmlField(item, video, "vod_id", "id");
            putXmlField(item, video, "vod_name", "name");
            putXmlField(item, video, "type", "type");
            putXmlField(item, video, "vod_class", "type");
            putXmlField(item, video, "vod_remarks", "note");
            putXmlField(item, video, "vod_time", "last");
            putXmlField(item, video, "vod_play_from", "dt");
            if (!item.has("vod_play_from")) putXmlField(item, video, "vod_play_from", "play");
            putXmlField(item, video, "vod_pic", "pic");
            putXmlField(item, video, "vod_actor", "actor");
            putXmlField(item, video, "vod_director", "director");
            String content = xmlChildText(video, "description");
            if (content.isEmpty()) content = xmlChildText(video, "content");
            if (!content.isEmpty()) item.addProperty("vod_content", content);
            putXmlField(item, video, "vod_play_url", "url");
            list.add(item);
        }
        result.add("list", list);
        return result;
    }

    private static void addXmlAttribute(JsonObject result, String key, Element root,
                                        Element metadata, String attribute, String fallback) {
        String value = root.hasAttr(attribute) ? root.attr(attribute) : metadata.attr(attribute);
        result.addProperty(key, value.isEmpty() ? fallback : value);
    }

    private static void putXmlField(JsonObject object, Element video, String jsonKey, String xmlKey) {
        String value = xmlChildText(video, xmlKey);
        if (!value.isEmpty()) object.addProperty(jsonKey, value);
    }

    private static String xmlChildText(Element video, String name) {
        Element child = video.selectFirst(":root > " + name);
        if (child == null) child = video.selectFirst(name);
        return child == null ? "" : child.text().trim();
    }

    private String referer(String source) {
        String host = HttpUrl.get(source).host().toLowerCase(Locale.ROOT);
        if (host.equals("api.ffzyapi.com")) return "https://ffzy5.tv/";
        if (host.equals("cj.ffzyapi.com")) return "https://ffzy5.tv/";
        if (host.equals("cj.lziapi.com")) return "https://lzizy.net/";
        if (host.equals("suoniapi.com")) return "https://suonizy.net/";
        if (host.equals("haiwaikan.com")) return "https://haiwaikan.com/";
        if (host.equals("api.kuaifan.tv")) return "https://kuaifan.tv/";
        if (host.equals("leshiapi.com")) return "https://leshiapi.com/";
        if (host.equals("www.hongniuzy2.com")) return "https://hongniuzy.com/";
        if (host.equals("api.wujinapi.me")) return "https://wujinzy.com/";
        if (host.equals("jszyapi.com")) return "https://jisuzy1.com/";
        if (host.equals("cj.yayazy.net")) return "https://yayazy.com/";
        if (host.equals("api.niuniuzy.me")) return "https://niuniuzy.cc/";
        if (host.equals("sdzyapi.com")) return "https://shandianzy.com/";
        if (host.equals("jyzyapi.com")) return "https://jinyingzy.com/";
        if (host.equals("m3u8.apiyhzy.com")) return "https://yhzy.cc/";
        if (host.equals("api.guangsuapi.com")) return "https://www.guangsuzy.com/";
        if (host.equals("www.huyaapi.com")) return "https://huyazy.com/";
        if (host.equals("360zyzz.com")) return "https://360zy.com/";
        if (host.equals("api.juliang.live")) return "https://juliang.app/";
        if (host.equals("iqiyizyapi.com")) return "https://iqiyizy.com/";
        if (host.equals("api.maoyanapi.top")) return "https://www.maoyanzy.com/";
        if (host.equals("caiji.dbzy5.com")) return "https://dbzy.tv/";
        if (host.equals("api.okzyw.net")) return "https://www.zzzypro.com/";
        if (host.equals("bfzyapi.com")) return "https://bfzy2.tv/";
        if (host.equals("tyyszyapi.com")) return "https://tyyszy.com/";
        if (host.equals("zuidazy.co")) return "https://zuidazy.co/";
        if (host.equals("api.yzzy-api.com")) return "https://yzzy.tv/";
        if (host.equals("caiji.kuaichezy.org")) return "https://kuaichezy.com/";
        if (host.equals("hhzyapi.com")) return "https://haohuazy.com/";
        if (host.equals("api.xinlangapi.com")) return "https://xinlangzy.com/";
        if (host.equals("api.wsyzy.net")) return "https://www.wsyzy.cc/";
        if (host.equals("caiji.dyttzyapi.com")) return "https://dyttzy.tv/";
        if (host.equals("api.ukuapi88.com")) return "https://ukuzy.com/";
        if (host.equals("www.mdzyapi.com")) return "https://moduzy.com/";
        if (host.equals("subocj.com")) return "https://www.suboziyuan.net/";
        if (host.equals("api.apibdzy.com")) return "https://bdzy.tv/";
        if (host.equals("apiyutu.com")) return "https://yutuzy.com/";
        if (host.equals("cdn.dzzyapi.com")) return "https://dzzy.com/";
        if (host.equals("cj.rycjapi.com")) return "https://ryzy.tv/";
        if (host.equals("api.yparse.com")) return "https://yparse.com/";
        if (host.equals("caiji.maotai999.vip")) return "https://mtzy0.com/";
        if (host.equals("m766.com")) return "https://m766.com/";
        if (host.equals("ikunzyapi.com")) return "https://ikunzy.com/";
        return origin(source);
    }

    private String origin(String source) {
        HttpUrl url = HttpUrl.get(source);
        StringBuilder result = new StringBuilder(url.scheme()).append("://").append(url.host());
        if (url.port() != HttpUrl.defaultPort(url.scheme())) result.append(':').append(url.port());
        return result.append('/').toString();
    }

    boolean isAllowedSource(String source) {
        if (TextUtils.isEmpty(source)) return false;
        try {
            return ALLOWED_HOSTS.contains(HttpUrl.get(source).host().toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private JsonObject normalizeResult(String source, JsonObject result) {
        if (result == null || !result.has("list") || !result.get("list").isJsonArray()) return result;
        for (JsonElement element : result.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            normalizePlaybackFields(vod, source);
            normalizeImageAliases(vod, source);
        }
        return result;
    }

    /**
     * 兼容各资源站实际返回的图片字段差异。只从当前条目提取，不跨资源站补图。
     * 部分接口返回对象/数组（例如 image.url、cover.original），不能直接 getAsString。
     */
    static void normalizeImageAliases(JsonObject vod, String source) {
        if (vod == null) return;
        copyImageAlias(vod, "vod_pic",
                "pic", "poster", "image", "img", "cover", "vod_image",
                "vod_pic_url", "vod_pic_path", "vod_pic_original", "poster_url",
                "image_url", "img_url", "cover_url", "thumb_url");
        copyImageAlias(vod, "vod_pic_thumb",
                "thumb", "poster_thumb", "image_thumb", "cover_thumb", "thumbnail",
                "vod_pic_thumb_url");
        copyImageAlias(vod, "vod_pic_slide",
                "slide", "backdrop", "background", "vod_pic_background");
        copyImageAlias(vod, "vod_pic_screenshot", "screenshot", "vod_screenshot");
        normalizeImageField(vod, "vod_pic", source);
        normalizeImageField(vod, "vod_pic_thumb", source);
        normalizeImageField(vod, "vod_pic_slide", source);
        normalizeImageField(vod, "vod_pic_screenshot", source);
    }

    /** 兼容部分 CMS 把线路字段返回为数组/对象,或用逗号代替 $$$ 分隔符。 */
    static void normalizePlaybackFields(JsonObject vod) {
        if (vod == null) return;
        copyPlaybackAlias(vod, "vod_play_from", "play_from", "playFrom", "vod_play_flag", "vod_play_source");
        copyPlaybackAlias(vod, "vod_play_url", "play_url", "playUrl", "vod_url", "url");
        String from = playbackString(vod.get("vod_play_from"));
        String urls = playbackString(vod.get("vod_play_url"));
        // 对象形式 {"线路":"url"} 没有并行 from/url 字符串,统一成标准组。
        if (vod.get("vod_play_url") != null && vod.get("vod_play_url").isJsonObject()) {
            JsonObject object = vod.getAsJsonObject("vod_play_url");
            StringBuilder objectFrom = new StringBuilder();
            StringBuilder objectUrls = new StringBuilder();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String flag = entry.getKey().trim();
                String value = playbackString(entry.getValue());
                if (flag.isEmpty() || value.isEmpty()) continue;
                if (objectFrom.length() > 0) { objectFrom.append("$$$"); objectUrls.append("$$$"); }
                objectFrom.append(flag);
                objectUrls.append(value);
            }
            if (objectFrom.length() > 0) {
                from = objectFrom.toString();
                urls = objectUrls.toString();
            }
        }
        if (from.isEmpty() || urls.isEmpty()) return;
        from = normalizeGroupSeparator(from);
        urls = normalizeGroupSeparator(urls);
        // 巨量等 CMS 偶尔把并行播放组写成逗号分隔,而 URL 组仍使用 $$$。
        // 只有在另一字段已经明确给出多个组时才拆逗号,避免破坏线路名中的逗号。
        int explicitGroupCount = Math.max(groupCount(from), groupCount(urls));
        if (explicitGroupCount > 1) {
            from = normalizeCommaGroups(from, explicitGroupCount);
            urls = normalizeCommaGroups(urls, explicitGroupCount);
        }
        String[] flags = from.split("\\$\\$\\$", -1);
        String[] groups = urls.split("\\$\\$\\$", -1);
        // 某些接口 from 用逗号而 url 仍只有一组,把同一组保留到第一个可播放线路。
        if (flags.length > groups.length && groups.length == 1) {
            from = flags[0];
        }
        vod.addProperty("vod_play_from", from);
        vod.addProperty("vod_play_url", urls);
    }

    /**
     * 站点专属线路收敛。速播、巨量和 U 酷的其它线路经常是网页地址或失效旧链路，
     * 详情页只保留帮助页声明的 m3u8 线路；若接口没有该线路则保留原始数据，避免
     * 因站点临时返回格式变化导致整部影片没有播放组。
     */
    static void normalizePlaybackFields(JsonObject vod, String source) {
        normalizePlaybackFields(vod);
        String required = requiredPlaybackFlag(source);
        if (TextUtils.isEmpty(required) || vod == null) return;
        String from = playbackString(vod.get("vod_play_from"));
        String urls = playbackString(vod.get("vod_play_url"));
        if (from.isEmpty() || urls.isEmpty()) return;
        String[] flags = from.split("\\$\\$\\$", -1);
        String[] groups = urls.split("\\$\\$\\$", -1);
        StringBuilder filteredFrom = new StringBuilder();
        StringBuilder filteredUrls = new StringBuilder();
        int count = Math.min(flags.length, groups.length);
        for (int i = 0; i < count; i++) {
            String flag = flags[i] == null ? "" : flags[i].trim();
            String group = groups[i] == null ? "" : groups[i].trim();
            if (flag.isEmpty() || group.isEmpty()
                    || !flag.toLowerCase(Locale.ROOT).contains(required)) continue;
            if (filteredFrom.length() > 0) {
                filteredFrom.append("$$$");
                filteredUrls.append("$$$");
            }
            filteredFrom.append(flag);
            filteredUrls.append(group);
        }
        if (filteredFrom.length() > 0) {
            vod.addProperty("vod_play_from", filteredFrom.toString());
            vod.addProperty("vod_play_url", filteredUrls.toString());
        }
    }

    static String requiredPlaybackFlag(String source) {
        if (TextUtils.isEmpty(source)) return "";
        try {
            String host = HttpUrl.get(source).host().toLowerCase(Locale.ROOT);
            if (host.equals("subocj.com") || host.endsWith(".subocj.com")) return "subm3u8";
            if (host.equals("api.juliang.live") || host.endsWith(".juliang.live")) return "jlm3u8";
            if (host.equals("api.ukuapi88.com") || host.endsWith(".ukuapi88.com")) return "ukm3u8";
        } catch (Throwable ignored) {
            // 非标准来源由统一 CMS 解析继续处理，不做全局线路误删。
        }
        return "";
    }

    private static void copyPlaybackAlias(JsonObject vod, String target, String... aliases) {
        if (vod.has(target) && !playbackString(vod.get(target)).isEmpty()) return;
        for (String alias : aliases) {
            if (!vod.has(alias)) continue;
            String value = playbackString(vod.get(alias));
            if (!value.isEmpty()) {
                vod.add(target, vod.get(alias));
                return;
            }
        }
    }

    private static String playbackString(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        try {
            if (element.isJsonPrimitive()) return element.getAsString().trim();
            if (element.isJsonArray()) {
                StringBuilder result = new StringBuilder();
                for (JsonElement item : element.getAsJsonArray()) {
                    String value = playbackString(item);
                    if (value.isEmpty()) continue;
                    if (result.length() > 0) result.append("$$$");
                    result.append(value);
                }
                return result.toString();
            }
            return element.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalizeGroupSeparator(String value) {
        return value.replace("\r", "").replace("\n", "")
                .replace("|||", "$$$").replace(",,,", "$$$");
    }

    private static int groupCount(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        return value.split("\\$\\$\\$", -1).length;
    }

    private static String normalizeCommaGroups(String value, int expectedCount) {
        if (TextUtils.isEmpty(value) || value.contains("$$$") || expectedCount < 2) return value;
        String[] groups = value.split("[,，]", -1);
        if (groups.length != expectedCount) return value;
        StringBuilder result = new StringBuilder();
        for (String group : groups) {
            String trimmed = group.trim();
            if (trimmed.isEmpty()) return value;
            if (result.length() > 0) result.append("$$$");
            result.append(trimmed);
        }
        return result.toString();
    }

    private static void normalizeImageField(JsonObject vod, String key, String source) {
        if (!vod.has(key) || vod.get(key).isJsonNull()) return;
        String value = normalizeImageUrl(imageValue(vod.get(key)), source);
        if (!value.isEmpty()) vod.addProperty(key, value);
    }

    static boolean shouldHydrateImages(String action, String keyword) {
        // 搜索沿用 ac=detail&wd=... 约定，但详情补图是同步网络链路；
        // 先交付搜索条目，海报由已有异步图片管线按需处理。
        return !"detail".equalsIgnoreCase(action);
    }

    private static void copyImageAlias(JsonObject vod, String target, String... aliases) {
        if (!imageValue(vod == null ? null : vod.get(target)).isEmpty()) return;
        for (String alias : aliases) {
            String value = imageValue(vod == null ? null : vod.get(alias));
            if (!value.isEmpty()) {
                vod.addProperty(target, value);
                return;
            }
        }
    }

    /** 从字符串、数组或常见图片对象中提取第一条可用地址。 */
    private static String imageValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        try {
            if (element.isJsonPrimitive()) return element.getAsString().trim();
            if (element.isJsonArray()) {
                for (JsonElement item : element.getAsJsonArray()) {
                    String value = imageValue(item);
                    if (!value.isEmpty()) return value;
                }
                return "";
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                String[] keys = {"url", "src", "original", "large", "medium", "imageUrl", "image_url", "path"};
                for (String key : keys) {
                    String value = imageValue(object.get(key));
                    if (!value.isEmpty()) return value;
                }
            }
        } catch (Throwable ignored) {
            // 非图片对象直接忽略，保留其它候选字段。
        }
        return "";
    }

    static String normalizeImageUrl(String value) {
        return normalizeImageUrl(value, "");
    }

    private static String normalizeImageUrl(String value, String source) {
        if (value == null) return "";
        value = cleanImageUrl(value);
        if (value.isEmpty()) return value;
        boolean protocolRelative = value.startsWith("//");
        String candidate = protocolRelative ? "https:" + value : repairImageScheme(value);
        try {
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")
                    && source != null && !source.trim().isEmpty()) {
                HttpUrl base = HttpUrl.get(source);
                HttpUrl resolved = base.resolve(candidate);
                if (resolved != null) candidate = resolved.toString();
            }
            HttpUrl url = HttpUrl.get(candidate);
            String host = url.host().toLowerCase(Locale.ROOT);
            if ("xinlangtupian.com".equals(host))
                return url.newBuilder().host("xl.xltupian.com").build().toString();
            if ("viptulz.com".equals(host) || "img.lzzyimg.com".equals(host))
                return url.newBuilder().host("img.lzipic.com").build().toString();
            return candidate;
        } catch (Throwable ignored) {
            return value;
        }
    }

    /** 清理采集站常见的 HTML/JSON 包装，避免海报 URL 带引号、空白或单斜杠协议。 */
    private static String cleanImageUrl(String value) {
        String result = value.trim();
        for (int i = 0; i < 2; i++) {
            String decoded = Entities.unescape(result);
            decoded = decodeImageEscapes(decoded);
            if (decoded.equals(result)) break;
            result = decoded;
        }
        result = result.replaceAll("\\s+", "").trim();
        while (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                result = result.substring(1, result.length() - 1).trim();
                result = result.replaceAll("\\s+", "");
            } else {
                break;
            }
        }
        return result;
    }

    private static String decodeImageEscapes(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            char next = value.charAt(++i);
            if (next == '/' || next == '\\' || next == '"' || next == '\'') {
                result.append(next);
            } else if (next == 'u' && i + 4 < value.length()) {
                int codePoint = 0;
                boolean valid = true;
                for (int offset = 1; offset <= 4; offset++) {
                    int digit = Character.digit(value.charAt(i + offset), 16);
                    if (digit < 0) {
                        valid = false;
                        break;
                    }
                    codePoint = (codePoint << 4) | digit;
                }
                if (valid) {
                    result.append((char) codePoint);
                    i += 4;
                } else {
                    result.append('\\').append(next);
                }
            } else {
                result.append('\\').append(next);
            }
        }
        return result.toString();
    }

    private static String repairImageScheme(String value) {
        for (String scheme : new String[]{"https", "http"}) {
            String prefix = scheme + ":";
            if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            int slash = prefix.length();
            while (slash < value.length() && value.charAt(slash) == '/') slash++;
            if (slash == prefix.length()) return value;
            return prefix + "//" + value.substring(slash);
        }
        return value;
    }

    private void copyImageField(JsonObject from, JsonObject to, String key) {
        String value = stringValue(from, key);
        if (!value.isEmpty()) to.addProperty(key, value);
    }

    private String join(ArrayList<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private Map<String, String> mapOf(String... values) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }

    private static String stringValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }

    private String error(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("msg", message);
        root.addProperty("page", 1);
        root.addProperty("pagecount", 1);
        root.addProperty("limit", "20");
        root.addProperty("total", 0);
        root.add("list", new JsonArray());
        return root.toString();
    }

    private JsonObject errorObject(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("msg", message);
        root.addProperty("page", 1);
        root.addProperty("pagecount", 1);
        root.addProperty("limit", "20");
        root.addProperty("total", 0);
        root.add("list", new JsonArray());
        return root;
    }

    private String safeMessage(Throwable th) {
        String message = th == null ? "" : th.getMessage();
        return TextUtils.isEmpty(message) ? th.getClass().getSimpleName() : message;
    }

    private NanoHTTPD.Response jsonResponse(String body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json; charset=utf-8",
                body == null ? "{}" : body);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
