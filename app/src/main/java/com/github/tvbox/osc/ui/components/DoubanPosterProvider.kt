package com.github.tvbox.osc.ui.components

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 豆瓣公开建议接口 provider。
 *
 * 该接口不需要 API key。请求和图片 URL 都保持豆瓣直连，避免把海报交给第三方代理服务。
 */
class DoubanPosterProvider(
    private val client: OkHttpClient = directClient(),
    private val endpoint: String = DEFAULT_ENDPOINT,
) : PosterProvider, VodImages.ExternalPosterProvider {

    /** 兼容 VodImages 的异步补图接口；该接口没有年份字段时只使用标题和条目标识。 */
    override fun findPoster(lookup: VodImages.PosterLookup): String? =
        findPoster(lookup.title, null, lookup.id)

    override fun findPoster(title: String?, year: Int?, doubanId: String?): String? {
        return try {
            val normalizedId = extractDoubanId(doubanId)
            if (normalizedId != null) {
                val byId = requestSuggestions(normalizedId)
                selectCandidate(byId, title, year, normalizedId)?.imageUrl?.let { return it }
            }

            val query = title?.trim().orEmpty()
            if (query.isEmpty()) return null
            selectCandidate(requestSuggestions(query), query, year, null)?.imageUrl
        } catch (_: Throwable) {
            // 补图是可选能力，网络、解析和 URL 异常都不能传到 UI。
            null
        }
    }

    private fun requestSuggestions(query: String): List<Suggestion> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://movie.douban.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            return parseSuggestions(body)
        }
    }

    internal data class Suggestion(
        val title: String,
        val year: Int,
        val imageUrl: String?,
        val id: String,
    )

    internal fun parseSuggestions(json: String): List<Suggestion> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            val array = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.get("subjects")?.isJsonArray == true ->
                    root.asJsonObject.getAsJsonArray("subjects")
                root.isJsonObject && root.asJsonObject.get("data")?.isJsonArray == true ->
                    root.asJsonObject.getAsJsonArray("data")
                else -> return emptyList()
            }
            parseSuggestionArray(array)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseSuggestionArray(array: JsonArray): List<Suggestion> = buildList {
        for (element in array) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val title = stringValue(obj.get("title"))
            val id = stringValue(obj.get("id"))
            val image = cleanImageUrl(
                stringValue(obj.get("img")).ifEmpty { stringValue(obj.get("cover")) },
            )
            if (title.isEmpty() && id.isEmpty()) continue
            add(
                Suggestion(
                    title = title,
                    year = parseYear(stringValue(obj.get("year"))),
                    imageUrl = image,
                    id = id,
                ),
            )
        }
    }

    internal fun selectCandidate(
        candidates: List<Suggestion>,
        requestedTitle: String?,
        requestedYear: Int?,
        requestedDoubanId: String?,
    ): Suggestion? {
        if (candidates.isEmpty()) return null
        val wantedId = extractDoubanId(requestedDoubanId)
        if (wantedId != null) {
            candidates.firstOrNull { extractDoubanId(it.id) == wantedId && it.imageUrl != null }?.let {
                return it
            }
        }

        val title = normalizeTitle(requestedTitle)
        if (title.isEmpty()) return null
        return candidates
            .asSequence()
            .mapNotNull { candidate ->
                if (candidate.imageUrl == null) return@mapNotNull null
                val titleScore = titleScore(title, normalizeTitle(candidate.title))
                if (titleScore == 0) return@mapNotNull null
                val yearScore = if (requestedYear != null && requestedYear > 0 &&
                    candidate.year == requestedYear
                ) 20 else 0
                candidate to (titleScore + yearScore)
            }
            .maxWithOrNull(compareBy<Pair<Suggestion, Int>> { it.second }.thenBy { it.first.title })
            ?.first
    }

    internal fun cleanImageUrl(raw: String?): String? {
        val value = raw
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.trim()
            .orEmpty()
        if (value.isEmpty()) return null

        val absolute = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value
            else -> "https://$value"
        }
        return try {
            val parsed = absolute.toHttpUrl()
            if (parsed.scheme != "http" && parsed.scheme != "https") return null
            if (!isDoubanImageHost(parsed.host)) return null
            parsed.newBuilder().scheme("https").build().toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun titleScore(requested: String, candidate: String): Int = when {
        requested == candidate -> 100
        candidate.startsWith(requested) || requested.startsWith(candidate) -> 65
        candidate.contains(requested) || requested.contains(candidate) -> 45
        else -> 0
    }

    private fun normalizeTitle(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }

    private fun extractDoubanId(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val subjectId = SUBJECT_ID_PATTERN.find(raw)?.groupValues?.getOrNull(1)
        return (subjectId ?: raw.takeIf { it.all(Char::isDigit) })?.takeIf { it.isNotEmpty() }
    }

    private fun parseYear(value: String): Int = YEAR_PATTERN.find(value)?.value?.toIntOrNull() ?: 0

    private fun stringValue(value: JsonElement?): String = try {
        if (value == null || value.isJsonNull) "" else value.asString.trim()
    } catch (_: Throwable) {
        ""
    }

    private fun isDoubanImageHost(host: String): Boolean =
        host.matches(Regex("img(?:\\d+)?\\.doubanio\\.com", RegexOption.IGNORE_CASE)) ||
            host.matches(Regex("img(?:\\d+)?\\.douban\\.com", RegexOption.IGNORE_CASE))

    companion object {
        const val DEFAULT_ENDPOINT = "https://movie.douban.com/j/subject_suggest"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        private val YEAR_PATTERN = Regex("\\d{4}")
        private val SUBJECT_ID_PATTERN = Regex("(?:movie\\.douban\\.com/)?subject/(\\d+)")

        fun directClient(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(Dns.SYSTEM)
            .retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
