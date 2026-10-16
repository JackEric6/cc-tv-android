@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.activity

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.ColorPainter
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.VodImages
import com.github.tvbox.osc.ui.components.PressableCard
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.theme.cardContainer
import com.kyant.capsule.ContinuousCapsule
import com.github.tvbox.osc.util.SearchSettings
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryChip(
    word: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.widthIn(max = 260.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = word,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            )
            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "删除搜索记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .glassTopBarSurface(ContinuousCapsule, MaterialTheme.colorScheme.cardContainer)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.height(40.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索片名、演员",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "清空",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onQueryChange("") }
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(4.dp))
            trailing()
        }
    }
}

internal fun hideIme(activity: android.app.Activity?) {
    if (activity == null) return
    val view = activity.window?.currentFocus ?: return
    val imm = activity.getSystemService(InputMethodManager::class.java)
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
}

@Composable
internal fun LayoutSwitchAction(
    selected: SearchSettings.SearchLayout,
    onSelect: (SearchSettings.SearchLayout) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = "结果展示方式",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { expanded = true }
                .padding(4.dp)
                .size(22.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .width(156.dp)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LayoutSwitchCard(
                    iconRes = R.drawable.ic_layout_horizontal,
                    label = "横向展示",
                    selected = selected == SearchSettings.SearchLayout.Horizontal,
                    onClick = {
                        expanded = false
                        onSelect(SearchSettings.SearchLayout.Horizontal)
                    },
                )
                LayoutSwitchCard(
                    iconRes = R.drawable.ic_layout_vertical,
                    label = "竖向展示",
                    selected = selected == SearchSettings.SearchLayout.Vertical,
                    onClick = {
                        expanded = false
                        onSelect(SearchSettings.SearchLayout.Vertical)
                    },
                )
            }
        }
    }
}

@Composable
internal fun LayoutSwitchCard(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// 源名称统一为两字简称，筛选栏收窄，避免占据搜索结果主体空间。
internal val SearchRailWidth = 92.dp

@Composable
internal fun RailResults(
    results: List<SearchViewModel.SourceResult>,
    running: Boolean,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    topPad: Dp,
    railState: LazyListState,
    listState: LazyListState,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val rows = remember(results, selectedSource) {
        results
            .filter { it.videos.isNotEmpty() && (selectedSource == null || it.sourceKey == selectedSource) }
            .flatMap { result -> result.videos.map { result.sourceName to it } }
    }

    LaunchedEffect(selectedSource) {
        if (rows.isNotEmpty()) listState.scrollToItem(0)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = railState,
            modifier = Modifier.width(SearchRailWidth),
            contentPadding = PaddingValues(start = 12.dp, end = 10.dp, top = topPad + 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "rail_all") {
                SearchRailItem(
                    name = "全部",
                    pending = running,
                    selected = selectedSource == null,
                    onClick = { onSelectSource(null) },
                )
            }
            items(results, key = { "rail_${it.sourceKey}" }) { result ->
                SearchRailItem(
                    name = result.sourceName,
                    pending = result.state == SearchViewModel.ResultState.Pending,
                    selected = selectedSource == result.sourceKey,
                    onClick = { onSelectSource(result.sourceKey) },
                )
            }
        }
        VerticalDivider(
            modifier = Modifier.padding(top = topPad + 8.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 10.dp, end = 12.dp, top = topPad + 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (running) {
                item(key = "rail_progress") {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
            itemsIndexed(rows, key = { index, (_, video) -> "rail_row_${index}_${video.sourceKey}_${video.id}" }) { _, (siteName, video) ->
                SearchResultRow(
                    video = video,
                    siteName = siteName.takeIf { selectedSource == null },
                    onClick = { onCardClick(video) },
                    onLongClick = { onCardLongClick(video) },
                )
            }
            if (rows.isEmpty() && !running) {
                item(key = "rail_empty") {
                    Text(
                        text = "该站点暂无结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchRailItem(
    name: String,
    pending: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pending) {
                Spacer(modifier = Modifier.width(6.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = contentColor.copy(alpha = 0.6f),
                    strokeWidth = 1.5.dp,
                )
            }
        }
    }
}

@Composable
internal fun SearchResultRow(
    video: Movie.Video,
    siteName: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    PressableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(MaterialTheme.colorScheme.cardContainer),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var posterStatus by remember(video.pic) {
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

            // 搜索结果并发到达时先登记当前源的候选海报，其他同名结果可立即复用。
            LaunchedEffect(video.name, video.pic) {
                VodImages.rememberPosterCandidate(video.name, video.pic)
            }

            // 搜索结果是多源并发返回的：某个源先把同名直连海报加载成功后，
            // 其他空图卡片立即读取共享缓存，不需要重新请求 CMS 或外部补图。
            LaunchedEffect(video.name) {
                VodImages.verifiedPosterUpdates.collect { titleKey ->
                    if (titleKey != VodImages.normalizePosterTitle(video.name)) return@collect
                    if (video.pic.isNullOrBlank() || posterStatus == PosterStatus.Error) {
                        val candidate = VodImages.sharedPosterForTitle(video.name)
                        if (candidate.isNotBlank() && candidate != fallbackPoster) {
                            fallbackPoster = candidate
                            fallbackAttempted = true
                            posterStatus = PosterStatus.Loading
                        }
                    }
                }
            }

            LaunchedEffect(video.pic, video.id, video.name, posterStatus) {
                if (posterStatus != PosterStatus.Error || fallbackAttempted) return@LaunchedEffect
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
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = fallbackPoster ?: video.pic,
                    contentDescription = video.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    fallback = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    onLoading = { posterStatus = PosterStatus.Loading },
                    onSuccess = {
                        posterStatus = PosterStatus.Success
                        VodImages.rememberVerifiedPoster(video.name, fallbackPoster ?: video.pic)
                    },
                    onError = {
                        VodImages.forgetPosterCandidate(video.name, fallbackPoster ?: video.pic)
                        fallbackAttempted = false
                        posterStatus = PosterStatus.Error
                    },
                )
                if (posterStatus != PosterStatus.Success) {
                    SearchPosterStatusLabel(if (posterStatus == PosterStatus.Loading) "加载中" else "暂无海报")
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!video.note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = video.note.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = searchMetaText(video)
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (siteName != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = siteName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 90.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private enum class PosterStatus { Loading, Success, Error }

@Composable
private fun SearchPosterStatusLabel(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

internal fun searchMetaText(video: Movie.Video): String = listOfNotNull(
    video.year.takeIf { it > 0 }?.toString(),
    video.area?.takeIf { it.isNotBlank() },
    video.type?.takeIf { it.isNotBlank() },
).joinToString(" · ")
