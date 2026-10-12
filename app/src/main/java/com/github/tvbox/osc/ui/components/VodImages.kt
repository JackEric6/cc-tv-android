package com.github.tvbox.osc.ui.components

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.IOException
import java.io.File
import java.net.Proxy
import java.net.URLDecoder
import java.text.Normalizer
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

object VodImages {

    private const val PIC_HTTP_CACHE_MB = 250L
    private const val PIC_MAX_RETRIES = 2
    private const val PIC_IMAGE_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private const val PIC_IMAGE_ACCEPT =
        "image/avif,image/webp,image/apng,image/jpeg,image/*,*/*;q=0.8"
    // 外部补图是可选扩展:默认没有 provider,不会产生任何外部请求。
    private const val EXTERNAL_POSTER_CACHE_CAPACITY = 256
    private const val EXTERNAL_POSTER_MAX_CONCURRENCY = 2
    private const val EXTERNAL_POSTER_QUEUE_CAPACITY = 16
    // 豆瓣 subject_suggest 一次查询需 connect(2s)+read(3s),1500ms 的超时
    // 会在 worker 完成前就取消并缓存 15 分钟失败,导致补图几乎永远不生效。
    private const val EXTERNAL_POSTER_TIMEOUT_MILLIS = 4000L
    private const val EXTERNAL_POSTER_SUCCESS_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val EXTERNAL_POSTER_FAILURE_TTL_MILLIS = 15L * 60L * 1000L
    private const val SHARED_POSTER_CACHE_CAPACITY = 512
    private const val SHARED_POSTER_SUCCESS_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val SHARED_POSTER_CANDIDATE_CAPACITY = 4
    private var picCacheDir: File? = null

    data class PosterLookup(val source: String, val id: String, val title: String)

    /** 外部 provider 只负责按标题/资源 ID 返回海报 URL,不得返回播放或详情数据。 */
    fun interface ExternalPosterProvider {
        fun findPoster(lookup: PosterLookup): String?
    }

    private data class PosterCacheEntry(val poster: String?, val expiresAt: Long)

    private val externalPosterCache = object : LinkedHashMap<String, PosterCacheEntry>(
        EXTERNAL_POSTER_CACHE_CAPACITY, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PosterCacheEntry>?): Boolean =
            size > EXTERNAL_POSTER_CACHE_CAPACITY
    }
    private val externalPosterInFlight = HashMap<String, Unit>()
    private val externalPosterLock = Any()
    /** 已由资源站图片请求成功验证过的同名海报，只共享图片 URL，不共享详情或播放数据。 */
    private val sharedPosterCache = object : LinkedHashMap<String, PosterCacheEntry>(
        SHARED_POSTER_CACHE_CAPACITY, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PosterCacheEntry>?): Boolean =
            size > SHARED_POSTER_CACHE_CAPACITY
    }
    /**
     * 已从资源站返回、但尚未被图片解码器验证的候选海报。
     * 候选只用于阻止重复的外部补图请求；只有图片 onSuccess 后才会进入 sharedPosterCache。
     */
    private val sharedPosterCandidates = LinkedHashMap<String, LinkedHashMap<String, Unit>>()
    private val _verifiedPosterUpdates = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** 已验证海报的标题更新通知，UI 只需据此重新读取本地共享缓存。 */
    val verifiedPosterUpdates: SharedFlow<String> = _verifiedPosterUpdates
    private val externalPosterExecutor = ThreadPoolExecutor(
        EXTERNAL_POSTER_MAX_CONCURRENCY,
        EXTERNAL_POSTER_MAX_CONCURRENCY,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(EXTERNAL_POSTER_QUEUE_CAPACITY),
        daemonThreadFactory("poster-fallback"),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val externalPosterTimeouts = ScheduledThreadPoolExecutor(
        1,
        daemonThreadFactory("poster-timeout"),
    ).apply {
        removeOnCancelPolicy = true
    }
    @Volatile
    private var externalPosterProvider: ExternalPosterProvider? = null
    @Volatile
    private var externalPosterProviderEnabled = false

    fun init(context: Context) {
        picCacheDir = File(context.cacheDir, "pic_http_cache")
        SingletonImageLoader.setSafe { appContext ->
            ImageLoader.Builder(appContext)
                .components {
                    add(com.github.tvbox.osc.util.CoilBridge.okhttpFetcher { picClient() })
                }
                .crossfade(true)
                .build()
        }
    }

    /**
     * 注册可插拔的外部海报 provider。默认关闭；调用方必须显式传入 enabled=true。
     * provider 只应请求海报，不应跨资源站读取播放/详情内容。
     */
    fun setExternalPosterProvider(provider: ExternalPosterProvider?, enabled: Boolean) {
        externalPosterProvider = provider
        externalPosterProviderEnabled = enabled && provider != null
        if (!externalPosterProviderEnabled) clearExternalPosterState()
    }

    /**
     * 组装有序的海报 provider 链。每个 provider 都是独立失败的；只有返回可用图片地址
     * 才会停止继续尝试，从而保证豆瓣优先、公开备用源只在豆瓣未命中时执行。
     */
    fun composeExternalPosterProviders(vararg providers: ExternalPosterProvider): ExternalPosterProvider =
        ExternalPosterProvider { lookup ->
            providers.firstNotNullOfOrNull { provider ->
                try {
                    provider.findPoster(lookup)?.trim()?.takeIf(::isUsablePosterUrl)
                } catch (error: Throwable) {
                    LOG.d("VodImages", "external poster provider failed: ${error.message}")
                    null
                }
            }
        }

    /** Application startup registration: Douban is the primary source, public provider is fallback. */
    fun registerDefaultExternalPosterProviders() {
        val douban = DoubanPosterProvider()
        val public = PublicPosterProvider()
        setExternalPosterProvider(
            composeExternalPosterProviders(
                ExternalPosterProvider { lookup -> douban.findPoster(lookup) },
                ExternalPosterProvider { lookup -> public.findPoster(lookup) },
            ),
            true,
        )
    }

    /** UI 只在真正注册了 provider 时才启动补图轮询，避免空海报制造无效协程。 */
    fun isExternalPosterProviderEnabled(): Boolean = externalPosterProviderEnabled

    /**
     * 记录一个已被图片加载器验证成功的海报，供首页和搜索页的同名影视复用。
     * 空标题、无效地址和数据 URI 不会进入共享缓存。
     */
    fun rememberVerifiedPoster(title: String?, poster: String?) {
        val key = normalizePosterTitle(title)
        val value = normalizePosterUrlForSharing(poster)
        if (key.isEmpty() || !isUsablePosterUrl(value)) return
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(externalPosterLock) {
            val previous = sharedPosterCache[key]
            changed = previous == null || previous.poster != value || previous.expiresAt <= now
            sharedPosterCache[key] = PosterCacheEntry(
                value,
                now + SHARED_POSTER_SUCCESS_TTL_MILLIS,
            )
            sharedPosterCandidates.remove(key)
        }
        if (changed) _verifiedPosterUpdates.tryEmit(key)
    }

    /**
     * 登记资源站刚返回的海报地址。候选不会被当作已验证海报广播，
     * 但能让同名卡片直接复用该地址，避免在首张图片尚未解码时启动外部 provider。
     */
    fun rememberPosterCandidate(title: String?, poster: String?) {
        val key = normalizePosterTitle(title)
        val value = normalizePosterUrlForSharing(poster)
        if (key.isEmpty() || !isUsablePosterUrl(value)) return
        synchronized(externalPosterLock) {
            val candidates = sharedPosterCandidates.getOrPut(key) { LinkedHashMap() }
            candidates.remove(value)
            candidates[value] = Unit
            while (candidates.size > SHARED_POSTER_CANDIDATE_CAPACITY) {
                candidates.entries.firstOrNull()?.let { candidates.remove(it.key) }
            }
        }
    }

    /** 读取尚未验证的同名候选；调用方必须在图片成功后升级或在失败后移除。 */
    private fun candidatePosterForTitle(title: String?): String {
        val key = normalizePosterTitle(title)
        if (key.isEmpty()) return ""
        synchronized(externalPosterLock) {
            val candidates = sharedPosterCandidates[key] ?: return ""
            return candidates.keys.firstOrNull().orEmpty()
        }
    }

    /** 图片请求失败时只移除对应候选，不影响同名的其他资源站候选或已验证海报。 */
    fun forgetPosterCandidate(title: String?, poster: String?) {
        val key = normalizePosterTitle(title)
        val value = normalizePosterUrlForSharing(poster)
        if (key.isEmpty() || value.isEmpty()) return
        synchronized(externalPosterLock) {
            val candidates = sharedPosterCandidates[key] ?: return
            candidates.remove(value)
            if (candidates.isEmpty()) sharedPosterCandidates.remove(key)
        }
    }

    /** 仅查询同名共享缓存，不触发任何网络请求。 */
    fun sharedPosterForTitle(title: String?): String {
        val key = normalizePosterTitle(title)
        if (key.isEmpty()) return ""
        synchronized(externalPosterLock) {
            val cached = sharedPosterCache[key] ?: return ""
            if (cached.expiresAt > System.currentTimeMillis()) return cached.poster.orEmpty()
            sharedPosterCache.remove(key)
            return ""
        }
    }

    /** 搜索结果只接受当前源直连海报或已经验证过的同名跨源共享海报。 */
    fun hasValidSearchPoster(currentPoster: String?, title: String?): Boolean =
        (!currentPoster.isNullOrBlank() && isUsablePosterUrl(currentPoster)) ||
            sharedPosterForTitle(title).isNotBlank()

    /** 标题仅用于海报匹配：统一全半角、大小写、空白和标点，避免跨站标题格式差异。 */
    internal fun normalizePosterTitle(title: String?): String {
        val normalized = Normalizer.normalize(title.orEmpty(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return normalized.replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    }

    /**
     * 空海报时同步读缓存，缓存未命中则后台排队并立即返回空串。
     * 非空海报会原样返回，保证已有海报绝不触发 provider。
     */
    fun resolvePosterOrSchedule(
        currentPoster: String?,
        source: String?,
        id: String?,
        title: String?,
    ): String {
        if (!currentPoster.isNullOrBlank()) return currentPoster
        val lookup = PosterLookup(source.orEmpty().trim(), id.orEmpty().trim(), title.orEmpty().trim())
        if (lookup.id.isEmpty() && lookup.title.isEmpty()) return ""
        // 同名资源站海报共享优先于豆瓣等外部补图，且完全不发起网络请求。
        sharedPosterForTitle(lookup.title).takeIf { it.isNotBlank() }?.let { return it }
        // 其他资源站已经返回同名图片时，先复用候选地址，等待图片加载结果决定是否升级。
        candidatePosterForTitle(lookup.title).takeIf { it.isNotBlank() }?.let { return it }
        if (!externalPosterProviderEnabled) return ""
        val provider = externalPosterProvider ?: return ""
        val key = posterLookupKey(lookup)
        val now = System.currentTimeMillis()
        synchronized(externalPosterLock) {
            val cached = externalPosterCache[key]
            if (cached != null) {
                if (cached.expiresAt > now) return cached.poster.orEmpty()
                externalPosterCache.remove(key)
            }
            if (externalPosterInFlight.containsKey(key)) return ""
            externalPosterInFlight[key] = Unit
        }
        scheduleExternalPosterLookup(key, lookup, provider)
        return ""
    }

    private fun scheduleExternalPosterLookup(
        key: String,
        lookup: PosterLookup,
        provider: ExternalPosterProvider,
    ) {
        val completed = AtomicBoolean(false)
        val workerRef = AtomicReference<Future<*>?>(null)
        var timeout: ScheduledFuture<*>? = null
        val finish: (String?) -> Unit = { poster ->
            if (completed.compareAndSet(false, true)) {
                if (poster != null && isUsablePosterUrl(poster)) {
                    // 外部补图同样是已验证的可用图片，应立即供其他同名资源站复用。
                    rememberVerifiedPoster(lookup.title, poster.trim())
                    // 先写同名缓存再写按 source/id 缓存，避免 UI 读到后者后在清理时
                    // 被尚未结束的 worker 重新写回共享缓存。
                    rememberExternalPoster(key, poster.trim(), EXTERNAL_POSTER_SUCCESS_TTL_MILLIS)
                } else {
                    rememberExternalPoster(key, null, EXTERNAL_POSTER_FAILURE_TTL_MILLIS)
                }
                synchronized(externalPosterLock) { externalPosterInFlight.remove(key) }
                timeout?.cancel(false)
            }
        }
        try {
            val worker = externalPosterExecutor.submit {
                val poster = try {
                    provider.findPoster(lookup)
                } catch (error: Throwable) {
                    LOG.d("VodImages", "external poster provider failed: ${error.message}")
                    null
                }
                finish(poster)
            }
            workerRef.set(worker)
            timeout = externalPosterTimeouts.schedule({
                if (completed.compareAndSet(false, true)) {
                    workerRef.get()?.cancel(true)
                    rememberExternalPoster(key, null, EXTERNAL_POSTER_FAILURE_TTL_MILLIS)
                    synchronized(externalPosterLock) { externalPosterInFlight.remove(key) }
                }
            }, EXTERNAL_POSTER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: RejectedExecutionException) {
            // 队列满时任务根本没运行,不能缓存 15 分钟失败,否则一次突发
            // 会把整批海报都标成"无图"(缓存投毒)。只撤销 in-flight,
            // 让 UI 的下一次轮询在队列腾出后重新提交。
            synchronized(externalPosterLock) { externalPosterInFlight.remove(key) }
        }
    }

    private fun rememberExternalPoster(key: String, poster: String?, ttlMillis: Long) {
        synchronized(externalPosterLock) {
            externalPosterCache[key] = PosterCacheEntry(poster, System.currentTimeMillis() + ttlMillis)
        }
    }

    private fun clearExternalPosterState() {
        synchronized(externalPosterLock) {
            externalPosterCache.clear()
            externalPosterInFlight.clear()
            sharedPosterCache.clear()
            sharedPosterCandidates.clear()
        }
    }

    private fun posterLookupKey(lookup: PosterLookup): String =
        lookup.source.lowercase().trim() + "\u0000" + lookup.id.lowercase().trim() +
            "\u0000" + lookup.title.lowercase().trim()

    private fun normalizePosterUrlForSharing(value: String?): String {
        val candidate = value?.trim().orEmpty()
        if (candidate.isEmpty() || candidate.startsWith("data:", ignoreCase = true)) return ""
        return parseVodPicUrl(candidate)?.first?.trim().orEmpty().ifEmpty { candidate }
    }

    private fun isUsablePosterUrl(value: String): Boolean {
        val candidate = normalizePosterUrlForSharing(value)
        if (candidate.isEmpty() || candidate.startsWith("data:", ignoreCase = true)) return false
        if (candidate.contains(".m3u8", ignoreCase = true) ||
            candidate.contains(".mp4", ignoreCase = true) ||
            candidate.contains("/play", ignoreCase = true)
        ) return false
        return try {
            val url = candidate.toHttpUrl()
            (url.scheme == "http" || url.scheme == "https") && url.host.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun daemonThreadFactory(prefix: String): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "cc-tv-$prefix").apply { isDaemon = true }
    }

    private fun picClient(): OkHttpClient = OkHttpClient.Builder()
        // 图片请求必须与搜索请求解耦:不继承全局代理、DoH、DNS 和拦截器。
        .proxy(Proxy.NO_PROXY)
        .dns(Dns.SYSTEM)
        // 海报是独立的 Coil 管线,避免复用全局客户端的低同主机并发把网格图片排队。
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        .cache(picCacheDir?.let { Cache(it, PIC_HTTP_CACHE_MB * 1024L * 1024L) })
        .retryOnConnectionFailure(false)
        // 先规范 URL/请求头,备用客户端也要拿到同一份已剥离元数据的请求。
        .addInterceptor(picHeaderInterceptor)
        // 直连链路失败后才切换到应用默认客户端,正常直连不会等待备用网络。
        .addInterceptor(picFallbackInterceptor)
        .addInterceptor(picRetryInterceptor)
        .addNetworkInterceptor(picForceCacheInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val picFallbackInterceptor = Interceptor { chain ->
        val fallback = OkGoHelper.getDefaultClient()
            ?.newBuilder()
            ?.retryOnConnectionFailure(false)
            ?.build()
        executeWithPicFallback(
            primary = { chain.proceed(chain.request()) },
            fallback = if (chain.call().isCanceled() || fallback == null) null else {
                { fallback.newCall(chain.request()).execute() }
            },
            shouldFallback = { response -> shouldFallbackPicResponse(response.code) },
            discard = { response -> response.close() },
        )
    }

    private val picRetryInterceptor = Interceptor { chain ->
        executePicRequest(chain)
    }

    private fun executePicRequest(chain: Interceptor.Chain): Response {
        var retries = 0
        while (retries <= PIC_MAX_RETRIES) {
            try {
                val response = chain.proceed(chain.request())
                if (response.code !in 500..599 || retries >= PIC_MAX_RETRIES) {
                    return response
                }
                response.close()
            } catch (error: IOException) {
                if (chain.call().isCanceled() || retries >= PIC_MAX_RETRIES) throw error
            }
            retries++
        }
        throw IOException("picture retry loop unexpectedly terminated")
    }

    internal fun shouldFallbackPicResponse(code: Int): Boolean =
        code == 403 || code == 408 || code == 429 || code in 500..599

    internal fun <T> executeWithPicFallback(
        primary: () -> T,
        fallback: (() -> T)?,
        shouldFallback: (T) -> Boolean = { false },
        discard: (T) -> Unit = {},
    ): T {
        val primaryResult = try {
            primary()
        } catch (error: IOException) {
            if (fallback == null) throw error
            return try {
                fallback()
            } catch (fallbackError: IOException) {
                fallbackError.addSuppressed(error)
                throw fallbackError
            }
        }
        if (fallback == null || !shouldFallback(primaryResult)) return primaryResult
        discard(primaryResult)
        return fallback()
    }

    private val picForceCacheInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val cc = response.header("Cache-Control")
        if (cc != null && (cc.contains("no-store") || cc.contains("no-cache") || cc.contains("max-age=0"))) {
            response.newBuilder()
                .removeHeader("Cache-Control")
                .removeHeader("Pragma")
                .header("Cache-Control", "public, max-age=604800")
                .build()
        } else {
            response
        }
    }

    private val picHeaderInterceptor = Interceptor { chain ->
        val request = chain.request()
        val parsed = parseVodPicUrl(request.url.toString())
        val builder = request.newBuilder()
        if (parsed != null) {
            val (url, headers) = parsed
            builder.url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
        }
        // 部分图床会拒绝 okhttp 默认 UA 或缺少图片 Accept 的请求。
        builder.header("User-Agent", PIC_IMAGE_UA)
        builder.header("Accept", PIC_IMAGE_ACCEPT)
        val host = builder.build().url.host.lowercase()
        if (host == "vod.xiguazyimg.com" || host.endsWith(".xiguazyimg.com")) {
            // 西瓜图片 CDN 对 Android 默认请求头不稳定，补齐网页端 UA/Referer。
            builder.header("User-Agent", PIC_IMAGE_UA)
            builder.header("Referer", "https://xgzy.tv/")
            builder.header("Accept", PIC_IMAGE_ACCEPT)
        }
        chain.proceed(builder.build())
    }

    internal fun parseVodPicUrl(raw: String): Pair<String, Map<String, String>>? {
        if (raw.startsWith("data:")) return null

        // 只有明确的扩展字段才是本项目的 @ 元数据分隔符；普通 URL
        // 可能合法包含 @（例如 userinfo），不能按任意 @ 截断。
        val markers = listOf("@Headers=", "@Cookie=", "@User-Agent=", "@Referer=")
        val firstMarker = markers.map { raw.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return null
        val url = raw.substring(0, firstMarker)
        if (url.isEmpty()) return null

        val headers = LinkedHashMap<String, String>()
        fun grab(marker: String): String? {
            val start = raw.indexOf(marker)
            if (start < 0) return null
            val valueStart = start + marker.length
            val next = markers.map { raw.indexOf(it, valueStart) }
                .filter { it >= 0 }
                .minOrNull() ?: raw.length
            return raw.substring(valueStart, next)
        }

        fun put(k: String, v: String?) {
            if (!v.isNullOrEmpty()) headers[k] = decodeMetadataValue(v)
        }
        grab("@Headers=")?.let { json ->
            try {
                val decoded = URLDecoder.decode(json, "UTF-8")
                val obj = Gson().fromJson(decoded, JsonObject::class.java)
                for (key in obj.keySet()) put(key, obj.get(key).asString)
            } catch (ignored: Throwable) {
                LOG.d("VodImages", "pic url @Headers decode failed, skip extra headers")
            }
        }
        put("Cookie", grab("@Cookie="))
        put("User-Agent", grab("@User-Agent="))
        put("Referer", grab("@Referer="))
        return url to headers
    }

    private fun decodeMetadataValue(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (ignored: IllegalArgumentException) {
        value
    }
}
