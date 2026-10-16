package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie
import java.text.Normalizer
import java.util.Locale

/**
 * 18+ 内容过滤器。
 *
 * <p>过滤开关只保存在当前进程内存中，默认开启。这样 4848 只会影响本次运行，
 * 应用进程重新启动后自然恢复过滤，不会留下持久化的关闭状态。</p>
 */
object AdultContentFilter {
    private val directPattern = Regex("(伦理片|理论片|伦理|情色|成人|限制级|18\\s*\\+)", RegexOption.IGNORE_CASE)
    private val trailingNoise = Regex(
        "(高清|超清|蓝光|1080p|2160p|4k|hd|全集|完结|中字|国语|粤语|双语)+$",
        RegexOption.IGNORE_CASE,
    )
    private val punctuationTypes: Set<Int> = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt(),
    )

    @Volatile
    private var sessionEnabled = true

    fun isEnabled(): Boolean = sessionEnabled

    fun disableForSession() {
        sessionEnabled = false
    }

    fun isDirectAdult(video: Movie.Video?): Boolean {
        if (video == null) return false
        return matchesDirect(video.name) || matchesDirect(video.type)
    }

    fun filter(videos: List<Movie.Video>): List<Movie.Video> =
        if (!sessionEnabled) videos else videos.filterNot(::isDirectAdult)

    /** 收集本轮搜索中被明确标记为成人内容的标题，用于跨源同名过滤。 */
    fun collectAdultTitleKeys(videos: Iterable<Movie.Video>): Set<String> =
        videos.asSequence()
            .filter(::isDirectAdult)
            .mapNotNull { normalizeTitle(it.name) }
            .toSet()

    /** 先过滤直接命中，再过滤被其它资源站标记为成人内容的同名条目。 */
    fun filterByAdultTitles(
        videos: List<Movie.Video>,
        adultTitleKeys: Set<String>,
        keyword: String,
    ): List<Movie.Video> {
        if (!sessionEnabled) return videos
        val keywordKey = normalizeTitle(keyword).orEmpty().takeIf { it.length >= 2 }
        return videos.filterNot { video ->
            if (isDirectAdult(video)) return@filterNot true
            val titleKey = normalizeTitle(video.name) ?: return@filterNot false
            if (titleKey in adultTitleKeys) return@filterNot true
            keywordKey != null && titleKey.contains(keywordKey) && adultTitleKeys.any {
                it.contains(keywordKey)
            }
        }
    }

    /** 用于跨源匹配的标题键：统一全角字符、大小写、空白、标点和常见采集站后缀。 */
    fun normalizeTitle(value: String?): String? {
        val source = value?.trim().orEmpty()
        if (source.isEmpty()) return null
        val normalized = Normalizer.normalize(source, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val compact = buildString(normalized.length) {
            normalized.forEach { ch ->
                if (!ch.isWhitespace() && !punctuationTypes.contains(Character.getType(ch))) append(ch)
            }
        }
        val cleaned = compact.replace(trailingNoise, "")
        return cleaned.takeIf { it.length >= 2 }
    }

    private fun matchesDirect(value: String?): Boolean =
        !value.isNullOrBlank() && directPattern.containsMatchIn(value)

    internal fun resetForTests() {
        sessionEnabled = true
    }
}
