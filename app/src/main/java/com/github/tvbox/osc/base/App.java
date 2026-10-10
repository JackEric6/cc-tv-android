package com.github.tvbox.osc.base;

import android.app.Activity;
import android.app.Application;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends Application {
    private static final String LEGACY_DEFAULT_API_URL =
            "https://ghproxy.net/https://raw.githubusercontent.com/JackEric6/movie/refs/heads/xgzy-config-20260922/movie2_xgzy_all";
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 启动看门狗(2026-09-21):装崩溃记录器。必须最早装 —— 第三方爬虫可能在
        // Application.onCreate 之后的任意时刻于自己的线程上闪退,晚了就记不到。
        com.github.tvbox.osc.util.BootGuard.install();
        initParams();
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        // 共享缓存容量(第二期扩展):设置项 → player 模块(须在首次 getSharedCache 前注入,改动重启 App 生效)
        ExoMediaSourceHelper.setSharedCacheSizeBytes(
                Math.max(128, KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT)) * 1024L * 1024L);
        QuickJSLoader.init();
        // Coil 单例:海报地址约定的请求头注入(Compose UI 图片管线)
        com.github.tvbox.osc.ui.components.VodImages.INSTANCE.init(this);
        // 缺图时异步补图:豆瓣优先,公开海报源仅作为回退,不阻塞首页/搜索首屏。
        com.github.tvbox.osc.ui.components.VodImages.INSTANCE.registerDefaultExternalPosterProviders();
        FileUtils.cleanPlayerCache();
        // 「清除缓存」遗留的 Exo 视频缓存清理(2026-09-13):clearCache 不直接删该目录(进程级 SimpleCache
        // 常驻,直删会"内存索引/磁盘失配"),改为启动早期删除 —— 必须早于首次 getSharedCache,且目录删除
        // 属耗时 IO,放后台线程(无待清理标记时仅一次 exists() 检查,零开销)
        new Thread(FileUtils::purgeExoCacheIfPending, "exo-cache-purge").start();
    }

    private void initParams() {
        // KV 存储(2026-09-13 起为唯一实现,取代 Hawk):初始化必须在任何 KV 读写之前
        KV.init(this);
        // 首次安装直接使用内置西瓜配置；升级时只替换本项目旧版内置配置地址。
        // 用户后来在设置页填写的其他地址必须保留。
        String apiUrl = KV.get(HawkConfig.API_URL, "").trim();
        boolean migrated = KV.get(HawkConfig.DEFAULT_API_MIGRATION, false);
        if (shouldReplaceApiUrlWithBundledDefault(apiUrl, migrated)) {
            KV.put(HawkConfig.API_URL, HawkConfig.DEFAULT_API_URL);
        }
        KV.put(HawkConfig.DEFAULT_API_MIGRATION, true);
        KV.put(HawkConfig.PLAYER_IS_LIVE, false);
        if (!KV.contains(HawkConfig.M3U8_PURIFY)) {
            KV.put(HawkConfig.M3U8_PURIFY, HawkConfig.M3U8_PURIFY_DEFAULT);
        }
        if (!KV.contains(HawkConfig.IJK_CODEC)) {
            KV.put(HawkConfig.IJK_CODEC, HawkConfig.DEFAULT_DECODE_MODE);
        }
        if (!KV.contains(HawkConfig.EXO_DECODE)) {
            KV.put(HawkConfig.EXO_DECODE, HawkConfig.DEFAULT_DECODE_MODE);
        }
        if (!KV.contains(HawkConfig.PLAY_TYPE)) {
            KV.put(HawkConfig.PLAY_TYPE, 2);
        } else if (KV.get(HawkConfig.PLAY_TYPE, 2) == 0) {
            KV.put(HawkConfig.PLAY_TYPE, 2);
        }
    }

    static boolean shouldReplaceApiUrlWithBundledDefault(String apiUrl, boolean migrationDone) {
        if (apiUrl == null || apiUrl.trim().isEmpty()) return true;
        return !migrationDone && isLegacyBundledMovieConfig(apiUrl);
    }

    private static boolean isLegacyBundledMovieConfig(String apiUrl) {
        return LEGACY_DEFAULT_API_URL.equalsIgnoreCase(apiUrl.trim());
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}
