package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.VodInfo
import org.json.JSONObject
import java.io.File

internal object OfflineMovieMetadata {
    fun encode(info: VodInfo, episodeName: String? = null): String = JSONObject().apply {
        put("id", info.id.orEmpty())
        put("tid", info.tid)
        put("name", info.name.orEmpty())
        put("type", info.type.orEmpty())
        put("dt", info.dt.orEmpty())
        put("pic", info.pic.orEmpty())
        put("doubanId", info.doubanId.orEmpty())
        put("lang", info.lang.orEmpty())
        put("area", info.area.orEmpty())
        put("year", info.year)
        put("state", info.state.orEmpty())
        put("note", info.note.orEmpty())
        put("actor", info.actor.orEmpty())
        put("director", info.director.orEmpty())
        put("des", info.des.orEmpty())
        put("sourceName", info.sourceName.orEmpty())
        put("playerCfg", info.playerCfg.orEmpty())
        put("reverseSort", info.reverseSort)
        put("danmakuTitle", info.name.orEmpty())
        put("danmakuEpisode", episodeName ?: info.playNote.orEmpty())
    }.toString()

    fun applyTo(info: VodInfo, metadataJson: String?, posterPath: String?) {
        val json = metadataJson?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return
        info.id = json.optString("id", info.id.orEmpty())
        info.tid = json.optInt("tid", info.tid)
        info.name = json.optString("name", info.name.orEmpty())
        info.type = json.optString("type", info.type.orEmpty())
        info.dt = json.optString("dt", info.dt.orEmpty())
        info.pic = json.optString("pic", info.pic.orEmpty())
        info.doubanId = json.optString("doubanId", info.doubanId.orEmpty())
        info.lang = json.optString("lang", info.lang.orEmpty())
        info.area = json.optString("area", info.area.orEmpty())
        info.year = json.optInt("year", info.year)
        info.state = json.optString("state", info.state.orEmpty())
        info.note = json.optString("note", info.note.orEmpty())
        info.actor = json.optString("actor", info.actor.orEmpty())
        info.director = json.optString("director", info.director.orEmpty())
        info.des = json.optString("des", info.des.orEmpty())
        info.sourceName = json.optString("sourceName", info.sourceName.orEmpty())
        info.playerCfg = json.optString("playerCfg", info.playerCfg.orEmpty())
        info.reverseSort = json.optBoolean("reverseSort", info.reverseSort)
        val localPoster = posterPath?.takeIf(String::isNotBlank)?.let(::File)
        if (localPoster?.isFile == true && localPoster.length() > 0L) {
            info.pic = localPoster.absolutePath
        }
    }
}
