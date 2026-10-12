package com.github.tvbox.osc.ui.components

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 无密钥的有限海报备用源。
 *
 * 这里只读取公开 JSON 中的图片字段，不返回详情、播放或条目地址。TVMaze
 * 是首选；它失败或没有合法图片时才尝试 IMDb suggestion。每个源的异常
 * 都被隔离，避免备用源反过来影响豆瓣结果。
 */
class PublicPosterProvider(
    private val requestJson: (String) -> String? = { url -> defaultClientGet(url) },
) : VodImages.ExternalPosterProvider {

    override fun findPoster(lookup: VodImages.PosterLookup): String? {
        val title = lookup.title.trim()
        if (title.isEmpty()) return null

        val sourceUrls = listOf(
            tvMazeUrl(title),
            imdbSuggestionUrl(title),
        )
        for (url in sourceUrls) {
            val poster = try {
                parsePosterUrl(requestJson(url), url)
            } catch (_: Throwable) {
                null
            }
            if (poster != null) return poster
        }
        return null
    }

    private fun tvMazeUrl(title: String): String =
        "https://api.tvmaze.com/singlesearch/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", title)
            .addQueryParameter("embed", "images")
            .build()
            .toString()

    private fun imdbSuggestionUrl(title: String): String =
        "https://v3.sg.media-imdb.com/suggestion/x".toHttpUrl().newBuilder()
            .addPathSegment("$title.json")
            .build()
            .toString()

    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 1200L
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(Dns.SYSTEM)
                .retryOnConnectionFailure(false)
                .connectTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .writeTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .build()
        }

        private fun defaultClientGet(url: String): String? {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "cc-tv/PosterFallback")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                return response.body.string()
            }
        }

        /** 只接受带明确图片扩展名的 HTTP(S) 地址，拒绝播放/详情类地址。 */
        internal fun isExplicitImageUrl(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            val candidate = value.trim()
            if (candidate.contains(".m3u8", ignoreCase = true) ||
                candidate.contains(".mp4", ignoreCase = true) ||
                candidate.contains("/play", ignoreCase = true) ||
                candidate.contains("/detail", ignoreCase = true) ||
                candidate.startsWith("data:", ignoreCase = true)
            ) return false
            return try {
                val url = candidate.toHttpUrl()
                val path = url.encodedPath.lowercase(Locale.ROOT)
                (url.scheme == "http" || url.scheme == "https") &&
                    url.host.isNotEmpty() &&
                    IMAGE_EXTENSIONS.any { path.endsWith(it) }
            } catch (_: Throwable) {
                false
            }
        }

        internal fun parsePosterUrl(json: String?, sourceUrl: String = ""): String? {
            if (json.isNullOrBlank()) return null
            val root = JsonParser.parseString(json)
            val candidates = if (sourceUrl.contains("tvmaze", ignoreCase = true)) {
                listOf(
                    root.getObjectString("image", "original"),
                    root.getObjectString("image", "medium"),
                )
            } else {
                root.getArrayObjectString("d", "i", "imageUrl")
            }
            return candidates.firstOrNull(::isExplicitImageUrl)?.trim()
        }

        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".avif")

        private fun JsonElement?.getObjectString(vararg keys: String): String? {
            var current = this ?: return null
            for (key in keys) {
                if (!current.isJsonObject || !current.asJsonObject.has(key)) return null
                current = current.asJsonObject.get(key)
            }
            return current.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }

        private fun JsonElement?.getArrayObjectString(vararg keys: String): List<String> {
            if (this == null || !isJsonObject || !asJsonObject.has(keys.first())) return emptyList()
            val array = asJsonObject.getAsJsonArray(keys.first())
            return array.mapNotNull { item ->
                var current: JsonElement = item
                for (key in keys.drop(1)) {
                    if (!current.isJsonObject || !current.asJsonObject.has(key)) return@mapNotNull null
                    current = current.asJsonObject.get(key)
                }
                current.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
        }
    }
}
