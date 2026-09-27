@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.page

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.OfflineVideoCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque
import kotlin.math.roundToLong

/** 在下载页和主界面下载标签复用同一套本地缓存播放入口。 */
fun launchCachedTask(context: Context, task: OfflineVideoCache.TaskSnapshot) {
    val file = task.targetPath.takeIf { it.isNotBlank() }?.let(::File)
    if (file == null || !file.isFile || file.length() <= 0L) {
        Toast.makeText(context, "本地缓存文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(context, DetailActivity::class.java).apply {
            putExtra("offlinePath", file.absolutePath)
            putExtra("offlineTitle", task.movieName.ifBlank { "本地缓存" })
            putExtra("offlineEpisode", task.episodeName.ifBlank { "本地视频" })
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Throwable) {
        Toast.makeText(context, "无法打开本地缓存", Toast.LENGTH_SHORT).show()
    }
}

class DownloadViewModel : ViewModel() {
    val tasks = MutableStateFlow<List<OfflineVideoCache.TaskSnapshot>>(emptyList())
    private var boundContext: android.content.Context? = null
    private val listener = object : OfflineVideoCache.TaskListener {
        override fun onTasksChanged(tasks: List<OfflineVideoCache.TaskSnapshot>) {
            this@DownloadViewModel.tasks.value = tasks
        }
    }

    fun bind(context: android.content.Context) {
        val appContext = context.applicationContext
        if (shouldRefreshBoundContext(boundContext, appContext)) {
            refresh(appContext)
            return
        }
        boundContext?.let { OfflineVideoCache.removeTaskListener(listener) }
        boundContext = appContext
        OfflineVideoCache.addTaskListener(appContext, listener)
    }

    /**
     * 下载页在 HorizontalPager 中会一直保活，重新切回或从详情页返回时不能依赖
     * Compose 重新创建页面；只重新读取持久化快照，不重复注册任务监听器。
     */
    fun refresh(context: android.content.Context) {
        tasks.value = OfflineVideoCache.getTaskSnapshots(context.applicationContext)
    }

    fun pause(task: OfflineVideoCache.TaskSnapshot) = perform { OfflineVideoCache.pause(it, task.id) }
    fun cancel(task: OfflineVideoCache.TaskSnapshot) = perform { OfflineVideoCache.cancel(it, task.id) }
    fun resume(task: OfflineVideoCache.TaskSnapshot) = perform { OfflineVideoCache.retry(it, task.id) }
    fun retry(task: OfflineVideoCache.TaskSnapshot) = perform { OfflineVideoCache.retry(it, task.id) }
    fun delete(task: OfflineVideoCache.TaskSnapshot) = perform { OfflineVideoCache.delete(it, task.id) }
    fun deleteAll(ids: Set<String>) = perform { context ->
        ids.forEach { OfflineVideoCache.delete(context, it) }
        true
    }

    private fun perform(action: (android.content.Context) -> Boolean) {
        val context = boundContext ?: return
        viewModelScope.launch(Dispatchers.IO) { action(context) }
    }

    override fun onCleared() {
        OfflineVideoCache.removeTaskListener(listener)
        boundContext = null
    }
}

internal fun shouldRefreshBoundContext(
    boundContext: Context?,
    appContext: Context,
): Boolean = boundContext === appContext

internal fun downloadTabIsCurrent(currentTab: Int, downloadTab: Int): Boolean =
    currentTab == downloadTab

@Composable
fun DownloadPage(
    vm: DownloadViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onNavigateBack: (() -> Unit)? = null,
    onPlay: ((OfflineVideoCache.TaskSnapshot) -> Unit)? = null,
    isCurrentTab: Boolean = true,
    refreshToken: Int = 0,
) {
    val context = LocalContext.current
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val tasks by vm.tasks.collectAsState()
    val listState = rememberLazyListState()
    var previousHeadId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val taskIds = tasks.mapTo(linkedSetOf()) { it.id }
    val allSelected = taskIds.isNotEmpty() && selectedIds.containsAll(taskIds)

    LaunchedEffect(taskIds) {
        selectedIds = selectedIds.intersect(taskIds)
    }

    DisposableEffect(vm, context) {
        vm.bind(context)
        onDispose { }
    }

    // HorizontalPager 会保活页面。每次下载 Tab 成为当前页时主动拉取快照，确保详情页
    // 新建的任务立即显示；ON_RESUME 覆盖从详情页返回主界面的场景。
    LaunchedEffect(isCurrentTab, refreshToken) {
        if (isCurrentTab) vm.refresh(context)
    }
    // 新任务始终插入列表头部。LazyColumn 为保持原可见项可能把新增项留在
    // 视口上方，当前页检测到队首变化时回到第 0 项，确保用户立即看到新任务。
    LaunchedEffect(tasks.firstOrNull()?.id, isCurrentTab) {
        if (!isCurrentTab) return@LaunchedEffect
        val headId = tasks.firstOrNull()?.id
        if (previousHeadId != null && headId != previousHeadId) {
            listState.scrollToItem(0)
        }
        previousHeadId = headId
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refresh(context)
    }

    AppTopBarScaffold(
        topBarStartInset = navStart,
        titleContent = {
            Text("下载", style = MaterialTheme.typography.headlineSmall)
        },
        actions = {
            if (tasks.isNotEmpty()) {
                TextButton(onClick = {
                    selectedIds = if (allSelected) emptySet() else taskIds
                }) {
                    Text(if (allSelected) "取消全选" else "全选")
                }
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { showDeleteConfirmation = true },
                ) {
                    Text("删除${selectedIds.size.takeIf { it > 0 }?.let { "($it)" } ?: ""}")
                }
            }
        },
        navigationIcon = onNavigateBack?.let { navigateBack ->
            { TopBarActionBox(R.drawable.ic_arrow_left, "返回", navigateBack) }
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = navStart, bottom = navBottom),
        ) {
            Spacer(Modifier.height(topPad))
            if (tasks.isEmpty()) {
                LoadStateBox(
                    state = LoadState.Empty,
                    emptyText = "暂无下载任务",
                    errorText = "",
                    retryText = "",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        DownloadTaskRow(
                            task = task,
                            vm = vm,
                            onPlay = onPlay,
                            selected = task.id in selectedIds,
                            onSelectedChange = { selected ->
                                selectedIds = if (selected) selectedIds + task.id else selectedIds - task.id
                            },
                        )
                    }
                }
            }
        }
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除下载") },
            text = { Text("将删除选中的 ${selectedIds.size} 个下载任务及其本地缓存文件，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll(selectedIds)
                    selectedIds = emptySet()
                    showDeleteConfirmation = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DownloadTaskRow(
    task: OfflineVideoCache.TaskSnapshot,
    vm: DownloadViewModel,
    onPlay: ((OfflineVideoCache.TaskSnapshot) -> Unit)?,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var liveTask by remember(task.id) { mutableStateOf(task) }

    // TaskListener 与本地轮询可能交错到达；优先使用更新的快照，避免后台字节数已经
    // 增长而列表行仍停留在旧的进度。轮询快照如果更新得更快，则继续保留它。
    val currentTask = when {
        task.status == OfflineVideoCache.TaskStatus.DOWNLOADING
            && liveTask.status == OfflineVideoCache.TaskStatus.DOWNLOADING -> {
            if (liveTask.downloadedBytes > task.downloadedBytes) liveTask else task
        }
        shouldPreferLiveSnapshot(task.updatedAt, liveTask.updatedAt) -> liveTask
        else -> task
    }
    // 任务重试会复用同一个缓存 ID；状态作为 key，避免沿用上一次运行的 99%。
    var displayedPercent by remember(task.id, currentTask.status) {
        mutableIntStateOf(initialDisplayedPercent(currentTask.status, currentTask.progressPercent))
    }
    var displayedSpeed by remember(task.id, currentTask.status) {
        mutableLongStateOf(0L)
    }
    var displayedDownloadedBytes by remember(task.id, currentTask.status) {
        mutableLongStateOf(currentTask.downloadedBytes.coerceAtLeast(0L))
    }
    var displayedTotalBytes by remember(task.id, currentTask.status) {
        mutableLongStateOf(currentTask.totalBytes)
    }

    // TaskListener 的回调频率受下载核心节流影响，不能作为下载页唯一的数据源。
    // 页面每秒读取一次最新快照，并直接用 downloadedBytes 的增量计算速度，避免核心层
    // 的瞬时 speedBytesPerSecond 为 0 或过期时把页面显示成几十 KB/s。
    LaunchedEffect(task.id) {
        var previousStatus = currentTask.status
        var previousDownloadedBytes = currentTask.downloadedBytes.coerceAtLeast(0L)
        var previousSampleAtNanos = System.nanoTime()
        var lastProgressAtNanos = previousSampleAtNanos
        val speedSamples = ArrayDeque<Long>(DOWNLOAD_SPEED_SAMPLE_COUNT)
        while (isActive) {
            val snapshot = withContext(Dispatchers.IO) {
                OfflineVideoCache.getTaskSnapshots(context.applicationContext)
                    .firstOrNull { it.id == task.id }
            }
            if (snapshot != null) {
                liveTask = snapshot
                val current = snapshot
                val nowNanos = System.nanoTime()
                val currentBytes = current.downloadedBytes.coerceAtLeast(0L)
                val active = current.status == OfflineVideoCache.TaskStatus.DOWNLOADING
                val restarted = active && previousStatus != OfflineVideoCache.TaskStatus.DOWNLOADING
                val rawPercent = current.progressPercent.coerceInOrUnknown()

                displayedDownloadedBytes = currentBytes
                displayedTotalBytes = current.totalBytes
                if (current.status == OfflineVideoCache.TaskStatus.COMPLETED) {
                    displayedPercent = 100
                    displayedSpeed = 0L
                    speedSamples.clear()
                } else if (active) {
                    // 总量重新校准或暂时未知时，必须接受新值，不能把旧的 99% 留在界面上。
                    displayedPercent = displayedPercentFor(current.status, rawPercent, restarted)

                    val elapsedNanos = nowNanos - previousSampleAtNanos
                    val deltaBytes = currentBytes - previousDownloadedBytes
                    val stalled = nowNanos - lastProgressAtNanos > DOWNLOAD_SPEED_STALE_NANOS
                    if (deltaBytes > 0L && elapsedNanos > 0L) {
                        if (stalled) speedSamples.clear()
                        val speedSample = bytesPerSecond(deltaBytes, elapsedNanos)
                        if (speedSample > 0L) {
                            speedSamples.addLast(speedSample)
                            while (speedSamples.size > DOWNLOAD_SPEED_SAMPLE_COUNT) speedSamples.removeFirst()
                            displayedSpeed = speedSamples.average().roundToLong().coerceAtLeast(0L)
                            lastProgressAtNanos = nowNanos
                        }
                    } else if (stalled) {
                        displayedSpeed = 0L
                        speedSamples.clear()
                    }
                    if (displayedSpeed <= 0L && current.speedBytesPerSecond > 0L
                        && nowNanos - lastProgressAtNanos <= DOWNLOAD_SPEED_STALE_NANOS) {
                        displayedSpeed = current.speedBytesPerSecond
                    }
                    previousDownloadedBytes = currentBytes
                    previousSampleAtNanos = nowNanos
                } else {
                    // 暂停、失败和取消都必须停止速度显示，不能残留上一轮速度。
                    displayedSpeed = 0L
                    speedSamples.clear()
                    previousDownloadedBytes = currentBytes
                    previousSampleAtNanos = nowNanos
                    lastProgressAtNanos = nowNanos
                }
                previousStatus = current.status
            }
            kotlinx.coroutines.delay(DOWNLOAD_UI_REFRESH_MS)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = onSelectedChange,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Text(
            text = currentTask.movieName.ifBlank { "未命名影片" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = currentTask.episodeName.ifBlank { "第${currentTask.episodeIndex + 1}集" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(
            text = "来源：${currentTask.sourceName.ifBlank { currentTask.sourceKey }.ifBlank { "未知来源" }}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        val percent = when (currentTask.status) {
            OfflineVideoCache.TaskStatus.COMPLETED -> 100
            OfflineVideoCache.TaskStatus.QUEUED -> -1
            OfflineVideoCache.TaskStatus.PAUSED -> currentTask.progressPercent.coerceInOrUnknown()
            else -> displayedPercent
        }
        val active = currentTask.status == OfflineVideoCache.TaskStatus.DOWNLOADING
        val queued = currentTask.status == OfflineVideoCache.TaskStatus.QUEUED
        val paused = currentTask.status == OfflineVideoCache.TaskStatus.PAUSED
        if (active || queued || paused || currentTask.status == OfflineVideoCache.TaskStatus.COMPLETED) {
            LinearProgressIndicator(
                progress = { if (percent >= 0) percent / 100f else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        currentTask.status == OfflineVideoCache.TaskStatus.COMPLETED -> "已完成"
                        currentTask.status == OfflineVideoCache.TaskStatus.QUEUED -> "排队中"
                        currentTask.status == OfflineVideoCache.TaskStatus.PAUSED && percent >= 0 -> "已暂停 $percent%"
                        currentTask.status == OfflineVideoCache.TaskStatus.PAUSED -> "已暂停"
                        percent >= 0 -> "下载中 $percent%"
                        else -> "下载中"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (currentTask.status == OfflineVideoCache.TaskStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                if (active) {
                    Text(
                        text = if (displayedSpeed > 0) formatDownloadSpeed(displayedSpeed) else "等待速度",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val estimatedTotalBytes = estimateTotalBytes(
                displayedDownloadedBytes,
                displayedTotalBytes,
                percent,
            )
            Text(
                text = formatDownloadSize(displayedDownloadedBytes, displayedTotalBytes, estimatedTotalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = when (currentTask.status) {
                    OfflineVideoCache.TaskStatus.COMPLETED -> "已完成"
                    OfflineVideoCache.TaskStatus.PAUSED -> "已暂停"
                    OfflineVideoCache.TaskStatus.CANCELLED -> "已取消"
                    OfflineVideoCache.TaskStatus.FAILED -> "下载失败"
                    else -> currentTask.status.name
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (currentTask.status == OfflineVideoCache.TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (currentTask.status) {
                OfflineVideoCache.TaskStatus.DOWNLOADING,
                OfflineVideoCache.TaskStatus.QUEUED -> {
                    OutlinedButton(onClick = { vm.pause(currentTask) }) { Text("暂停") }
                    OutlinedButton(onClick = { vm.cancel(currentTask) }) { Text("取消") }
                }
                OfflineVideoCache.TaskStatus.PAUSED -> {
                    Button(onClick = { vm.resume(currentTask) }) { Text("继续") }
                }
                OfflineVideoCache.TaskStatus.FAILED,
                OfflineVideoCache.TaskStatus.CANCELLED -> {
                    Button(onClick = { vm.retry(currentTask) }) { Text("重试") }
                }
                OfflineVideoCache.TaskStatus.COMPLETED -> Unit
            }
            if (currentTask.status == OfflineVideoCache.TaskStatus.COMPLETED && onPlay != null) {
                OutlinedButton(onClick = { onPlay(currentTask) }) { Text("播放") }
            }
            if (currentTask.status != OfflineVideoCache.TaskStatus.DOWNLOADING && currentTask.status != OfflineVideoCache.TaskStatus.QUEUED) {
                OutlinedButton(onClick = { vm.delete(currentTask) }) { Text("删除") }
            }
        }
        }
    }
}

private fun formatDownloadSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond < 1024L) return "$bytesPerSecond B/s"
    if (bytesPerSecond < 1024L * 1024L) return "${(bytesPerSecond / 1024.0).formatSpeedDecimal()} KB/s"
    return "${(bytesPerSecond / (1024.0 * 1024.0)).formatSpeedDecimal()} MB/s"
}

private fun formatDownloadSize(downloadedBytes: Long, totalBytes: Long, estimatedTotalBytes: Long): String {
    val downloaded = formatByteCount(downloadedBytes)
    val total = when {
        totalBytes > 0L -> formatByteCount(totalBytes)
        estimatedTotalBytes > 0L -> "约 ${formatByteCount(estimatedTotalBytes)}"
        else -> "总大小未知"
    }
    return "大小：$downloaded / $total"
}

private fun formatByteCount(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return "${(bytes / 1024.0).formatSpeedDecimal()} KB"
    if (bytes < 1024L * 1024L * 1024L) {
        return "${(bytes / (1024.0 * 1024.0)).formatSpeedDecimal()} MB"
    }
    return "${(bytes / (1024.0 * 1024.0 * 1024.0)).formatSpeedDecimal()} GB"
}

internal fun estimateTotalBytes(downloadedBytes: Long, totalBytes: Long, percent: Int): Long {
    if (totalBytes > 0L) return totalBytes
    if (downloadedBytes <= 0L || percent !in 1..99) return -1L
    return (downloadedBytes * 100L / percent).coerceAtLeast(downloadedBytes)
}

private fun Int.coerceInOrUnknown(): Int = if (this < 0) -1 else coerceIn(0, 100)

private fun Double.formatSpeedDecimal(): String = String.format(java.util.Locale.US, "%.1f", this)

private fun bytesPerSecond(deltaBytes: Long, elapsedNanos: Long): Long {
    if (deltaBytes <= 0L || elapsedNanos <= 0L) return 0L
    return (deltaBytes.toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble())
        .roundToLong()
        .coerceAtLeast(0L)
}

private const val DOWNLOAD_UI_REFRESH_MS = 500L
private const val DOWNLOAD_SPEED_SAMPLE_COUNT = 4
private const val DOWNLOAD_SPEED_STALE_NANOS = 2_500_000_000L
private const val DOWNLOAD_SPEED_FRESHNESS_MS = 1_500L

internal fun shouldPreferLiveSnapshot(taskUpdatedAt: Long, liveUpdatedAt: Long): Boolean =
    liveUpdatedAt >= taskUpdatedAt

internal fun displayedSpeedFor(
    status: OfflineVideoCache.TaskStatus,
    speedBytesPerSecond: Long,
    updatedAt: Long,
    now: Long,
): Long {
    if (status != OfflineVideoCache.TaskStatus.DOWNLOADING || speedBytesPerSecond <= 0L) return 0L
    val age = now - updatedAt
    return if (age in 0L..DOWNLOAD_SPEED_FRESHNESS_MS) speedBytesPerSecond else 0L
}

internal fun downloadTabFor(status: OfflineVideoCache.TaskStatus): Int = when (status) {
    OfflineVideoCache.TaskStatus.QUEUED,
    OfflineVideoCache.TaskStatus.DOWNLOADING,
    OfflineVideoCache.TaskStatus.PAUSED -> 0
    OfflineVideoCache.TaskStatus.COMPLETED -> 1
    OfflineVideoCache.TaskStatus.FAILED,
    OfflineVideoCache.TaskStatus.CANCELLED -> 2
}

internal fun initialDisplayedPercent(
    status: OfflineVideoCache.TaskStatus,
    progressPercent: Int,
): Int = when (status) {
    OfflineVideoCache.TaskStatus.COMPLETED -> 100
    OfflineVideoCache.TaskStatus.DOWNLOADING -> progressPercent.coerceInOrUnknown()
    OfflineVideoCache.TaskStatus.QUEUED,
    OfflineVideoCache.TaskStatus.FAILED,
    OfflineVideoCache.TaskStatus.PAUSED,
    OfflineVideoCache.TaskStatus.CANCELLED -> -1
}

internal fun displayedPercentFor(
    status: OfflineVideoCache.TaskStatus,
    progressPercent: Int,
    restarted: Boolean,
): Int {
    // 状态从队列/失败/暂停重新进入下载时，旧快照里的百分比可能尚未清零。
    // 先回到未知，等待本轮下载产生可靠样本。
    if (restarted && status == OfflineVideoCache.TaskStatus.DOWNLOADING) return -1
    if (restarted) return initialDisplayedPercent(status, progressPercent)
    return when (status) {
        OfflineVideoCache.TaskStatus.COMPLETED -> 100
        OfflineVideoCache.TaskStatus.DOWNLOADING -> progressPercent.coerceInOrUnknown()
        else -> initialDisplayedPercent(status, progressPercent)
    }
}
