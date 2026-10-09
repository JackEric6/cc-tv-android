package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import android.util.Base64;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.net.OkHttp;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.github.tvbox.osc.server.Cz4kBrowserSession;
import com.github.tvbox.osc.server.NativeFanSource;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OfflineContentCache;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;
import com.github.tvbox.osc.util.KV;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;


import java.io.IOException;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.HttpUrl;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    private static final String PUSH_AGENT = "push_agent";
    private static final String PUSH_FALLBACK = "push_fallback";
    private static final String PUSH_HEADERS_MARKER = "@Headers=";

    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> actionResult;
    public MutableLiveData<JSONObject> playResult;
    /** 下一集预解析专用通道（预载方案,与 playResult 独立 seq 防串扰,规格 §5.2） */
    public MutableLiveData<JSONObject> preloadResult;

    private Gson gson;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger playRequestSeq = new AtomicInteger();
    private final AtomicInteger preloadRequestSeq = new AtomicInteger();
    /** 首页请求代际;切源后旧源的迟到回调不得覆盖新源首屏。 */
    private final AtomicInteger sortRequestSeq = new AtomicInteger();

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        actionResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        preloadResult = new MutableLiveData<>();
        gson=new Gson();
    }

    public static final ExecutorService spThreadPool = Executors.newFixedThreadPool(3); // 2026-09-11:单线程改 3 线程——原单线程被卡死的 spider 任务(不响应 interrupt)永久占用后,后续全部任务排队,首页永久骨架屏
    /** 首页分类请求独立于搜索/详情等 spider 任务，避免慢源占满公共线程池。 */
    private static final ExecutorService homeCategoryThreadPool = Executors.newFixedThreadPool(8);
    /** 推荐是首屏补充请求，不能占用分类请求的并发额度。 */
    private static final ExecutorService homeRecThreadPool = Executors.newFixedThreadPool(2);
    private static final ExecutorService httpPrepareThreadPool = Executors.newFixedThreadPool(3);
    /** 详情扩展参数准备独立限流，避免点击详情时同步占用主线程或公共准备池。 */
    private static final ExecutorService detailPrepareThreadPool = Executors.newFixedThreadPool(2);
    /** spider 任务使用共享的守护线程池替代"每请求 new 一个单线程执行器"。
        旧实现里每个请求各建一条非守护线程,挂死的 spider(不响应 interrupt)会让线程
        永久泄漏;切源/切分类的并发突发会把后台线程数撑到无界。共享守护池把并发上限
        钉死在固定值,线程全部为守护线程,坏任务只占用一个槽位而不会拖累 JVM 退出。 */
    private static final ExecutorService spiderTaskThreadPool = Executors.newFixedThreadPool(24, daemonThreadFactory("spider-task"));
    /** 扩展参数(extend JSON)抓取与 spider 任务解耦,占 spThreadPool 的槽位会拖慢详情/取流. */
    private static final ExecutorService fixUrlThreadPool = Executors.newFixedThreadPool(2, daemonThreadFactory("fix-url"));

    private static ThreadFactory daemonThreadFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "avbox-" + prefix + "-" + seq.getAndIncrement());
            }
        };
    }

    private static List<Movie.Video> keepHomeList(List<Movie.Video> videos, AbsSortXml sortXml) {
        if (videos != null && !videos.isEmpty()) return videos;
        if (sortXml != null && sortXml.list != null && sortXml.list.videoList != null) {
            return sortXml.list.videoList;
        }
        return new ArrayList<>();
    }

    //homeContent缓存，最多存储5个sourceKey的AbsSortXml对象
    private static final Map<String, AbsSortXml> sortCache = Collections.synchronizedMap(new LinkedHashMap<String, AbsSortXml>(5, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, AbsSortXml> eldest) {
            return size() > 5;
        }
    });

    // XStream 非线程安全:按线程缓存实例复用(勿改共享单例)
    private static final ThreadLocal<XStream> sortXStream = new ThreadLocal<XStream>() {
        @Override
        protected XStream initialValue() {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            return xstream;
        }
    };
    private static final ThreadLocal<XStream> listXStream = new ThreadLocal<XStream>() {
        @Override
        protected XStream initialValue() {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            return xstream;
        }
    };

    private static void cacheSort(String sourceKey, AbsSortXml sortXml) {
        attachSortSource(sourceKey, sortXml);
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (!hasHomeRecVideos(sortXml)) {
            return;
        }
        if (!shouldBypassSortCache(sourceKey, sourceBean) && !hasActionSort(sortXml)) {
            sortCache.put(sourceKey, sortXml);
        }
        // 进程内缓存用于热切换，MMKV 缓存用于重启/断网回退；两者都只保存已解析的展示数据。
        OfflineContentCache.putHome(sourceKey, sortXml);
    }

    public static void clearRuntimeCache() {
        sortCache.clear();
        extendCache.clear();
    }

    private static AbsSortXml attachSortSource(String sourceKey, AbsSortXml sortXml) {
        if (sortXml != null) {
            sortXml.sourceKey = sourceKey;
        }
        return sortXml;
    }

    private void postSortResult(String sourceKey, AbsSortXml sortXml) {
        if (sortXml == null) {
            sortXml = new AbsSortXml();
        }
        sortResult.postValue(attachSortSource(sourceKey, sortXml));
    }

    private void postInitialSortResult(String sourceKey, AbsSortXml sortXml, long requestStartedAt) {
        LOG.i("echo--home-first-result--" + sourceKey + "--elapsedMs="
                + (System.currentTimeMillis() - requestStartedAt));
        postSortResult(sourceKey, sortXml);
    }

    private void postSortFailure(String sourceKey, int requestSeq) {
        if (!isCurrentSortRequest(requestSeq)) return;
        AbsSortXml cached = OfflineContentCache.getHome(sourceKey);
        if (cached != null && hasHomeRecVideos(cached)) {
            attachSortSource(sourceKey, cached);
            sortCache.put(sourceKey, cached);
            LOG.i("offline-home-fallback source=" + sourceKey);
            postSortResult(sourceKey, cached);
        } else {
            postSortResult(sourceKey, null);
        }
    }

    private boolean isCurrentSortRequest(int requestSeq) {
        return requestSeq == sortRequestSeq.get();
    }

    private static boolean hasActionSort(AbsSortXml sortXml) {
        if (sortXml == null) return false;
        if (hasActionVideo(sortXml.videoList)) return true;
        return sortXml.list != null && hasActionVideo(sortXml.list.videoList);
    }

    private static boolean hasHomeRecVideos(AbsSortXml sortXml) {
        return sortXml != null && sortXml.videoList != null && !sortXml.videoList.isEmpty();
    }

    private static boolean hasActionVideo(List<Movie.Video> videos) {
        if (videos == null) return false;
        for (Movie.Video video : videos) {
            if (video != null && video.action != null) return true;
        }
        return false;
    }

    private static boolean shouldBypassSortCache(String sourceKey, SourceBean sourceBean) {
        return isFirstSource(sourceKey) && isDoubanSource(sourceBean);
    }

    private static boolean isFirstSource(String sourceKey) {
        List<SourceBean> sources = ApiConfig.get().getSourceBeanList();
        return !sources.isEmpty() && sources.get(0) != null && sourceKey.equals(sources.get(0).getKey());
    }

    private static boolean isDoubanSource(SourceBean sourceBean) {
        if (sourceBean == null) return false;
        return containsDouban(sourceBean.getKey())
                || containsDouban(sourceBean.getName())
                || containsDouban(sourceBean.getApi())
                || containsDouban(sourceBean.getExt());
    }

    private static boolean containsDouban(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String lower = value.toLowerCase();
        return lower.contains("douban") || value.contains("\u8c46\u74e3");
    }


    // homeContent
    public void getSort(final String sourceKey) {
        getSort(sourceKey, true);
    }

    /** withRec=false 跳过首页推荐那一次额外请求(豆瓣类 videolist / spider homeVideoContent),sorts 不必等它 */
    public void getSort(final String sourceKey, final boolean withRec) {
        final int requestSeq = sortRequestSeq.incrementAndGet();
        final long requestStartedAt = System.currentTimeMillis();
        if (sourceKey == null) {
            sortResult.postValue(new AbsSortXml());
            return;
        }

        // 优先检查缓存
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            LOG.i("echo--getSort-source-null--" + sourceKey);
            postSortResult(sourceKey, null);
            return;
        }
        if(sourceBean.getName().length()<=3 && sourceBean.getName().endsWith("搜")){
            postSortResult(sourceKey, null);
            return;
        }

        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            AbsSortXml cached = sortCache.get(sourceKey);
            if (cached != null) {
                boolean shouldUseCache = cached.videoList != null && !cached.videoList.isEmpty();
                if (shouldUseCache) {
                    attachSortSource(sourceKey, cached);
                    postSortResult(sourceKey, cached);
                    return;
                }
            }
        }

        if (NativeFanSource.isSupported(sourceKey)) {
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String nativeJson = NativeFanSource.home(sourceKey);
                        if (!isCurrentSortRequest(requestSeq)) return;
                        AbsSortXml nativeSort = sortJson(sortResult, nativeJson);
                        if (nativeSort == null) {
                            postSortFailure(sourceKey, requestSeq);
                            return;
                        }
                        AbsXml homeData = json(null, nativeJson, sourceKey);
                        if (homeData != null && homeData.movie != null
                                && homeData.movie.videoList != null
                                && !homeData.movie.videoList.isEmpty()) {
                            nativeSort.videoList = homeData.movie.videoList;
                        }
                        attachSortSource(sourceKey, nativeSort);
                        postInitialSortResult(sourceKey, nativeSort, requestStartedAt);
                        cacheSort(sourceKey, nativeSort);
                    } catch (Throwable throwable) {
                        LOG.i("native-home-error source=" + sourceKey + " error=" + throwable.getMessage());
                        postSortFailure(sourceKey, requestSeq);
                    }
                }
            });
            return;
        }

        final int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    Future<String> future = spiderTaskThreadPool.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            String json = sp.homeContent(true);
//                            LOG.i("echo--getSort :" + json);
                            return json;
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(30, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        LOG.i("echo--getSort-timeout--" + sourceBean.getKey());
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        Throwable cause = e.getCause();
                        LOG.i("echo--getSort-error--" + sourceBean.getKey() + "--" + e.getClass().getSimpleName() + "--" + (cause != null ? cause.getClass().getSimpleName() + ":" + cause.getMessage() : e.getMessage()));
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (!withRec) {
                                    if (!isCurrentSortRequest(requestSeq)) {
                                        return;
                                    }
                                    postInitialSortResult(sourceKey, sortXml, requestStartedAt);
                                    cacheSort(sourceKey, sortXml);
                                } else if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    if (!isCurrentSortRequest(requestSeq)) {
                                        return;
                                    }
                                    sortXml.videoList = absXml.movie.videoList;
                                    postInitialSortResult(sourceKey, sortXml, requestStartedAt);
                                    cacheSort(sourceKey, sortXml);
                                } else {
                                    if (!isCurrentSortRequest(requestSeq)) {
                                        return;
                                    }
                                    // 首轮 homeContent 已有分类结果，推荐请求仅作后台补充，不能阻塞首页首帧。
                                    postInitialSortResult(sourceKey, sortXml, requestStartedAt);
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            if (!isCurrentSortRequest(requestSeq)) return;
                                            // 推荐接口是补充请求;失败时必须保留首轮 homeContent 列表,否则首页会被清空。
                                            sortXml.videoList = keepHomeList(videos, sortXml);
                                            postSortResult(sourceKey, sortXml);
                                            cacheSort(sourceKey, sortXml);
                                        }
                                    });
                                }
                            } else {
                                if (!isCurrentSortRequest(requestSeq)) {
                                    return;
                                }
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            }
                        } else {
                            if (!isCurrentSortRequest(requestSeq)) {
                                return;
                            }
                            postSortFailure(sourceKey, requestSeq);
                        }
                    }
                }
            };
            httpPrepareThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (requestSeq != sortRequestSeq.get()) return;
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = response.body();
                                sortXml = sortXml(sortResult, xml);
                            } else if (type == 1) {
                                String json = response.body();
                                sortXml = sortJson(sortResult, json);
                            }
                            attachSortSource(sourceKey, sortXml);
                            if (withRec && sortXml != null && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                final AbsSortXml finalSortXml = sortXml;
                                // 先显示首页列表;详情补充仅用于丰富海报/播放字段,不应阻塞首屏。
                                finalSortXml.videoList = finalSortXml.list.videoList;
                                    postInitialSortResult(sourceKey, finalSortXml, requestStartedAt);
                                cacheSort(sourceKey, finalSortXml);
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        if (requestSeq != sortRequestSeq.get()) return;
                                        // 详情补充请求失败时回退到首轮返回的列表,避免首页空白。
                                        finalSortXml.videoList = keepHomeList(videos, finalSortXml);
                                        postSortResult(sourceKey, finalSortXml);
                                        cacheSort(sourceKey, finalSortXml);
                                    }
                                });
                            } else {
                                postInitialSortResult(sourceKey, sortXml, requestStartedAt);
                                cacheSort(sourceKey, sortXml);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            if (requestSeq != sortRequestSeq.get()) return;
                            postSortFailure(sourceKey, requestSeq);
                        }
                    });
        }else if (type == 4) {
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);
            if(URLEncoder.encode(extend).length()<1000){
                GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                        .tag(sourceBean.getKey() + "_sort")
                        .params("filter", "true");
                // 当 extend 不为空且非空字符串时添加参数
                if (extend != null && !extend.isEmpty()) {
                    request.params("extend", extend);
                }
                request.execute(new AbsCallback<String>() {
                            @Override
                            public String convertResponse(okhttp3.Response response) throws Throwable {
                                if (response.body() != null) {
                                    return response.body().string();
                                } else {
                                    throw new IllegalStateException("网络请求错误");
                                }
                            }

                            @Override
                            public void onSuccess(Response<String> response) {
                                if (!isCurrentSortRequest(requestSeq)) return;
                                String sortJson  = response.body();
                                if (sortJson != null) {
                                    final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                                    attachSortSource(sourceKey, sortXml);
                                    if (sortXml != null) {
                                        AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                            sortXml.videoList = absXml.movie.videoList;
                                            postInitialSortResult(sourceKey, sortXml, requestStartedAt);
                                            cacheSort(sourceKey, sortXml);
                                        } else {
                                            // 先交付分类首轮结果，推荐/详情补充在后台完成。
                                            postSortResult(sourceKey, sortXml);
                                            getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                                @Override
                                                public void done(List<Movie.Video> videos) {
                                                    if (!isCurrentSortRequest(requestSeq)) return;
                                                    // 推荐接口失败不应覆盖已经解析出的首页列表。
                                                    sortXml.videoList = keepHomeList(videos, sortXml);
                                                    postSortResult(sourceKey, sortXml);
                                                    cacheSort(sourceKey, sortXml);
                                                }
                                            });
                                        }
                                    } else {
                                        if (!isCurrentSortRequest(requestSeq)) return;
                                        postSortResult(sourceKey, sortXml);
                                        cacheSort(sourceKey, sortXml);
                                    }
                                } else {
                                    if (!isCurrentSortRequest(requestSeq)) return;
                                    postSortFailure(sourceKey, requestSeq);
                                }
                            }

                            @Override
                            public void onError(Response<String> response) {
                                super.onError(response);
                                if (!isCurrentSortRequest(requestSeq)) return;
                                postSortFailure(sourceKey, requestSeq);
                            }
                        });
            }else {
                try {
                    Map<String, String> params = new HashMap<>();
                    params.put("filter","true");
                    if (extend != null && !extend.isEmpty()) {
                        params.put("extend",extend);
                    }
                    RemoteTVBox.post(sourceBean.getApi(), params, new okhttp3.Callback() {
                        @Override
                        public void onFailure(@NonNull Call call, IOException e) {
                            if (!isCurrentSortRequest(requestSeq)) return;
                                postSortFailure(sourceKey, requestSeq);
                        }

                        @Override
                        public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                            if (!isCurrentSortRequest(requestSeq)) return;
                            assert response.body() != null;
                            String sortJson = response.body().string();
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    postInitialSortResult(sourceKey, sortXml, requestStartedAt);
                                    cacheSort(sourceKey, sortXml);
                                } else {
                                    postSortResult(sourceKey, sortXml);
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            if (!isCurrentSortRequest(requestSeq)) return;
                                            sortXml.videoList = keepHomeList(videos, sortXml);
                                            postSortResult(sourceKey, sortXml);
                                            cacheSort(sourceKey, sortXml);
                                        }
                                    });
                                }
                            } else {
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            }
                        }
                    });
                } catch (Exception ignored) {
                    if (!isCurrentSortRequest(requestSeq)) return;
                            postSortFailure(sourceKey, requestSeq);
                }
            }
        } else {
            postSortResult(sourceKey, null);
        }
    }
    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        getList(homeSourceBean.getKey(), sortData, page);
    }

    /** 分类请求显式绑定源，避免首页固定西瓜时误用用户默认源。 */
    public void getList(String sourceKey, MovieSort.SortData sortData, int page) {
        if (sortData == null) {
            LOG.i("echo-getList-sortData-null");
            listResult.postValue(null);
            return;
        }
        SourceBean homeSourceBean = ApiConfig.get().getSource(sourceKey);
        if (homeSourceBean == null) {
            LOG.i("echo-getList-source-null--" + sourceKey);
            listResult.postValue(null);
            return;
        }
        if (NativeFanSource.isSupported(sourceKey)) {
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String nativeJson = NativeFanSource.category(sourceKey, sortData.id, page, sortData.filterSelect);
                        json(listResult, nativeJson, sourceKey);
                    } catch (Throwable throwable) {
                        LOG.i("native-category-error source=" + sourceKey + " error=" + throwable.getMessage());
                        listResult.postValue(null);
                    }
                }
            });
            return;
        }
        int type = homeSourceBean.getType();
        if (type == 3) {
            homeCategoryThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    Future<String> future = spiderTaskThreadPool.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(homeSourceBean);
                            return sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect);
                        }
                    });
                    String json = null;
                    try {
                        json = future.get(homeSourceBean.getPlayTimeoutSeconds(), TimeUnit.SECONDS);
//                        LOG.i("echo-categoryContent:"+json);
                    } catch (TimeoutException e) {
                        LOG.i("echo--getList-timeout--" + homeSourceBean.getKey());
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        Throwable cause = e.getCause();
                        LOG.i("echo--getList-error--" + homeSourceBean.getKey() + "--" + e.getClass().getSimpleName() + "--" + (cause != null ? cause.getClass().getSimpleName() + ":" + cause.getMessage() : e.getMessage()));
                        e.printStackTrace();
                    } finally {
                        if (json != null) {
                            json(listResult, json,homeSourceBean.getKey());
                        } else {
                            listResult.postValue(null);
                        }
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params(sortData.filterSelect)
                    .params("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString())
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(listResult, xml, homeSourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(listResult, json, homeSourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });
        }else if (type == 4) {
            String ext= "";
            String extend=homeSourceBean.getExt();
            extend=getFixUrl(extend);
            if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
                try {
                    String selectExt = new JSONObject(sortData.filterSelect).toString();
                    ext = Base64.encodeToString(selectExt.getBytes("UTF-8"), Base64.DEFAULT |  Base64.NO_WRAP);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }else {
                ext = Base64.encodeToString("{}".getBytes(), Base64.DEFAULT |  Base64.NO_WRAP);
            }

            GetRequest<String> request = OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", "detail")
                    .params("filter", "true")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params("ext", ext);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            try {
                                if (response.body() != null) {
                                    return response.body().string();
                                } else {
                                    throw new IllegalStateException("网络请求错误，response body 为 null");
                                }
                            } catch (Exception e) {
                                LOG.i("echo-list: convertResponse error"+ e.getMessage());
                                throw e;  // 重新抛出异常
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
//                            LOG.i("echo-list: " + json);
                            json(listResult, json, homeSourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });

        } else {
            listResult.postValue(null);
        }
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }
    //    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    Future<String> future = spiderTaskThreadPool.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            String json = sp.homeVideoContent();
//                            LOG.i("echo--getHomeRecList :" + json);
                            return json;
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(20, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        } else {
                            callback.done(null);
                        }
                    }
                }
            };
            homeRecThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_detail")
                    .params("ac", sourceBean.getType() == 0 ? "videolist" : "detail")
                    .params("ids", TextUtils.join(",", ids))
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = response.body();
                                absXml = xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                absXml = json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }
    // detailContent
    public void getDetail(String sourceKey, String urlid) {
        getDetail(sourceKey, urlid, false);
    }

    public void getDetail(String sourceKey, String urlid, boolean fallback) {
        if (urlid.startsWith("push://") && ApiConfig.get().getSource(PUSH_AGENT) != null) {
            String pushUrl = urlid.substring(7);
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl);
            }
            sourceKey = isCastPushUrl(pushUrl) ? PUSH_FALLBACK : PUSH_AGENT;
            urlid = pushUrl;
        } else if (PUSH_AGENT.equals(sourceKey) && isCastPushUrl(urlid)) {
            sourceKey = PUSH_FALLBACK;
        }
        final String id = urlid;
        final String detailSourceKey = sourceKey;
    
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (isPushFallback(sourceKey, sourceBean)) {
            detailResult.postValue(createPushDetail(urlid, sourceKey));
            return;
        }
        if (sourceBean == null) {
            // 源已不存在(2026-09-13):典型场景 = 切到新源后加载完成前,从历史记录点进
            // 一条属于旧源(已失效 key)的条目;或订阅源被删。此处返回空 AbsXml(与末尾
            // 未知 type 分支同形状),详情页走空态,而不是在下面 sourceBean.getType() 处 NPE
            LOG.i("echo--getDetail--source-null--" + sourceKey);
            detailResult.postValue(createEmptyDetail(sourceKey));
            return;
        }
        if (NativeFanSource.isSupported(sourceKey)) {
            final String nativeSourceKey = sourceKey;
            final String nativeId = id;
            detailPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String nativeJson = NativeFanSource.detail(nativeSourceKey, nativeId);
                        json(detailResult, nativeJson, nativeSourceKey);
                    } catch (Throwable throwable) {
                        LOG.i("native-detail-error source=" + nativeSourceKey + " error=" + throwable.getMessage());
                        detailResult.postValue(createEmptyDetail(nativeSourceKey));
                    }
                }
            });
            return;
        }
        if (requiresBrowserSession(sourceKey)
                && !Cz4kBrowserSession.isReadyForUrl(browserLandingUrl())) {
            ensureBrowserSession(
                    browserLandingUrl(),
                    () -> getDetail(detailSourceKey, id, fallback));
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            // 详情请求使用独立准备池，避免慢 Spider 占满公共 spThreadPool，拖慢播放/动作任务。
            detailPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    Future<String> future = spiderTaskThreadPool.submit(new Callable<String>() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            List<String> ids = new ArrayList<>();
                            ids.add(id);
                            try {
//                                LOG.i("echo--getDetail--id: " + id);
                                return sp.detailContent(ids);
                            } catch (Exception e) {
                                LOG.i("echo--getDetail--error: " + e.getMessage());
                                return "";
                            }
                        }
                    });

                    String json = null;
                    try {
                        json = future.get(fallback ? 6 : 30, TimeUnit.SECONDS);
//                        LOG.i("echo--getDetail--result:" + json);
                    } catch (TimeoutException e) {
                        LOG.i("echo--getDetail--timeout");
                        future.cancel(true);
                    } catch (Exception e) {
                        LOG.i("echo--getDetail--error: " + e.getMessage());
                    } finally {
                        json(detailResult, json, sourceBean.getKey());
                    }
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            detailPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    String extend = sourceBean.getExt();
                    extend = fallback ? getFixUrl(extend, 6) : getFixUrl(extend, 8);

                    GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                            .tag("detail")
                            .params("ac", type == 0 ? "videolist" : "detail")
                            .params("ids", id);
                    // 当 extend 不为空且非空字符串时添加参数
                    if (extend != null && !extend.isEmpty()) {
                        request.params("extend", extend);
                    }
                    request.execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(detailResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                LOG.i(json);
                                json(detailResult, json, sourceBean.getKey());
                            }
                        }

                            @Override
                            public void onError(Response<String> response) {
                                super.onError(response);
                                json(detailResult, "", sourceBean.getKey());
                            }
                        });
                }
            });
        } else {
            detailResult.postValue(createEmptyDetail(sourceKey));
        }
    }

    /** 空详情(源不存在 / 未知 type):详情页按空态渲染,不带任何影片数据 */
    private static AbsXml createEmptyDetail(String sourceKey) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        return data;
    }

    public void action(String sourceKey, String action) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null || action == null) {
            actionResult.postValue(null);
            return;
        }
        if (sourceBean.getType() == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(sourceBean);
                        String json = sp.action(action);
                        actionResult.postValue(TextUtils.isEmpty(json) ? null : new JSONObject(json));
                    } catch (Throwable th) {
                        th.printStackTrace();
                        actionResult.postValue(null);
                    }
                }
            });
        } else {
            actionResult.postValue(null);
        }
    }

    // searchContent
    public void getSearch(String sourceKey, String wd) {
        getSearch(sourceKey, wd, "");
    }

    public void getSearch(String sourceKey, String wd, String searchToken) {
        getSearch(sourceKey, wd, searchToken, searchResult, "search");
    }

    private void getSearch(String sourceKey, String wd, String searchToken, MutableLiveData<AbsXml> result, String requestTag) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            postEmptySearchResult(result, sourceKey, searchToken);
            return;
        }
        if (NativeFanSource.isSupported(sourceKey)) {
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String nativeJson = NativeFanSource.search(sourceKey, wd);
                        json(result, nativeJson, sourceKey, searchToken, wd);
                    } catch (Throwable throwable) {
                        LOG.i("native-search-error source=" + sourceKey + " error=" + throwable.getMessage());
                        postSearchFailure(result, sourceKey, searchToken, wd);
                    }
                }
            });
            return;
        }
        if (requiresBrowserSession(sourceKey)
                && !Cz4kBrowserSession.isReadyForUrl(browserSearchUrl(wd))) {
            ensureBrowserSession(
                    browserSearchUrl(wd),
                    () -> getSearch(sourceKey, wd, searchToken, result, requestTag));
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                String search = sp.searchContent(wd, false);
                if(!TextUtils.isEmpty(search)){
                    json(result, search, sourceBean.getKey(), searchToken, wd);
                } else {
                    postSearchFailure(result, sourceBean.getKey(), searchToken, wd);
                }
            } catch (Throwable th) {
                th.printStackTrace();
                postSearchFailure(result, sourceBean.getKey(), searchToken, wd);
            }
        } else if (type == 0 || type == 1) {
            // 搜索源地址可能本身已经带有 source 等 query 参数(西瓜本地适配器就是这种形态)。
            // 先用 HttpUrl 组装完整 URL，避免 OkGo 在 GET 参数合并时覆盖/重复编码中文关键词。
            // 按原始 AVBox 约定发送:XML 源保留原始查询,JSON CMS 搜索使用 ac=detail。
            String searchUrl = buildSearchUrl(sourceBean.getApi(), wd, type == 1 ? "detail" : "");
            LOG.i("echo-search-request source=" + sourceBean.getKey()
                    + " url=" + searchUrl + " wd=" + wd);
            OkGo.<String>get(searchUrl)
                    .tag(requestTag)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            LOG.i("echo-search-success source=" + sourceBean.getKey()
                                    + " url=" + sourceBean.getApi()
                                    + " wd=" + wd
                                    + " bytes=" + (response.body() == null ? 0 : response.body().length()));
                            if (type == 0) {
                                String xml = response.body();
                                xml(result, xml, sourceBean.getKey(), searchToken, wd);
                            } else {
                                String json = response.body();
                                json(result, json, sourceBean.getKey(), searchToken, wd);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            LOG.i("echo-search-error source=" + sourceBean.getKey()
                                    + " url=" + sourceBean.getApi()
                                    + " wd=" + wd);
                            postSearchFailure(result, sourceBean.getKey(), searchToken, wd);
                        }
                    });
        }else if (type == 4) {
            final String searchWd = wd;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
            String extend=sourceBean.getExt();
            extend=getFixUrlDirect(extend);
            String queryWd = searchWd;
            try {
                queryWd=URLEncoder.encode(queryWd, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag(requestTag)
                    .params("wd", queryWd)
                    .params("ac" ,"detail")
                    .params("quick" ,"false");
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            LOG.i("echo-t4 search-网络请求错误");
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                            String json = response.body();
//                            LOG.i("echo-t4 search onSuccess"+json);
                            json(result, json, sourceBean.getKey(), searchToken, searchWd);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        LOG.i("echo-t4 search-onError");
                        super.onError(response);
                        postSearchFailure(result, sourceBean.getKey(), searchToken, searchWd);
                    }
                });
                }
            });
        } else {
            postSearchFailure(result, sourceBean.getKey(), searchToken, wd);
        }
    }

    /** 构造搜索 URL，同时保留配置地址里的 source、token 等固定参数。 */
    static String buildSearchUrl(String baseUrl, String keyword, String action) {
        try {
            HttpUrl.Builder builder = HttpUrl.get(baseUrl).newBuilder();
            builder.removeAllQueryParameters("wd");
            builder.removeAllQueryParameters("ac");
            builder.addQueryParameter("wd", keyword == null ? "" : keyword.trim());
            if (!TextUtils.isEmpty(action)) builder.addQueryParameter("ac", action);
            return builder.build().toString();
        } catch (Throwable th) {
            // 非标准自定义源仍沿用原始地址；绝大多数 XML/JSON 源都走上面的安全路径。
            LOG.i("echo-search-url-build-fallback source=" + baseUrl + " error=" + th.getMessage());
            return baseUrl;
        }
    }

    private String encodeCz4kQuery(String keyword) {
        try {
            return URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8")
                    .replace("+", "%20");
        } catch (UnsupportedEncodingException ignored) {
            return keyword == null ? "" : keyword;
        }
    }

    private void ensureBrowserSession(String url, Runnable retry) {
        mainHandler.post(() -> {
            android.app.Activity activity = null;
            try {
                if (App.getInstance() != null) activity = App.getInstance().getCurrentActivity();
            } catch (Throwable ignored) {
                // Application startup or an external caller may not have an Activity yet.
            }
            Cz4kBrowserSession.ensureReady(activity, url, retry);
        });
    }

    private boolean requiresBrowserSession(String sourceKey) {
        return "cz4k".equalsIgnoreCase(sourceKey);
    }

    private String browserLandingUrl() {
        return "https://www.cz4k.com/";
    }

    private String browserSearchUrl(String keyword) {
        return "https://www.cz4k.com/nimasile?q=" + encodeCz4kQuery(keyword);
    }

    // playerContent
    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        getPlayInternal(playRequestSeq, playResult, "play", sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    /** 下一集预解析（预载方案）:结果走 preloadResult 通道,seq 独立于真实播放请求,互不作废 */
    public void getPlayForPreload(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        getPlayInternal(preloadRequestSeq, preloadResult, "playPreload", sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    private void getPlayInternal(AtomicInteger seqHolder, MutableLiveData<JSONObject> resultChannel, String requestTag,
                                 String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        final int requestSeq = seqHolder.incrementAndGet();
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        boolean pushFallback = isPushFallback(sourceKey, sourceBean);
        PushUrl pushUrl = pushFallback ? parsePushUrl(url) : createPushUrl(url);
        String requestUrl = pushUrl.url;
        if (pushFallback) {
            postPlayResult(seqHolder, resultChannel, requestSeq, createPushPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag));
            return;
        }
        if (sourceBean == null) {
            // 源已不存在(2026-09-13):与 getDetail 同因(切源窗口期/源被删);
            // 走 null 结果 = 取流失败,播放器按"解析失败"处理,不再在下面 NPE
            LOG.i("echo--getPlay--source-null--" + sourceKey);
            postPlayResult(seqHolder, resultChannel, requestSeq, null);
            return;
        }
        if (NativeFanSource.isSupported(sourceKey)) {
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject result = NativeFanSource.play(sourceKey, playFlag, requestUrl);
                        result.put("key", url);
                        result.put("proKey", progressKey);
                        result.put("subtKey", subtitleKey);
                        if (!result.has("flag")) result.put("flag", playFlag);
                        postPlayResult(seqHolder, resultChannel, requestSeq, result);
                    } catch (Throwable throwable) {
                        LOG.i("native-play-error source=" + sourceKey + " error=" + throwable.getMessage());
                        postPlayResult(seqHolder, resultChannel, requestSeq, null);
                    }
                }
            });
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    Future<String> future = spiderTaskThreadPool.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            if (TextUtils.isEmpty(requestUrl)) return "";
                            try {
                                LOG.i("echo--getPlay--id: " + requestUrl);
                                return sp.playerContent(playFlag, requestUrl, ApiConfig.get().getVipParseFlags());
                            } catch (Exception e) {
                                LOG.i("echo--getPlay--error: " + e.getMessage());
                                return "";
                            }
                        }
                    });

                    try {
                        String json = future.get(sourceBean.getPlayTimeoutSeconds(), TimeUnit.SECONDS);
                        LOG.i("echo--getPlay--result:" + json);
                        // 处理返回的 JSON
                        if (!TextUtils.isEmpty(json)) {
                            JSONObject result = normalizePlayerResult(new JSONObject(json));
                            result.put("key", url);
                            mergePushHeaders(result, pushUrl);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            if (TextUtils.isEmpty(result.optString("url", "")) && shouldDirectPlay(sourceBean, requestUrl)) {
                                postPlayResult(seqHolder, resultChannel, requestSeq, createDirectPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag));
                            } else {
                                postPlayResult(seqHolder, resultChannel, requestSeq, result);
                            }
                        } else {
                            postPlayResult(seqHolder, resultChannel, requestSeq, null);
                        }
                    } catch (TimeoutException e) {
                        // 如果超时了，处理超时逻辑
                        LOG.i("echo--getPlay--timeout");
                        future.cancel(true);
                        postPlayResult(seqHolder, resultChannel, requestSeq, null);
                    } catch (Exception e) {
                        // 捕获其他异常
                        LOG.i("echo--getPlay--error: " + e.getMessage());
                        postPlayResult(seqHolder, resultChannel, requestSeq, null);
                    } finally {
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            JSONObject result = new JSONObject();
            try {
                result.put("key", url);
                String playbackUrl = normalizeCmsPlaybackUrl(sourceKey, requestUrl);
                String playUrl = sourceBean.getPlayerUrl().trim();
                // yparse 的令牌不是媒体 URL;把官方播放器页同时交给 type=0 嗅探器,
                // 否则空 playUrl 会让解析器收到空地址。
                if ("yparse".equals(sourceKey) && playUrl.isEmpty()
                        && !TextUtils.equals(playbackUrl, requestUrl)) {
                    playUrl = playbackUrl;
                }
                boolean directMedia = isDirectMediaUrl(playbackUrl);
                if (directMedia && playUrl.isEmpty()) {
                    result.put("parse", 0);
                    result.put("url", playbackUrl);
                } else {
                    result.put("parse", 1);
                    result.put("url", playbackUrl);
                }
                mergePushHeaders(result, pushUrl);
                mergeCmsHeaders(result, sourceBean);
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                result.put("playUrl", playUrl);
                result.put("flag", playFlag);
                postPlayResult(seqHolder, resultChannel, requestSeq, result);
            } catch (Throwable th) {
                th.printStackTrace();
                postPlayResult(seqHolder, resultChannel, requestSeq, null);
            }
        } else if (type == 4) {
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag(requestTag)
                    .params("play", requestUrl)
                    .params("flag" ,playFlag);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
                        LOG.i(json);
                        try {
                            JSONObject result = normalizePlayerResult(new JSONObject(json));
                            result.put("key", url);
                            mergePushHeaders(result, pushUrl);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            postPlayResult(seqHolder, resultChannel, requestSeq, result);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            postPlayResult(seqHolder, resultChannel, requestSeq, null);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        postPlayResult(seqHolder, resultChannel, requestSeq, null);
                    }
                });
        }else {
            postPlayResult(seqHolder, resultChannel, requestSeq, null);
        }
    }

    public void cancelPlayRequest() {
        playRequestSeq.incrementAndGet();
    }

    private boolean shouldDirectPlay(SourceBean sourceBean, String requestUrl) {
        return sourceBean != null
                && !TextUtils.isEmpty(requestUrl)
                && (requestUrl.startsWith("http://") || requestUrl.startsWith("https://"));
    }

    private boolean isDirectMediaUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return DefaultConfig.isVideoFormat(url)
                || lower.contains(".m3u8")
                || lower.contains(".mp4")
                || lower.contains(".flv")
                || lower.contains(".mpd")
                || lower.contains(".ts?");
    }

    /** 云解析接口返回的是 Yparse... 令牌,需交给其官方播放器页面再由解析器取流。 */
    static String normalizeCmsPlaybackUrl(String sourceKey, String rawUrl) {
        if (!"yparse".equals(sourceKey) || TextUtils.isEmpty(rawUrl)
                || rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            return rawUrl == null ? "" : rawUrl;
        }
        try {
            HttpUrl page = HttpUrl.get("https://jx.yparse.com/index.php");
            return page.newBuilder().addQueryParameter("url", rawUrl).build().toString();
        } catch (Throwable ignored) {
            return "https://jx.yparse.com/index.php?url=" + rawUrl;
        }
    }

    private JSONObject createDirectPlayResult(String rawUrl, PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("jx", 0);
            result.put("url", pushUrl.url);
            mergePushHeaders(result, pushUrl);
            LOG.i("echo--getPlay--direct:" + pushUrl.url);
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    private JSONObject normalizePlayerResult(JSONObject result) {
        if (result == null) return null;
        try {
            String playUrl = result.optString("playUrl", "");
            String url = result.optString("url", "");
            if (TextUtils.isEmpty(url)) return result;
            if (url.startsWith("[") && url.endsWith("]")) {
                JSONArray array = new JSONArray(url);
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (item instanceof String) {
                        String str = (String) item;
                        if (str.startsWith("proxy://")) {
                            str = DefaultConfig.checkReplaceProxy(str);
                            array.put(i, str);
                        } else if (str.startsWith("video://")) {
                            str = str.substring(8);
                            array.put(i, str);
                        }
                    }
                }
                result.put("url", array.toString());
                result.put("parse", 0);
                return result;
            }
            if (url.startsWith("video://")) {
                url = url.substring(8);
                result.put("url", url);
                result.put("parse", 1);
            } else if (url.startsWith("proxy://")) {
                url = DefaultConfig.checkReplaceProxy(url);
                result.put("url", url);
                result.put("parse", 0);
            } else if (playUrl.length() == 0
                    && DefaultConfig.isVideoFormat(url)
                    && !result.has("parse")
                    && !result.has("jx")) {
                result.put("parse", 0);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return result;
    }

    private boolean isPushFallback(String sourceKey, SourceBean sourceBean) {
        return PUSH_FALLBACK.equals(sourceKey) || (sourceBean != null && PUSH_AGENT.equals(sourceBean.getKey()) && sourceBean.getType() == -1);
    }

    private boolean isCastPushUrl(String url) {
        return !TextUtils.isEmpty(url) && url.contains(PUSH_HEADERS_MARKER);
    }

    private AbsXml createPushDetail(String url, String sourceKey) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        Movie movie = new Movie();
        movie.videoList = new ArrayList<>();
        Movie.Video video = new Movie.Video();
        video.id = url;
        video.name = url;
        video.type = "推送";
        video.sourceKey = sourceKey;
        video.urlBean = new Movie.Video.UrlBean();
        video.urlBean.infoList = new ArrayList<>();
        Movie.Video.UrlBean.UrlInfo urlInfo = new Movie.Video.UrlBean.UrlInfo();
        urlInfo.flag = "推送";
        urlInfo.urls = "播放$" + url;
        urlInfo.beanList = new ArrayList<>();
        urlInfo.beanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean("播放", url));
        video.urlBean.infoList.add(urlInfo);
        movie.videoList.add(video);
        data.movie = movie;
        return data;
    }

    private JSONObject createPushPlayResult(String rawUrl, PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("url", pushUrl.url);
            mergePushHeaders(result, pushUrl);
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    private void mergePushHeaders(JSONObject result, PushUrl pushUrl) {
        if (result == null || pushUrl == null || pushUrl.headers.isEmpty()) return;
        try {
            JSONObject header = result.optJSONObject("header");
            if (header == null) header = result.optJSONObject("headers");
            if (header == null) header = new JSONObject();
            for (String key : pushUrl.headers.keySet()) {
                header.put(key, pushUrl.headers.get(key));
            }
            result.put("header", header);
        } catch (Throwable th) {
            LOG.e("SourceViewModel", "merge push headers failed", th);
        }
    }

    /** CMS 直链常见 CDN 会校验 Referer；给多源 CMS 的直链统一补齐站点来源。 */
    private void mergeCmsHeaders(JSONObject result, SourceBean sourceBean) {
        if (result == null || sourceBean == null) return;
        String referer = cmsReferer(sourceBean.getKey());
        if (TextUtils.isEmpty(referer)) return;
        try {
            JSONObject header = result.optJSONObject("header");
            if (header == null) header = result.optJSONObject("headers");
            if (header == null) header = new JSONObject();
            if (!header.has("User-Agent")) {
                header.put("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            }
            if (!header.has("Referer")) header.put("Referer", referer);
            result.put("header", header);
        } catch (Throwable th) {
            LOG.e("merge CMS headers failed: " + th.getMessage());
        }
    }

    private String cmsReferer(String sourceKey) {
        return cmsRefererForSourceKey(sourceKey);
    }

    static String cmsRefererForSourceKey(String sourceKey) {
        if ("ffzy".equals(sourceKey)) return "https://ffzy5.tv/";
        if ("lzi".equals(sourceKey)) return "https://lzizy.net/";
        if ("sony".equals(sourceKey)) return "https://suonizy.net/";
        if ("hongniu".equals(sourceKey)) return "https://hongniuzy.com/";
        if ("wujin".equals(sourceKey)) return "https://wujinzy.com/";
        if ("jisu".equals(sourceKey)) return "https://jisuzy1.com/";
        if ("yaya".equals(sourceKey)) return "https://yayazy.com/";
        if ("niuniu".equals(sourceKey)) return "https://niuniuzy.cc/";
        if ("shandian".equals(sourceKey)) return "https://shandianzy.com/";
        if ("jinying".equals(sourceKey)) return "https://jinyingzy.com/";
        if ("yhzy".equals(sourceKey)) return "https://yhzy.cc/";
        if ("guangsu".equals(sourceKey)) return "https://www.guangsuzy.com/";
        if ("huya".equals(sourceKey)) return "https://huyazy.com/";
        if ("360zy".equals(sourceKey)) return "https://360zy.com/";
        if ("juliang".equals(sourceKey)) return "https://juliang.app/";
        if ("yparse".equals(sourceKey)) return "https://yparse.com/";
        if ("ikunzy".equals(sourceKey)) return "https://ikunzy.com/";
        if ("cz4k".equals(sourceKey)) return "https://www.cz4k.com/";
        if ("qiumu".equals(sourceKey)) return "https://old.sliiic.org/";
        if ("meitan".equals(sourceKey)) return "https://www.hmjy100.com/";
        if ("jingyan".equals(sourceKey)) return "https://www.wljingyan.com/";
        if ("kaida".equals(sourceKey)) return "https://www.kdndt.net/";
        if ("shuicao".equals(sourceKey)) return "http://www.knshuichuli.com/";
        if ("hongguo".equals(sourceKey)) return "https://m.syshuoyuan.com/";
        if ("laoniu".equals(sourceKey)) return "https://shanghai.365che.net/";
        if ("ningmeng".equals(sourceKey)) return "https://www.nmgflw.com/";
        if ("xingchen".equals(sourceKey)) return "http://www.chgxdq.com/";
        if ("qingpingguo".equals(sourceKey)) return "https://yz.sdhfcj.com/";
        if ("zhongxing".equals(sourceKey)) return "https://www.sczxsx.com/";
        if ("haier".equals(sourceKey)) return "https://www.nbhaierxin.com/";
        if ("huilv".equals(sourceKey)) return "https://www.zzhuilv.cn/";
        if ("shiji".equals(sourceKey)) return "http://m.sjtx-oa.com/";
        if ("cechi".equals(sourceKey)) return "http://www.lanyajc.com/";
        if ("kuaikan".equals(sourceKey)) return "http://www.ikang.org/";
        if ("wushuang".equals(sourceKey)) return "http://www.fjsycjs.com/";
        if ("zanpian".equals(sourceKey)) return "https://www.zanpian.org/";
        if ("ys88".equals(sourceKey)) return "https://www.88ystv.com/";
        return "";
    }

    private PushUrl createPushUrl(String rawUrl) {
        PushUrl pushUrl = new PushUrl();
        pushUrl.url = rawUrl == null ? "" : rawUrl;
        return pushUrl;
    }

    private PushUrl parsePushUrl(String rawUrl) {
        PushUrl pushUrl = createPushUrl(rawUrl);
        parseMarkedHeaders(pushUrl);
        return pushUrl;
    }

    private boolean parseMarkedHeaders(PushUrl pushUrl) {
        String marker = PUSH_HEADERS_MARKER;
        int start = pushUrl.url.indexOf(marker);
        if (start < 0) return false;
        int valueStart = start + marker.length();
        int end = pushUrl.url.indexOf('@', valueStart);
        if (end < 0) return false;
        try {
            String text = URLDecoder.decode(pushUrl.url.substring(valueStart, end), "UTF-8");
            JSONObject json = new JSONObject(text);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, "");
                if (!TextUtils.isEmpty(key)) pushUrl.headers.put(key, value);
            }
            pushUrl.url = pushUrl.url.substring(0, start) + pushUrl.url.substring(end + 1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static class PushUrl {
        String url = "";
        HashMap<String, String> headers = new HashMap<>();
    }

    private void postPlayResult(AtomicInteger seqHolder, MutableLiveData<JSONObject> resultChannel, int requestSeq, JSONObject result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (requestSeq != seqHolder.get()) {
                    LOG.i("echo--getPlay--ignore stale result");
                    return;
                }
                resultChannel.setValue(result);
            }
        });
    }

    private static final ConcurrentHashMap<String, String> extendCache = new ConcurrentHashMap<>();

    private String getFixUrl(final String extend) {
        return getFixUrl(extend, 20);
    }

    private String getFixUrl(final String extend, final long timeoutSeconds) {
        if (TextUtils.isEmpty(extend)) return "";
        if(!extend.startsWith("http"))return extend;
        final String key = MD5.string2MD5(extend);
        if (extendCache.containsKey(key)) {
            LOG.i("echo-getFixUrl Cache");
            return extendCache.get(key);
        }
        Future<String> future = fixUrlThreadPool.submit(new Callable<String>() {
            @Override
            public String call() {
                String result = extend;
                if (extend.startsWith("http://127.0.0.1")) {
                    String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                    path = path.replaceAll("localhost/", "/");
                    result = FileUtils.readFileToString(path, "UTF-8");
                    result = tryMinifyJson(result);
                    extendCache.putIfAbsent(key, result);
                } else if (extend.startsWith("http")) {
                    result = OkHttp.string(extend, null);
                    if (!result.isEmpty()) {
                        result = tryMinifyJson(result);
                        if(result.length()>2500)result = extend;
                        extendCache.putIfAbsent(key, result);
                    }
                }
                return result;
            }
        });

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            te.printStackTrace();
            future.cancel(true);
            return extend;
        } catch (Exception e) {
            e.printStackTrace();
            return extend;
        }
    }

    private String tryMinifyJson(String raw) {
        try {
            raw = raw.trim();
            JsonElement jsonElement = JsonParser.parseString(raw);
            return gson.toJson(jsonElement);
        } catch (Exception e) {
            return raw;
        }
    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        // 上游 filter 缺 key/name/value 时 getAsString/getAsJsonArray 会直接抛异常,
        // 导致整个分类菜单加载失败、筛选区域空白;这里改为缺字段即整体跳过。
        if (obj == null || !obj.has("key") || !obj.has("name") || !obj.has("value")) return null;
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv;
        try {
            kv = obj.getAsJsonArray("value");
        } catch (Throwable th) {
            return null;
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        // 2026-09-10 BugFix:必须存 (v → n),与 FilterSheet 消费约定一致(显示 map value=显示名 n,
        // 选中发送 map key=筛选值 v);原 put(n, v) 写反,导致胶囊显示英文 v 且发错筛选值
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_value = ele_obj.has("v") ? ele_obj.get("v").getAsString() : "";
            String values_name = ele_obj.has("n") ? ele_obj.get("n").getAsString() : "";
            values.put(values_value, values_name);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            if (TextUtils.isEmpty(json)) {
                return new AbsSortJson().toAbsSortXml();
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = gson.fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            MovieSort.SortFilter parsed = getSortFilter(one.getAsJsonObject());
                            if (parsed != null) {
                                sortFilter.add(parsed);
                            }
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                MovieSort.SortFilter parsed = getSortFilter(ele.getAsJsonObject());
                                if (parsed != null) {
                                    sortFilter.add(parsed);
                                }
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    if (data.classes != null && data.classes.sortList != null) {
                        for (MovieSort.SortData sort : data.classes.sortList) {
                            if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                                sort.filters = sortFilters.get(sort.id);
                            }
                        }
                    }
                }
            } catch (Throwable th) {
                LOG.d("SourceViewModel", "sort filters parse failed, continue without filters");
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            XStream xstream = sortXStream.get();
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void absXml(AbsXml data, String sourceKey) {
        absXml(data, sourceKey, "");
    }

    private void absXml(AbsXml data, String sourceKey, String searchToken) {
        data.sourceKey = sourceKey;
        data.searchToken = searchToken;
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
//                        for (String s : str) {
//                            if (s.contains("$")) {
//                                String[] ss = s.split("\\$");
//                                if (ss.length >= 2) {
//                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
//                                }
//                                //infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(s.substring(0, s.indexOf("$")), s.substring(s.indexOf("$") + 1)));
//                            }
//                        }
                        for (String s : str) {
                            String[] ss = s.split("\\$", 2);
                            if (ss.length > 0) {
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                } else {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                video.sourceKey = sourceKey;
            }
        }
    }
    
    private AbsXml checkPush(AbsXml data) {
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() > 0) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() > 0) {
                for (int i = 0; i < video.urlBean.infoList.size(); i++) {
                    Movie.Video.UrlBean.UrlInfo urlinfo = video.urlBean.infoList.get(i);
                    if (urlinfo != null && urlinfo.beanList != null && !urlinfo.beanList.isEmpty()) {
                        for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlinfo.beanList) {
                            if (infoBean.url.startsWith("push://")) {
                                String pushUrl = infoBean.url.substring(7);
                                if (pushUrl.startsWith("b64:")) {
                                    try {
                                        pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                                    } catch (UnsupportedEncodingException e) {
                                        e.printStackTrace();
                                    }
                                } else {
                                    pushUrl = URLDecoder.decode(pushUrl);
                                }

                                final AbsXml[] resData = {null};

                                final CountDownLatch countDownLatch = new CountDownLatch(1);
                                String finalPushUrl = pushUrl;
                                spiderTaskThreadPool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        SourceBean sb = ApiConfig.get().getSource("push_agent");
                                        if (sb == null) {
                                            countDownLatch.countDown();
                                            return;
                                        }
                                        if (sb.getType() == 4) {
                                            OkGo.<String>get(sb.getApi())
                                                    .tag("detail")
                                                    .params("ac","detail")
                                                    .params("ids", finalPushUrl)
                                                    .execute(new AbsCallback<String>() {
                                                        @Override
                                                        public String convertResponse(okhttp3.Response response) throws Throwable {
                                                            if (response.body() != null) {
                                                                return response.body().string();
                                                            } else {
                                                                return "";
                                                            }
                                                        }

                                                        @Override
                                                        public void onSuccess(Response<String> response) {
                                                            String res = response.body();
                                                            if (!TextUtils.isEmpty(res)) {
                                                                try {
                                                                    AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {
                                                                    }.getType());
                                                                    resData[0] = absJson.toAbsXml();
                                                                    absXml(resData[0], sb.getKey());
                                                                } catch (Exception e) {
                                                                    e.printStackTrace();
                                                                }
                                                            }
                                                            countDownLatch.countDown();
                                                        }

                                                        @Override
                                                        public void onError(Response<String> response) {
                                                            super.onError(response);
                                                            countDownLatch.countDown();
                                                        }
                                                    });
                                        } else {
                                            try {
                                                Spider sp = ApiConfig.get().getCSP(sb);
                                             //   ApiConfig.get().setPlayJarKey(sb.getJar());
                                                List<String> ids = new ArrayList<>();
                                                ids.add(finalPushUrl);
                                                String res = sp.detailContent(ids);
                                                if (!TextUtils.isEmpty(res)) {
                                                    try {
                                                        AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {}.getType());
                                                        resData[0] = absJson.toAbsXml();
                                                        absXml(resData[0], sb.getKey());
                                                    } catch (Exception e) {
                                                        e.printStackTrace();
                                                    }
                                                }
                                            } catch (Throwable th) {
                                                th.printStackTrace();
                                            }
                                            countDownLatch.countDown();
                                        }
                                    }
                                });
                                try {
                                    countDownLatch.await(15, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                if (resData[0] != null) {
                                    AbsXml res = resData[0];
                                    if (res.movie != null && res.movie.videoList != null && res.movie.videoList.size() > 0) {
                                        Movie.Video resVideo = res.movie.videoList.get(0);
                                        if (resVideo != null && resVideo.urlBean != null && resVideo.urlBean.infoList != null && resVideo.urlBean.infoList.size() > 0) {
                                            if (urlinfo.beanList.size() == 1) {
                                                video.urlBean.infoList.remove(i);
                                            } else {
                                                urlinfo.beanList.remove(infoBean);
                                            }
                                            for (Movie.Video.UrlBean.UrlInfo resUrlinfo : resVideo.urlBean.infoList) {
                                                if (resUrlinfo != null && resUrlinfo.beanList != null && !resUrlinfo.beanList.isEmpty()) {
                                                    video.urlBean.infoList.add(resUrlinfo);
                                                }
                                            }
                                            video.sourceKey = "push_agent";
                                            return data;
                                        }
                                    }
                                }
                                infoBean.name = "解析失败 >>> " + infoBean.name;
                            }
                        }
                    }
                }
            }
        }
        return data;
    }

    public void checkThunder(AbsXml data, int index) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
                boolean hasThunder=false;
                thunderLoop:
                for (int idx=0;idx<video.urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if(Thunder.isSupportUrl(infoBean.url)){
                            hasThunder=true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                // Thunder 解析回调里的 index/0 没有边界保护,index 与
                                // infoList 数量不一致时会 IndexOutOfBounds 崩详情页。
                                if (!video.urlBean.infoList.isEmpty()
                                        && !video.urlBean.infoList.get(0).beanList.isEmpty()) {
                                    video.urlBean.infoList.get(0).beanList.get(0).name = info;
                                }
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                String playList=urlMap.get(key);
                                if (key < 0 || key >= video.urlBean.infoList.size()) continue;
                                video.urlBean.infoList.get(key).urls = playList;
                                String[] str = playList.split("#");
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$", 2);

                                        if (ss.length > 0) {
                                            if (ss.length >= 2) {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                            } else {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                            }
                                        }
                                    }
                                }
                                video.urlBean.infoList.get(key).beanList = infoBeanList; // key 已在循环开头边界检查
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse && index==0) {
            detailResult.postValue(data);
        }
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        return xml(result, xml, sourceKey, "");
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey, String searchToken) {
        return xml(result, xml, sourceKey, searchToken, "");
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey,
                       String searchToken, String searchQuery) {
        try {
            XStream xstream = listXStream.get();
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            absXml(data, sourceKey, searchToken);
            if (searchResult == result) {
                OfflineContentCache.putSearch(sourceKey, searchQuery, data);
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                	data = checkPush(data);
                    checkThunder(data,0);
                }else {
                    postSearchResult(result, data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                if (!TextUtils.isEmpty(searchQuery)) {
                    postSearchFailure(result, sourceKey, searchToken, searchQuery);
                } else {
                    postEmptySearchResult(result, sourceKey, searchToken);
                }
            } else if (result != null) {
                if (result == detailResult) {
                    AbsXml data = new AbsXml();
                    data.sourceKey = sourceKey;
                    result.postValue(data);
                } else {
                    result.postValue(null);
                }
            }
            return null;
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        return json(result, json, sourceKey, "");
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey, String searchToken) {
        return json(result, json, sourceKey, searchToken, "");
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey,
                        String searchToken, String searchQuery) {
        try {
            // 测试数据
//            json = "{\n" +
//                    "\t\"list\": [{\n" +
//                    "\t\t\"vod_id\": \"137133\",\n" +
//                    "\t\t\"vod_name\": \"磁力测试\",\n" +
//                    "\t\t\"vod_pic\": \"https:/img9.doubanio.com/view/photo/s_ratio_poster/public/p2656327176.webp\",\n" +
//                    "\t\t\"type_name\": \"剧情 / 爱情 / 古装\",\n" +
//                    "\t\t\"vod_year\": \"2022\",\n" +
//                    "\t\t\"vod_area\": \"中国大陆\",\n" +
//                    "\t\t\"vod_remarks\": \"40集全\",\n" +
//                    "\t\t\"vod_actor\": \"刘亦菲\",\n" +
//                    "\t\t\"vod_director\": \"杨阳\",\n" +
//                    "\t\t\"vod_content\": \"　　在钱塘开茶铺的赵盼儿（刘亦菲 饰）惊闻未婚夫、新科探花欧阳旭（徐海乔 饰）要另娶当朝高官之女，不甘命运的她誓要上京讨个公道。在途中她遇到了出自权门但生性正直的皇城司指挥顾千帆（陈晓 饰），并卷入江南一场大案，两人不打不相识从而结缘。赵盼儿凭借智慧解救了被骗婚而惨遭虐待的“江南第一琵琶高手”宋引章（林允 饰）与被苛刻家人逼得离家出走的豪爽厨娘孙三娘（柳岩 饰），三位姐妹从此结伴同行，终抵汴京，见识世间繁华。为了不被另攀高枝的欧阳旭从东京赶走，赵盼儿与宋引章、孙三娘一起历经艰辛，将小小茶坊一步步发展为汴京最大的酒楼，揭露了负心人的真面目，收获了各自的真挚感情和人生感悟，也为无数平凡女子推开了一扇平等救赎之门。\",\n" +
//                    "\t\t\"vod_play_from\": \"磁力测试\",\n" +
//                    "\t\t\"vod_play_url\": \"0$magnet:?xt=urn:btih:e398ca38fb9d64897ed19b4d16efeea11af4d03b\"\n" +
//                    "\t}]\n" +
//                    "}";
            AbsJson absJson = gson.fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey, searchToken);
            if (searchResult == result) {
                OfflineContentCache.putSearch(sourceKey, searchQuery, data);
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                	data = checkPush(data);
                    checkThunder(data,0);
                }else {
                    postSearchResult(result, data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                if (!TextUtils.isEmpty(searchQuery)) {
                    postSearchFailure(result, sourceKey, searchToken, searchQuery);
                } else {
                    postEmptySearchResult(result, sourceKey, searchToken);
                }
            } else if (result != null) {
                if (result == detailResult) {
                    AbsXml data = new AbsXml();
                    data.sourceKey = sourceKey;
                    result.postValue(data);
                } else {
                    result.postValue(null);
                }
            }
            return null;
        }
    }

    private String getFixUrlDirect(final String extend) {
        if (TextUtils.isEmpty(extend)) return "";
        if (!extend.startsWith("http")) return extend;
        final String key = MD5.string2MD5(extend);
        if (extendCache.containsKey(key)) {
            return extendCache.get(key);
        }
        String result = extend;
        try {
            if (extend.startsWith("http://127.0.0.1")) {
                String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                path = path.replaceAll("localhost/", "/");
                result = FileUtils.readFileToString(path, "UTF-8");
                result = tryMinifyJson(result);
                extendCache.putIfAbsent(key, result);
            } else {
                result = OkHttp.string(extend, null);
                if (!TextUtils.isEmpty(result)) {
                    result = tryMinifyJson(result);
                    if (result.length() > 2500) result = extend;
                    extendCache.putIfAbsent(key, result);
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
            return extend;
        }
        return result;
    }

    private void postSearchFailure(MutableLiveData<AbsXml> result, String sourceKey,
                                   String searchToken, String keyword) {
        AbsXml cached = OfflineContentCache.getSearch(sourceKey, keyword);
        if (cached != null && cached.movie != null && cached.movie.videoList != null
                && !cached.movie.videoList.isEmpty()) {
            cached.sourceKey = sourceKey;
            cached.searchToken = searchToken;
            for (Movie.Video video : cached.movie.videoList) {
                if (video != null) video.sourceKey = sourceKey;
            }
            LOG.i("offline-search-fallback source=" + sourceKey + " keyword=" + keyword);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, cached));
            } else {
                postSearchResult(result, cached);
            }
            return;
        }
        postEmptySearchResult(result, sourceKey, searchToken);
    }

    private void postEmptySearchResult(MutableLiveData<AbsXml> result, String sourceKey, String searchToken) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        data.searchToken = searchToken;
        if (searchResult == result) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
        } else if (result != null) {
            postSearchResult(result, data);
        }
    }

    private void postSearchResult(final MutableLiveData<AbsXml> result, final AbsXml data) {
        result.postValue(data);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
    }
}
