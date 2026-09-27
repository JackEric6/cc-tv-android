package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.bean.Movie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isFolderCard] 单测:判错的后果是"点目录条目进详情页却永远空"(列目录要 `t=<id>&filter=true`,
 * 详情页发 `ids=<id>`),而搜索链路三个入口全靠它决定是否下钻。
 */
class VodCardActionTest {

    private fun video(tag: String?): Movie.Video {
        val item = Movie.Video()
        item.tag = tag
        return item
    }

    private fun detailVideo(id: String?, sourceKey: String?): Movie.Video {
        val item = Movie.Video()
        item.id = id
        item.sourceKey = sourceKey
        item.name = "测试影片"
        return item
    }

    @Test
    fun folderTagIsFolderCard() {
        assertTrue(video("folder").isFolderCard())
    }

    @Test
    fun missingOrOtherTagIsNotFolderCard() {
        assertFalse(video(null).isFolderCard())
        assertFalse(video("").isFolderCard())
        assertFalse(video("电影").isFolderCard())
        assertFalse(video("Folder").isFolderCard())
    }

    @Test
    fun realIdAndSourceKeyOpenDetailEvenForIndexMarkedSource() {
        val target = resolveVodCardTarget(detailVideo("83897", "index-source"))
        assertTrue(target is VodCardTarget.Detail)
    }

    @Test
    fun missingIdOrSourceKeyFallsBackToSearch() {
        assertTrue(resolveVodCardTarget(detailVideo(null, "source")) is VodCardTarget.Search)
        assertTrue(resolveVodCardTarget(detailVideo("", "source")) is VodCardTarget.Search)
        assertTrue(resolveVodCardTarget(detailVideo("msearch:沉默的荣耀", "source")) is VodCardTarget.Search)
        assertTrue(resolveVodCardTarget(detailVideo("83897", null)) is VodCardTarget.Search)
        assertTrue(resolveVodCardTarget(detailVideo("83897", "  ")) is VodCardTarget.Search)
    }

    @Test
    fun folderStillTakesPrecedenceOverDetailId() {
        val item = detailVideo("folder-id", "source")
        item.tag = "folder"
        assertTrue(resolveVodCardTarget(item) is VodCardTarget.Folder)
    }
}
