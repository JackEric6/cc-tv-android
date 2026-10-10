package com.github.tvbox.osc.util.live

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import kotlin.coroutines.resume

private const val UINT32_MASK = 0xffff_ffffL
private const val CHECKSUM_MASK = 0x7fff_ffffL

/** Gets a fresh anonymous Yangshipin HLS URL when a channel is selected; URLs are never persisted. */
internal object YspLiveResolver {
    private const val API_URL = "https://bkliveinfo.ysp.cctv.cn/"
    private const val PLATFORM = 4330403
    private const val APP_VERSION = "V8.22.1035.3031"
    private const val REFERER = "https://live.cctv.cn/"
    private const val USER_AGENT = "qqlive"
    private val gson = Gson()
    private val random = SecureRandom()
    private val ckeyTeaKey = hex("59b2f7cf725ef43c34fdd7c123411ed3")
    private val guardTeaKey = hex("110dbec10c23e7d2e56a1cad6914ef1b")
    private val ckeyXor = hex("842eed08f066e6ea48b4caa991ed6ff3")
    private val guardXor = hex("b3c953a06913ad4d")
    private val prefetchedUrls = YspPrefetchCache()
    private val fallbackUrls = YspFallbackQueue()
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(source: String): String? {
        val candidates = prefetchedUrls.takeCandidates(source) ?: resolveFresh(source) ?: return null
        fallbackUrls.set(source, candidates.drop(1))
        return candidates.firstOrNull()
    }

    suspend fun prefetch(source: String) {
        val urls = resolveFresh(source) ?: return
        prefetchedUrls.putCandidates(source, urls)
    }

    fun nextFallback(source: String): String? = fallbackUrls.next(source)

    fun clearFallbacks(source: String) = fallbackUrls.clear(source)

    private suspend fun resolveFresh(source: String): List<String>? {
        val uri = runCatching { URI(source) }.getOrNull() ?: return null
        if (uri.scheme != "ysp") return null
        val channelId = uri.host ?: return null
        val channel = YspLiveChannelCatalog.find(channelId) ?: return null
        repeat(2) {
            val requestChannel = channel.copy(definition = qualityForSource(source, channel.definition))
            val request = runCatching { buildRequest(requestChannel) }.getOrNull() ?: return null
            val body = executeRequest(request) ?: return@repeat
            val urls = parsePlayUrls(body)
            if (urls.isNotEmpty()) return urls
        }
        return null
    }

    internal fun qualityForSource(source: String, defaultQuality: String): String {
        val query = runCatching { URI(source).rawQuery }.getOrNull().orEmpty()
        val quality = query.split('&')
            .firstOrNull { it.startsWith("quality=") }
            ?.substringAfter('=')
        return quality?.takeIf { it == "fhd" || it == "shd" } ?: defaultQuality
    }

    private fun buildRequest(channel: YspLiveChannel): Request {
        val now = System.currentTimeMillis()
        val ticket = createTicket(channel.channelId, now)
        val capability = Base64.getEncoder().encodeToString(
            "H(30:1080,60:1080|30:1080,60:1080)".toByteArray(Charsets.UTF_8),
        )
        val url = API_URL.toHttpUrl().newBuilder().apply {
            addQueryParameter("atime", "120")
            addQueryParameter("livepid", channel.livePid)
            addQueryParameter("cnlid", channel.channelId)
            addQueryParameter("appVer", APP_VERSION)
            addQueryParameter("app_version", "300090")
            addQueryParameter("caplv", "1")
            addQueryParameter("cmd", "2")
            addQueryParameter("defn", channel.definition)
            addQueryParameter("device", "iPhone")
            addQueryParameter("encryptVer", "4.2")
            addQueryParameter("getpreviewinfo", "0")
            addQueryParameter("hevclv", "0")
            addQueryParameter("lang", "zh-Hans_CN")
            addQueryParameter("livequeue", "0")
            addQueryParameter("logintype", "1")
            addQueryParameter("nettype", "1")
            addQueryParameter("newnettype", "1")
            addQueryParameter("newplatform", PLATFORM.toString())
            addQueryParameter("platform", PLATFORM.toString())
            addQueryParameter("sdtfrom", "v3021")
            addQueryParameter("spacode", "23")
            addQueryParameter("spaudio", "1")
            addQueryParameter("spdemuxer", "6")
            addQueryParameter("spdrm", "2")
            addQueryParameter("spdynamicrange", "1")
            addQueryParameter("spflv", "1")
            addQueryParameter("spflvaudio", "1")
            addQueryParameter("sphdrfps", "60")
            addQueryParameter("sphttps", "1")
            addQueryParameter("spvcode", capability)
            addQueryParameter("spvideo", "4")
            addQueryParameter("stream", "1")
            addQueryParameter("system", "1")
            addQueryParameter("sysver", "ios18.2.1")
            addQueryParameter("uhd_flag", "0")
            addQueryParameter("cKey", ticket.cKey)
            addQueryParameter("guid", ticket.guid)
            addQueryParameter("fntick", ticket.timestamp.toString())
            addQueryParameter("flowid", ticket.flowId)
            addQueryParameter("playbacktime", "0")
        }.build()
        return Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
    }

    private suspend fun executeRequest(request: Request): String? = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use {
                    if (it.isSuccessful) it.body?.string() else null
                }
                if (continuation.isActive) continuation.resume(body)
            }
        })
    }

    internal fun parsePlayUrls(body: String): List<String> = runCatching {
        val json = gson.fromJson(body, JsonObject::class.java)
        if (json.get("iretcode")?.asInt != 0) return emptyList()
        val urls = ArrayList<String>()
        json.get("playurl")?.takeIf { it.isJsonPrimitive }?.asString?.let(urls::add)
        json.getAsJsonArray("backurl_list")?.forEach { item ->
            val candidate = if (item.isJsonPrimitive) item.asString else item.asJsonObject.get("url")?.asString
            if (!candidate.isNullOrBlank()) urls.add(candidate)
        }
        urls.filter(::isOfficialMediaUrl).distinct()
    }.getOrDefault(emptyList())

    private fun isOfficialMediaUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.US) ?: return false
        uri.scheme == "https" && (
            host == "cctv.cn" || host.endsWith(".cctv.cn") ||
                host == "ysp.cctv.cn" || host.endsWith(".ysp.cctv.cn") ||
                host == "cctv.com" || host.endsWith(".cctv.com")
            )
    }.getOrDefault(false)

    private data class Ticket(val cKey: String, val guid: String, val timestamp: Long, val flowId: String)

    private fun createTicket(channelId: String, nowMillis: Long): Ticket {
        fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
        val timestamp = nowMillis / 1000L
        val guid = randomBytes(16).toHex()
        val uid = randomBytes(4).toHex().uppercase(Locale.US)
        return createTicket(channelId, timestamp, guid, uid, ::randomBytes, "${UUID.randomUUID()}_$PLATFORM")
    }

    internal fun createCKeyForTest(
        channelId: String,
        nowMillis: Long,
        guid: String,
        uid: String,
        randomBytes: (Int) -> ByteArray,
    ): String = createTicket(channelId, nowMillis / 1000L, guid, uid, randomBytes, "test_$PLATFORM").cKey

    private fun createTicket(
        channelId: String,
        timestamp: Long,
        guid: String,
        uid: String,
        randomBytes: (Int) -> ByteArray,
        flowId: String,
    ): Ticket {
        val guard = createGuard(timestamp, guid, randomBytes)
        val body = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(hex("0000004200000004000004d2"))
                out.writeUInt32(PLATFORM.toLong())
                out.writeUInt32(0)
                out.writeUInt32(timestamp)
                out.writeLengthPrefixed("dcgh")
                out.writeLengthPrefixed("_zj1A5Gh6QYcxWjIUGos2w==")
                out.writeLengthPrefixed(APP_VERSION)
                out.writeLengthPrefixed(channelId)
                out.writeLengthPrefixed(guid)
                out.writeUInt32(1)
                out.writeUInt32(1)
                out.writeLengthPrefixed(uid)
                out.writeLengthPrefixed("nil")
                out.writeLengthPrefixed("57eab0c4-2c58-44c6-8ae9-dd2757525dc5")
                out.writeLengthPrefixed("nil")
                out.writeLengthPrefixed("v0.1.000")
                out.writeLengthPrefixed("com.cctv.yangshipin.app.iphone")
                out.writeLengthPrefixed(PLATFORM.toString())
                out.writeLengthPrefixed("ex_json_bus")
                out.writeLengthPrefixed("ex_json_vs")
                out.writeLengthPrefixed(guard)
            }
            bytes.toByteArray()
        }
        val packet = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeShort(body.size)
                out.write(body)
            }
            bytes.toByteArray()
        }.also { writeUInt32(it, 18, checksum(it).toLong()) }
        val encrypted = encryptTeaPacket(packet, ckeyTeaKey, randomBytes) + uint32(checksum(packet).toLong())
        encrypted.indices.forEach { encrypted[it] = (encrypted[it].toInt() xor (ckeyXor[it % ckeyXor.size].toInt() and 0xff)).toByte() }
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(encrypted)
            .replace('+', '_')
            .replace('/', '-')
        return Ticket("--01$encoded", guid, timestamp, flowId.uppercase(Locale.US))
    }

    private fun createGuard(timestamp: Long, guid: String, randomBytes: (Int) -> ByteArray): String {
        val body = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUInt32(timestamp)
                out.writeLengthPrefixed(guid.takeLast(5))
                out.writeLengthPrefixed("")
                out.writeLengthPrefixed("")
                out.writeLengthPrefixed("-1")
            }
            bytes.toByteArray()
        }
        val plain = lengthPrefixed(body)
        val encrypted = encryptTeaPacket(plain, guardTeaKey, randomBytes) + uint32(checksum(plain).toLong())
        encrypted.indices.forEach { encrypted[it] = (encrypted[it].toInt() xor (guardXor[it % guardXor.size].toInt() and 0xff)).toByte() }
        return encrypted.toHex().uppercase(Locale.US)
    }

    private fun encryptTeaPacket(input: ByteArray, key: ByteArray, randomBytes: (Int) -> ByteArray): ByteArray {
        val padLength = (8 - ((input.size + 10) % 8)) % 8
        val plain = ByteArray(1 + padLength + 2 + input.size + 7)
        plain[0] = ((randomBytes(1)[0].toInt() and 0xff and 0xf8) or padLength).toByte()
        if (padLength > 0) randomBytes(padLength).copyInto(plain, 1)
        randomBytes(2).copyInto(plain, 1 + padLength)
        input.copyInto(plain, 3 + padLength)

        val output = ByteArray(plain.size)
        var previousPlain = ByteArray(8)
        var previousCipher = ByteArray(8)
        for (offset in plain.indices step 8) {
            val mixed = ByteArray(8) { index -> (plain[offset + index].toInt() xor previousCipher[index].toInt()).toByte() }
            val encrypted = teaEncryptBlock(mixed, key)
            val cipher = ByteArray(8) { index -> (encrypted[index].toInt() xor previousPlain[index].toInt()).toByte() }
            cipher.copyInto(output, offset)
            previousPlain = mixed
            previousCipher = cipher
        }
        return output
    }

    private fun teaEncryptBlock(block: ByteArray, key: ByteArray): ByteArray {
        var y = readUInt32(block, 0)
        var z = readUInt32(block, 4)
        val k = LongArray(4) { readUInt32(key, it * 4) }
        var sum = 0L
        repeat(16) {
            sum = (sum + 0x9e3779b9L) and UINT32_MASK
            y = (y + ((((z shl 4) + k[0]) xor (z + sum) xor ((z ushr 5) + k[1])) and UINT32_MASK)) and UINT32_MASK
            z = (z + ((((y shl 4) + k[2]) xor (y + sum) xor ((y ushr 5) + k[3])) and UINT32_MASK)) and UINT32_MASK
        }
        return uint32(y) + uint32(z)
    }

    private fun checksum(data: ByteArray): Int {
        var value = 0L
        data.forEach { value = (0x83L * value + (it.toInt() and 0xff)) and CHECKSUM_MASK }
        return value.toInt()
    }

    private fun DataOutputStream.writeUInt32(value: Long) = write(uint32(value))

    private fun DataOutputStream.writeLengthPrefixed(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun lengthPrefixed(value: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(value.size)
            out.write(value)
        }
        bytes.toByteArray()
    }

    private fun uint32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        uint32(value).copyInto(bytes, offset)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

}

internal class YspPrefetchCache(
    private val ttlMillis: Long = 15_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val urls: List<String>, val expiresAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun put(source: String, url: String) {
        putCandidates(source, listOf(url))
    }

    fun putCandidates(source: String, urls: List<String>) {
        val candidates = urls.filter(String::isNotBlank).distinct()
        if (source.isNotBlank() && candidates.isNotEmpty()) {
            entries[source] = Entry(candidates, nowMillis() + ttlMillis)
        }
    }

    fun take(source: String): String? = takeCandidates(source)?.firstOrNull()

    fun takeCandidates(source: String): List<String>? {
        val entry = entries.remove(source) ?: return null
        return entry.urls.takeIf { nowMillis() <= entry.expiresAt }
    }
}

internal class YspFallbackQueue {
    private val entries = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    fun set(source: String, urls: List<String>) {
        val candidates = urls.filter(String::isNotBlank).distinct()
        if (source.isBlank() || candidates.isEmpty()) {
            clear(source)
        } else {
            entries[source] = java.util.concurrent.ConcurrentLinkedQueue(candidates)
        }
    }

    fun next(source: String): String? {
        val queue = entries[source] ?: return null
        val next = queue.poll()
        if (queue.isEmpty()) entries.remove(source, queue)
        return next
    }

    fun clear(source: String) {
        if (source.isNotBlank()) entries.remove(source)
    }
}
