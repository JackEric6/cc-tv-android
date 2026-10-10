package com.github.tvbox.osc.player.controller

import com.github.tvbox.osc.bean.ParseBean

import java.util.HashMap

interface VodControlListener {
    fun playNext(rmProgress: Boolean)

    fun playPre()

    fun showEpisodeDialog()

    fun prepared()

    fun changeParse(pb: ParseBean)

    fun updatePlayerCfg()

    fun replay(replay: Boolean)

    /** 请求宿主缓存当前集当前源，不改变当前播放地址或播放状态 */
    fun downloadCurrentVideo()

    fun errReplay()

    fun selectSubtitle()

    fun selectAudioTrack()

    fun selectVideoTrack()

    fun showDanmuSetting()

    fun toggleDanmu(): Boolean

    fun searchDanmuUi(longClick: Boolean)

    fun startPlayUrl(gen: Int, url: String, headers: HashMap<String, String>?)

    fun onM3u8ProxyUrl(gen: Int, proxyUrl: String, sourceUrl: String, headers: HashMap<String, String>?)

    fun clickCast()

    fun setAllowSwitchPlayer(isAllow: Boolean)

    /**
     * 用户手动选过解码方式(播放器解码按钮):本次播放不再自动回退软解,且"自动软解"态作废
     * (用户的选择要能落进播放记录;与 setAllowSwitchPlayer 同理,2026-09-15)
     */
    fun setAllowDecodeFallback(isAllow: Boolean)
}
