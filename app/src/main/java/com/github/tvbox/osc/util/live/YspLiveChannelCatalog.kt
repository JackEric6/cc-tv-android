package com.github.tvbox.osc.util.live

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class YspLiveChannel(
    val id: String,
    val name: String,
    val channelId: String,
    val livePid: String,
    val definition: String = "fhd",
)

/** Public channels from the Yangshipin live interface; no account-only channels are included. */
object YspLiveChannelCatalog {
    private val cctvChannels = listOf(
        YspLiveChannel("cctv1", "CCTV1综合", "2024078201", "600001859"),
        YspLiveChannel("cctv2", "CCTV2财经", "2024075401", "600001800"),
        YspLiveChannel("cctv3", "CCTV3综艺", "2024068501", "600001801"),
        YspLiveChannel("cctv4", "CCTV4中文国际", "2029797101", "600001814"),
        YspLiveChannel("cctv5", "CCTV5体育", "2024078401", "600001818"),
        YspLiveChannel("cctv5p", "CCTV5+体育赛事", "2024078001", "600001817"),
        YspLiveChannel("cctv6", "CCTV6电影", "2013693901", "600108442"),
        YspLiveChannel("cctv7", "CCTV7国防军事", "2024072001", "600004092"),
        YspLiveChannel("cctv8", "CCTV8电视剧", "2029793001", "600001803"),
        YspLiveChannel("cctv9", "CCTV9纪录", "2024078601", "600004078"),
        YspLiveChannel("cctv10", "CCTV10科教", "2024078701", "600001805"),
        YspLiveChannel("cctv11", "CCTV11戏曲", "2027248701", "600001806"),
        YspLiveChannel("cctv12", "CCTV12社会与法", "2027248801", "600001807"),
        YspLiveChannel("cctv13", "CCTV13新闻", "2029797201", "600001811"),
        YspLiveChannel("cctv14", "CCTV14少儿", "2027248901", "600001809"),
        YspLiveChannel("cctv15", "CCTV15音乐", "2027249001", "600001815"),
        YspLiveChannel("cctv16", "CCTV16奥林匹克", "2027249101", "600098637"),
        YspLiveChannel("cctv164k", "CCTV16 4K", "2027249301", "600099502"),
        YspLiveChannel("cctv17", "CCTV17农业农村", "2027249401", "600001810"),
        YspLiveChannel("cctv4k", "CCTV4K超高清", "2029810301", "600002264"),
        YspLiveChannel("cctv8k", "CCTV8K超高清", "2026774101", "600156816"),
    )

    private val provincialSatellites = listOf(
        YspLiveChannel("bjws", "北京卫视", "2024052703", "600002309"),
        YspLiveChannel("jsws", "江苏卫视", "2024171103", "600002521"),
        YspLiveChannel("dfws", "东方卫视", "2024054503", "600002483"),
        YspLiveChannel("zjws", "浙江卫视", "2024054703", "600002520"),
        YspLiveChannel("hnws", "湖南卫视", "2024054803", "600002475"),
        YspLiveChannel("hbws", "湖北卫视", "2024171203", "600002508"),
        YspLiveChannel("gdws", "广东卫视", "2024060903", "600002485"),
        YspLiveChannel("gxws", "广西卫视", "2024060703", "600002509"),
        YspLiveChannel("hljws", "黑龙江卫视", "2029797003", "600002498"),
        YspLiveChannel("hnws2", "海南卫视", "2024055603", "600002506"),
        YspLiveChannel("cqws", "重庆卫视", "2024061103", "600002531"),
        YspLiveChannel("szws", "深圳卫视", "2024061303", "600002481"),
        YspLiveChannel("scws", "四川卫视", "2024061403", "600002516"),
        YspLiveChannel("henanws", "河南卫视", "2029797303", "600002525"),
        YspLiveChannel("fjdnhz", "东南卫视", "2024061503", "600002484"),
        YspLiveChannel("gzhws", "贵州卫视", "2024061603", "600002490"),
        YspLiveChannel("jxws", "江西卫视", "2024061703", "600002503"),
        YspLiveChannel("lnws", "辽宁卫视", "2024171303", "600002505"),
        YspLiveChannel("ahws", "安徽卫视", "2024171403", "600002532"),
        YspLiveChannel("hbws2", "河北卫视", "2024171503", "600002493"),
        YspLiveChannel("sdws", "山东卫视", "2029787903", "600002513"),
        YspLiveChannel("tjws", "天津卫视", "2019927003", "600152137"),
        YspLiveChannel("jlws", "吉林卫视", "2025561503", "600190405"),
        YspLiveChannel("shanxiws", "陕西卫视", "2029795103", "600190400"),
        YspLiveChannel("nxws", "宁夏卫视", "2025608503", "600190737"),
        YspLiveChannel("nmgws", "内蒙古卫视", "2025561203", "600190401"),
        YspLiveChannel("ynws", "云南卫视", "2025561303", "600190402"),
        YspLiveChannel("shanxiws2", "山西卫视", "2025560803", "600190407"),
        YspLiveChannel("qhws", "青海卫视", "2025559103", "600190406"),
        YspLiveChannel("xzws", "西藏卫视", "2025558003", "600190403"),
        YspLiveChannel("xjws", "新疆卫视", "2019927403", "600152138"),
    )

    private val satelliteExtras = listOf(
        YspLiveChannel("cgtn", "CGTN", "2024181701", "600014550"),
        YspLiveChannel("cgtnfy", "CGTN法语", "2024181801", "600084704"),
        YspLiveChannel("cgtney", "CGTN俄语", "2024181901", "600084758"),
        YspLiveChannel("cgtnalby", "CGTN阿拉伯语", "2024182001", "600084782"),
        YspLiveChannel("cgtnxby", "CGTN西班牙语", "2024182101", "600084744"),
        YspLiveChannel("cgtnwyjl", "CGTN纪录", "2024182301", "600084781"),
        YspLiveChannel("cetv1", "CETV1", "2022823801", "600171827"),
        YspLiveChannel("cctvfyjc", "CCTV风云剧场", "2025637103", "600099658", "shd"),
        YspLiveChannel("cctvdyjc", "CCTV第一剧场", "2026874203", "600099655", "shd"),
        YspLiveChannel("cctvhjjc", "CCTV怀旧剧场", "2026874303", "600099620", "shd"),
    )

    private val allChannels = cctvChannels + provincialSatellites + satelliteExtras
    private val channelsById = allChannels.associateBy(YspLiveChannel::id)

    @JvmStatic
    fun createGroups(): JsonArray = JsonArray().apply {
        add(createGroup("央视", cctvChannels))
        add(createGroup("卫视", provincialSatellites + satelliteExtras))
    }

    internal fun find(id: String): YspLiveChannel? = channelsById[id]

    internal fun channelCounts(): Pair<Int, Int> = cctvChannels.size to (provincialSatellites.size + satelliteExtras.size)

    private fun createGroup(name: String, channels: List<YspLiveChannel>) = JsonObject().apply {
        addProperty("group", name)
        add("channels", JsonArray().apply {
            channels.forEach { channel ->
                add(JsonObject().apply {
                    addProperty("name", channel.name)
                    addProperty("logo", "")
                    add("header", JsonObject().apply {
                        addProperty("User-Agent", "qqlive")
                        addProperty("Referer", "https://live.cctv.cn/")
                    })
                    add("urls", JsonArray().apply {
                        val primaryQuality = channel.definition
                        val backupQuality = if (primaryQuality == "fhd") "shd" else "fhd"
                        add("ysp://${channel.id}?quality=$primaryQuality\$央视频${qualityName(primaryQuality)}")
                        add("ysp://${channel.id}?quality=$backupQuality\$央视频${qualityName(backupQuality)}备用")
                    })
                })
            }
        })
    }

    private fun qualityName(quality: String): String = if (quality == "shd") "标清线路" else "高清线路"
}
