package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.PartitionListActivity

sealed interface VodCardTarget {
    data class Action(val video: Movie.Video) : VodCardTarget

    data class Folder(val video: Movie.Video) : VodCardTarget

    data class Search(val title: String) : VodCardTarget

    data class Detail(val video: Movie.Video) : VodCardTarget
}

internal fun Movie.Video.isFolderCard(): Boolean = tag == "folder"

internal fun Movie.Video.hasOpenableDetailId(): Boolean {
    val value = id?.trim().orEmpty()
    return value.isNotEmpty() && !value.startsWith("msearch:", ignoreCase = true)
}

internal fun Movie.Video.hasUsableSourceKey(): Boolean = !sourceKey?.trim().isNullOrEmpty()

fun resolveVodCardTarget(video: Movie.Video): VodCardTarget = when {
    !video.action.isNullOrEmpty() -> VodCardTarget.Action(video)
    video.isFolderCard() -> VodCardTarget.Folder(video)
    video.hasUsableSourceKey() && video.hasOpenableDetailId() ->
        VodCardTarget.Detail(video)
    else -> VodCardTarget.Search(video.name.orEmpty())
}

fun Context.dispatchVodCardClick(video: Movie.Video, onAction: (Movie.Video) -> Unit = {}) {
    when (val target = resolveVodCardTarget(video)) {
        is VodCardTarget.Action -> onAction(video)
        is VodCardTarget.Folder -> openVodFolder(target.video)
        is VodCardTarget.Search -> jumpToSearch(target.title)
        is VodCardTarget.Detail -> jumpToDetail(
            target.video.id?.trim(),
            target.video.sourceKey?.trim(),
            target.video.name,
            target.video.pic,
        )
    }
}

fun Context.openVodCardOrDetail(video: Movie.Video) {
    when (val target = resolveVodCardTarget(video)) {
        is VodCardTarget.Action -> Unit
        is VodCardTarget.Folder -> openVodFolder(target.video)
        is VodCardTarget.Search -> jumpToSearch(target.title)
        is VodCardTarget.Detail -> jumpToDetail(
            target.video.id?.trim(),
            target.video.sourceKey?.trim(),
            target.video.name,
            target.video.pic,
        )
    }
}

private fun Context.openVodFolder(video: Movie.Video) {
    val folderId = video.id.orEmpty()
    if (folderId.isEmpty()) {
        Toast.makeText(this, "目录数据缺失,无法打开", Toast.LENGTH_SHORT).show()
        return
    }
    PartitionListActivity.startForFolder(this, folderId, video.name.orEmpty())
}
