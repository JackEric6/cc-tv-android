package com.github.tvbox.osc.ui.components

/**
 * 海报查询 provider。provider 只返回可交给 Coil 的图片 URL，不参与详情、播放或资源站请求。
 */
interface PosterProvider {
    fun findPoster(title: String?, year: Int?, doubanId: String?): String?
}
