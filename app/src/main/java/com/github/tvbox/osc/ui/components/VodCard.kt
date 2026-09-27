package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.ColorPainter
import coil3.compose.AsyncImage
import com.github.tvbox.osc.bean.Movie
import kotlinx.coroutines.delay

enum class VodCardStyle { Overlay, Stacked }

@Composable
fun VodCard(
    video: Movie.Video,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: VodCardStyle = VodCardStyle.Overlay,
) {
    if (style == VodCardStyle.Stacked) {
        Column(modifier = modifier) {
            PressableCard(
                onClick = onClick,
                onLongClick = onLongClick,
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(modifier = Modifier.aspectRatio(2f / 3f)) {
                    VodPoster(video)
                    RatingBadge(video, Modifier.align(Alignment.TopEnd))
                }
            }
            Text(
                text = video.name ?: "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
        return
    }

    PressableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(modifier = Modifier.aspectRatio(2f / 3f)) {
            VodPoster(video)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f),
                        )
                    ),
            )
            RatingBadge(video, Modifier.align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    if (video.year > 0) append(video.year)
                    if (!video.area.isNullOrBlank()) {
                        if (isNotEmpty()) append(" / ")
                        append(video.area)
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun VodPoster(video: Movie.Video) {
    var status by remember(video.pic) {
        mutableStateOf(if (video.pic.isNullOrBlank()) PosterStatus.Error else PosterStatus.Loading)
    }
    var fallbackPoster by remember(video.pic, video.id, video.name) {
        mutableStateOf(
            if (video.pic.isNullOrBlank()) {
                VodImages.sharedPosterForTitle(video.name).takeIf { it.isNotBlank() }
            } else {
                null
            }
        )
    }
    var fallbackAttempted by remember(video.pic, video.id, video.name) { mutableStateOf(false) }

    // 资源站返回图片地址后立即登记候选，让同名卡片直接复用，
    // 不必等待当前卡片完成解码，也不会因此标记为已验证海报。
    LaunchedEffect(video.name, video.pic) {
        VodImages.rememberPosterCandidate(video.name, video.pic)
    }

    // 首页各资源站并发加载时，先返回海报的卡片会把已验证地址广播给同名卡片。
    // 监听本地通知即可即时切换，不需要重新请求 CMS 或阻塞首屏。
    LaunchedEffect(video.name) {
        VodImages.verifiedPosterUpdates.collect { titleKey ->
            if (titleKey != VodImages.normalizePosterTitle(video.name)) return@collect
            if (video.pic.isNullOrBlank() || status == PosterStatus.Error) {
                val candidate = VodImages.sharedPosterForTitle(video.name)
                if (candidate.isNotBlank() && candidate != fallbackPoster) {
                    fallbackPoster = candidate
                    fallbackAttempted = true
                    status = PosterStatus.Loading
                }
            }
        }
    }

    LaunchedEffect(video.pic, video.id, video.name, status) {
        if (status != PosterStatus.Error || fallbackAttempted) return@LaunchedEffect
        fallbackAttempted = true
        repeat(16) {
            val candidate = VodImages.resolvePosterOrSchedule(
                currentPoster = "",
                source = video.sourceKey,
                id = video.doubanId ?: video.id,
                title = video.name,
            )
            if (candidate.isNotBlank()) {
                fallbackPoster = candidate
                return@LaunchedEffect
            }
            delay(100)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = fallbackPoster ?: video.pic,
            contentDescription = video.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            fallback = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            onLoading = { status = PosterStatus.Loading },
            onSuccess = {
                status = PosterStatus.Success
                VodImages.rememberVerifiedPoster(video.name, fallbackPoster ?: video.pic)
            },
            onError = {
                VodImages.forgetPosterCandidate(video.name, fallbackPoster ?: video.pic)
                fallbackAttempted = false
                status = PosterStatus.Error
            },
        )
        if (status != PosterStatus.Success) {
            PosterStatusLabel(
                label = if (status == PosterStatus.Loading) "加载中" else "暂无海报",
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private enum class PosterStatus { Loading, Success, Error }

@Composable
private fun PosterStatusLabel(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun RatingBadge(video: Movie.Video, modifier: Modifier = Modifier) {
    val badge = ratingBadgeText(video.note) ?: return
    Text(
        text = badge,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private val RATING_SCORE_REGEX = Regex("评分[:：]?\\s*(\\d+(?:\\.\\d+)?)")
private val RATING_SCORE_SUFFIX_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*分$")

internal fun ratingBadgeText(note: String?): String? {
    val n = note?.trim().orEmpty()
    if (n.isEmpty()) return null
    val m = RATING_SCORE_REGEX.find(n)
        ?: RATING_SCORE_SUFFIX_REGEX.find(n)
    if (m != null) {
        val num = m.groupValues[1]
        return if (num.toFloatOrNull() == 0f) null else num
    }
    return n
}
