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
import kotlin.math.ceil
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
    fun pauseAll(tasks: List<OfflineVideoCache.TaskSnapshot>) = perform { context ->
        tasks.filter { canPauseDownload(it.status) }
            .forEach { OfflineVideoCache.pause(context, it.id) }
        true
    }
    fun resumeAll(tasks: List<OfflineVideoCache.TaskSnapshot>) = perform { context ->
        tasks.filter { canResumeDownload(it.status) }
            .forEach { OfflineVideoCache.retry(context, it.id) }
        true
    }
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

internal fun canPauseDownload(status: OfflineVideoCache.TaskStatus): Boolean =
    status == OfflineVideoCache.TaskStatus.QUEUED ||
        status == OfflineVideoCache.TaskStatus.DOWNLOADING

internal fun canResumeDownload(status: OfflineVideoCache.TaskStatus): Boolean =
    status == OfflineVideoCache.TaskStatus.PAUSED

internal data class DownloadProgressInput(
    val status: OfflineVideoCache.TaskStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val estimatedTotalBytes: Long = -1L,
)

internal data class OverallDownloadTotals(
    val unfinishedTasks: Int,
    val unknownTasks: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val progressPercent: Int?
        get() = if (totalBytes <= 0L) null else
            (downloadedBytes.toDouble() * 100.0 / totalBytes).toInt().coerceIn(0, 99)
}

internal fun aggregateDownloadTotals(tasks: List<DownloadProgressInput>): OverallDownloadTotals {
    var unfinished = 0
    var unknown = 0
    var downloaded = 0L
    var total = 0L
    for (task in tasks) {
        if (task.status != OfflineVideoCache.TaskStatus.QUEUED &&
            task.status != OfflineVideoCache.TaskStatus.DOWNLOADING
        ) continue
        unfinished++
        val currentBytes = task.downloadedBytes.coerceAtLeast(0L)
        val taskTotal = estimateTotalBytes(
            currentBytes, task.totalBytes, task.estimatedTotalBytes,
        )
        if (taskTotal <= 0L) {
            unknown++
        } else {
            downloaded += currentBytes
            total += taskTotal
        }
    }
    return OverallDownloadTotals(unfinished, unknown, downloaded, total)
}

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
    var editMode by remember { mutableStateOf(false) }
    var deleteConfirmationIds by remember { mutableStateOf<Set<String>?>(null) }
    val taskIds = tasks.mapTo(linkedSetOf()) { it.id }
    val allSelected = taskIds.isNotEmpty() && selectedIds.containsAll(taskIds)
    val selectedTasks = tasks.filter { it.id in selectedIds }
    val selectedPausableTasks = selectedTasks.filter { canPauseDownload(it.status) }
    val selectedPausedTasks = selectedTasks.filter { canResumeDownload(it.status) }

    LaunchedEffect(taskIds) {
        selectedIds = selectedIds.intersect(taskIds)
        if (taskIds.isEmpty()) editMode = false
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("下载", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(20.dp))
                OverallDownloadSummary(
                    context = context,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        actions = {
            if (tasks.isNotEmpty()) {
                if (editMode) {
                    TextButton(onClick = {
                        selectedIds = if (allSelected) emptySet() else taskIds
                    }) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                    TextButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = { deleteConfirmationIds = selectedIds },
                    ) {
                        Text("删除${selectedIds.size.takeIf { it > 0 }?.let { "($it)" } ?: ""}")
                    }
                    TextButton(onClick = {
                        editMode = false
                        selectedIds = emptySet()
                    }) {
                        Text("完成")
                    }
                } else {
                    TextButton(onClick = { editMode = true }) {
                        Text("编辑")
                    }
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
                if (editMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "已选 ${selectedIds.size}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            enabled = selectedPausableTasks.isNotEmpty(),
                            onClick = { vm.pauseAll(selectedPausableTasks) },
                        ) {
                            Text("暂停 ${selectedPausableTasks.size}")
                        }
                        Button(
                            enabled = selectedPausedTasks.isNotEmpty(),
                            onClick = { vm.resumeAll(selectedPausedTasks) },
                        ) {
                            Text("继续 ${selectedPausedTasks.size}")
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        DownloadTaskRow(
                            task = task,
                            vm = vm,
                            onPlay = onPlay,
                            editMode = editMode,
                            selected = task.id in selectedIds,
                            onSelectedChange = { selected ->
                                selectedIds = if (selected) selectedIds + task.id else selectedIds - task.id
                            },
                            onDeleteRequest = { deleteConfirmationIds = setOf(task.id) },
                        )
                    }
                }
            }
        }
    }
    val pendingDeleteIds = deleteConfirmationIds
    if (pendingDeleteIds != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmationIds = null },
            title = { Text("确认删除？") },
            text = {
                val count = pendingDeleteIds.size
                Text("将删除${if (count == 1) "此" else "选中的 $count 个"}下载任务及其本地缓存文件，此操作不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll(pendingDeleteIds)
                    selectedIds = selectedIds - pendingDeleteIds
                    deleteConfirmationIds = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmationIds = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OverallDownloadSummary(
    context: Context,
    modifier: Modifier = Modifier,
) {
    var totals by remember {
        mutableStateOf(OverallDownloadTotals(0, 0, 0L, 0L))
    }
    var speedBytesPerSecond by remember { mutableLongStateOf(0L) }

    LaunchedEffect(context) {
        var previousActiveBytes = emptyMap<String, Long>()
        var previousAtNanos = 0L
        val speedSamples = ArrayDeque<OverallDownloadSpeedSample>()
        while (isActive) {
            val snapshots = withContext(Dispatchers.IO) {
                OfflineVideoCache.getTaskSnapshots(context.applicationContext)
            }
            val activeBytes = snapshots.asSequence()
                .filter { it.status == OfflineVideoCache.TaskStatus.DOWNLOADING }
                .associate { it.id to it.downloadedBytes.coerceAtLeast(0L) }
            val nowNanos = System.nanoTime()
            if (previousAtNanos > 0L && activeBytes.isNotEmpty()) {
                val elapsedNanos = nowNanos - previousAtNanos
                val deltaBytes = activeBytes.entries.sumOf { (id, bytes) ->
                    (bytes - (previousActiveBytes[id] ?: bytes)).coerceAtLeast(0L)
                }
                if (elapsedNanos in 1L..OVERALL_SPEED_WINDOW_NANOS) {
                    speedSamples.addLast(OverallDownloadSpeedSample(nowNanos, deltaBytes, elapsedNanos))
                }
            }
            if (activeBytes.isEmpty()) speedSamples.clear()
            while (speedSamples.isNotEmpty()
                && nowNanos - speedSamples.first().atNanos > OVERALL_SPEED_WINDOW_NANOS
            ) speedSamples.removeFirst()
            speedBytesPerSecond = if (activeBytes.isEmpty()) 0L
                else overallDownloadSpeed(speedSamples.toList())
            previousActiveBytes = activeBytes
            previousAtNanos = nowNanos
            totals = aggregateDownloadTotals(snapshots.map {
                DownloadProgressInput(
                    status = it.status,
                    downloadedBytes = it.downloadedBytes,
                    totalBytes = it.totalBytes,
                    progressPercent = it.progressPercent,
                    estimatedTotalBytes = it.estimatedTotalBytes,
                )
            })
            kotlinx.coroutines.delay(DOWNLOAD_UI_REFRESH_MS)
        }
    }

    if (totals.unfinishedTasks == 0) return
    val remainingSeconds = if (totals.unknownTasks == 0) {
        estimateRemainingSeconds(
            totals.downloadedBytes,
            totals.totalBytes,
            speedBytesPerSecond,
        )?.let(::roundRemainingSecondsForDisplay)
    } else null
    val progressPercent = totals.progressPercent
    Column(
        modifier = modifier.padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = buildString {
                append("总进度 ")
                append(progressPercent?.let { "$it%" } ?: "计算中")
                append("  ·  总剩余 ")
                append(remainingSeconds?.let(::formatRemainingTime) ?: "计算中")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append("总下载速度 ")
                append(if (speedBytesPerSecond > 0L) formatDownloadSpeed(speedBytesPerSecond) else "计算中")
                if (totals.unknownTasks > 0) append("  ·  ${totals.unknownTasks}项待估")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (progressPercent != null) {
            LinearProgressIndicator(
                progress = { progressPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: OfflineVideoCache.TaskSnapshot,
    vm: DownloadViewModel,
    onPlay: ((OfflineVideoCache.TaskSnapshot) -> Unit)?,
    editMode: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onDeleteRequest: () -> Unit,
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
    // 列表监听和行内轮询不是同一个时序；轮询已经拿到后台增长的字节时，
    // 不能因为父列表又传来旧快照，就把行上的大小回退成 0 B。
    val visibleDownloadedBytes = maxOf(
        displayedDownloadedBytes,
        currentTask.downloadedBytes.coerceAtLeast(0L),
    )
    val visibleTotalBytes = if (currentTask.totalBytes > 0L) {
        maxOf(displayedTotalBytes, currentTask.totalBytes)
    } else {
        displayedTotalBytes
    }
    val visibleSpeed = if (displayedSpeed > 0L) displayedSpeed else {
        sanitizeDisplaySpeed(currentTask.speedBytesPerSecond)
    }

    // TaskListener 的回调频率受下载核心节流影响，不能作为下载页唯一的数据源。
    // 页面每秒读取一次最新快照，并直接用 downloadedBytes 的增量计算速度，避免核心层
    // 的瞬时 speedBytesPerSecond 为 0 或过期时把页面显示成几十 KB/s。
    LaunchedEffect(task.id) {
        var previousStatus = currentTask.status
        var previousDownloadedBytes = currentTask.downloadedBytes.coerceAtLeast(0L)
        var previousSampleAtNanos = System.nanoTime()
        var lastProgressAtNanos = previousSampleAtNanos
        var speedBaselineReady = false
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
                    speedBaselineReady = false
                    speedSamples.clear()
                } else if (active) {
                    // 总量重新校准或暂时未知时，必须接受新值，不能把旧的 99% 留在界面上。
                    displayedPercent = displayedPercentFor(current.status, rawPercent, restarted)

                    val elapsedNanos = nowNanos - previousSampleAtNanos
                    val deltaBytes = currentBytes - previousDownloadedBytes
                    val stalled = nowNanos - lastProgressAtNanos > DOWNLOAD_SPEED_STALE_NANOS
                    if (shouldResetSpeedBaseline(speedBaselineReady, restarted, deltaBytes)) {
                        // 首次看到任务时，已有的 .part/P2P 字节不是本采样周期内下载的。
                        speedBaselineReady = true
                        displayedSpeed = 0L
                        speedSamples.clear()
                        previousDownloadedBytes = currentBytes
                        previousSampleAtNanos = nowNanos
                        lastProgressAtNanos = nowNanos
                    } else if (deltaBytes > 0L && elapsedNanos > 0L) {
                        if (stalled) speedSamples.clear()
                        val speedSample = downloadSpeedSample(deltaBytes, elapsedNanos)
                        if (speedSample > 0L) {
                            speedSamples.addLast(speedSample)
                            while (speedSamples.size > DOWNLOAD_SPEED_SAMPLE_COUNT) speedSamples.removeFirst()
                            displayedSpeed = speedSamples.average().roundToLong().coerceAtLeast(0L)
                            lastProgressAtNanos = nowNanos
                        } else {
                            displayedSpeed = 0L
                            speedSamples.clear()
                        }
                    } else if (stalled) {
                        displayedSpeed = 0L
                        speedSamples.clear()
                    }
                    if (displayedSpeed <= 0L && current.speedBytesPerSecond > 0L
                        && nowNanos - lastProgressAtNanos <= DOWNLOAD_SPEED_STALE_NANOS) {
                        displayedSpeed = sanitizeDisplaySpeed(current.speedBytesPerSecond)
                    }
                    if (speedBaselineReady) {
                        previousDownloadedBytes = currentBytes
                        previousSampleAtNanos = nowNanos
                    }
                } else {
                    // 暂停、失败和取消都必须停止速度显示，不能残留上一轮速度。
                    displayedSpeed = 0L
                    speedBaselineReady = false
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
        if (editMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChange,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
                        text = if (visibleSpeed > 0) formatDownloadSpeed(visibleSpeed) else "等待速度",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val estimatedTotalBytes = estimateTotalBytes(
                visibleDownloadedBytes,
                visibleTotalBytes,
                currentTask.estimatedTotalBytes,
            )
            Text(
                text = formatDownloadSize(visibleDownloadedBytes, visibleTotalBytes, estimatedTotalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                val remainingSeconds = estimateRemainingSeconds(
                    visibleDownloadedBytes,
                    estimatedTotalBytes,
                    visibleSpeed,
                )
                Text(
                    text = remainingSeconds?.let { "预计剩余 ${formatRemainingTime(it)}" }
                        ?: "预计剩余时间计算中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            if (!editMode && currentTask.status != OfflineVideoCache.TaskStatus.DOWNLOADING
                && currentTask.status != OfflineVideoCache.TaskStatus.QUEUED) {
                OutlinedButton(onClick = onDeleteRequest) { Text("删除") }
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

internal fun estimateTotalBytes(
    downloadedBytes: Long,
    totalBytes: Long,
    explicitEstimateBytes: Long = -1L,
): Long {
    if (totalBytes > 0L) return totalBytes
    if (explicitEstimateBytes > 0L) return maxOf(downloadedBytes, explicitEstimateBytes)
    return -1L
}

internal data class OverallDownloadSpeedSample(
    val atNanos: Long,
    val downloadedBytes: Long,
    val elapsedNanos: Long,
)

internal fun overallDownloadSpeed(samples: List<OverallDownloadSpeedSample>): Long {
    val bytes = samples.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
    val elapsedNanos = samples.sumOf { it.elapsedNanos.coerceAtLeast(0L) }
    if (bytes <= 0L || elapsedNanos <= 0L) return 0L
    val speed = (bytes.toDouble() * 1_000_000_000.0 / elapsedNanos).roundToLong()
    return speed.coerceIn(0L, MAX_TOTAL_DISPLAY_SPEED_BYTES_PER_SECOND)
}

internal fun roundRemainingSecondsForDisplay(seconds: Long): Long {
    val interval = when {
        seconds >= 3_600L -> 300L
        seconds >= 600L -> 60L
        seconds >= 120L -> 30L
        else -> 10L
    }
    return ((seconds.coerceAtLeast(0L) + interval / 2L) / interval) * interval
}

internal fun estimateRemainingSeconds(
    downloadedBytes: Long,
    totalBytes: Long,
    speedBytesPerSecond: Long,
): Long? {
    if (downloadedBytes < 0L || totalBytes <= 0L || speedBytesPerSecond <= 0L) return null
    if (downloadedBytes >= totalBytes) return 0L
    return ceil((totalBytes - downloadedBytes).toDouble() / speedBytesPerSecond)
        .toLong()
        .coerceAtLeast(1L)
}

internal fun formatRemainingTime(seconds: Long): String {
    val remaining = seconds.coerceAtLeast(0L)
    if (remaining < 60L) return "${remaining}秒"
    if (remaining < 3_600L) return "${remaining / 60L}分${remaining % 60L}秒"
    return "${remaining / 3_600L}小时${remaining % 3_600L / 60L}分"
}

private fun Int.coerceInOrUnknown(): Int = if (this < 0) -1 else coerceIn(0, 100)

private fun Double.formatSpeedDecimal(): String = String.format(java.util.Locale.US, "%.1f", this)

internal fun shouldResetSpeedBaseline(
    baselineReady: Boolean,
    restarted: Boolean,
    deltaBytes: Long,
): Boolean = !baselineReady || restarted || deltaBytes < 0L

internal fun downloadSpeedSample(deltaBytes: Long, elapsedNanos: Long): Long {
    if (deltaBytes <= 0L || elapsedNanos <= 0L) return 0L
    return sanitizeDisplaySpeed((deltaBytes.toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble())
        .roundToLong()
        .coerceAtLeast(0L))
}

private fun sanitizeDisplaySpeed(speedBytesPerSecond: Long): Long =
    speedBytesPerSecond.takeIf { it in 1L..MAX_DISPLAY_SPEED_BYTES_PER_SECOND } ?: 0L

private const val DOWNLOAD_UI_REFRESH_MS = 500L
private const val DOWNLOAD_SPEED_SAMPLE_COUNT = 4
private const val DOWNLOAD_SPEED_STALE_NANOS = 2_500_000_000L
private const val DOWNLOAD_SPEED_FRESHNESS_MS = 1_500L
private const val MAX_DISPLAY_SPEED_BYTES_PER_SECOND = 100L * 1024L * 1024L
private const val MAX_TOTAL_DISPLAY_SPEED_BYTES_PER_SECOND = 500L * 1024L * 1024L
private const val OVERALL_SPEED_WINDOW_NANOS = 20_000_000_000L

internal fun shouldPreferLiveSnapshot(taskUpdatedAt: Long, liveUpdatedAt: Long): Boolean =
    liveUpdatedAt >= taskUpdatedAt

internal fun displayedSpeedFor(
    status: OfflineVideoCache.TaskStatus,
    speedBytesPerSecond: Long,
    updatedAt: Long,
    now: Long,
): Long {
    if (status != OfflineVideoCache.TaskStatus.DOWNLOADING) return 0L
    val safeSpeed = sanitizeDisplaySpeed(speedBytesPerSecond)
    if (safeSpeed <= 0L) return 0L
    val age = now - updatedAt
    return if (age in 0L..DOWNLOAD_SPEED_FRESHNESS_MS) safeSpeed else 0L
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
