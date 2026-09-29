package com.github.tvbox.osc.ui.page

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.viewmodel.SourceViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.coroutines.resume

class HomeViewModel : ViewModel() {
    companion object {
        private const val PREFERRED_HOME_SOURCE_KEY = "guazi"
        private const val FALLBACK_HOME_SOURCE_KEY = "xgzy"
        private const val IMMEDIATE_HOME_PARTITIONS = 4
        private const val LAZY_HOME_PARTITION_DELAY_MS = 240L
        private const val GUAZI_RECOMMENDATION_SOURCE_COUNT = 3
        private const val GUAZI_RECOMMENDATION_SIZE = 5
        private const val GUAZI_RECOMMENDATION_DAY_KEY = "guazi_recommendation_day_v2"
        private const val GUAZI_RECOMMENDATION_IDS_KEY = "guazi_recommendation_ids_v2"

        internal const val HOME_PARTITION_CONCURRENCY = 8

        internal fun shouldShowHomeContent(
            bootReady: Boolean,
            sortsLoaded: Boolean,
            recState: PartitionState,
            partitionStates: List<PartitionState>,
        ): Boolean = bootReady && sortsLoaded &&
            (recState != PartitionState.Loading || partitionStates.any { it != PartitionState.Loading })

        internal fun defaultSortId(sorts: List<MovieSort.SortData>): String? =
            sorts.firstOrNull()?.id

        internal fun isCurrentSourceResult(currentKey: String, resultKey: String?): Boolean =
            resultKey == currentKey

        internal fun partitionRequestKey(generation: Int, sortId: String): String =
            "$generation:$sortId"
    }

    /** 首页优先使用瓜子；瓜子首屏无有效内容时临时切换西瓜。 */
    private fun fixedHomeSource(): SourceBean? {
        return fixedHomeSources().firstOrNull()
    }

    private fun fixedHomeSources(): List<SourceBean> {
        val sources = ApiConfig.get().getSourceBeanList()
        val preferred = sources.firstOrNull { it.key.equals(PREFERRED_HOME_SOURCE_KEY, true) }
            ?: sources.firstOrNull { it.api?.contains(PREFERRED_HOME_SOURCE_KEY, true) == true }
        val fallback = sources.firstOrNull { it.key.equals(FALLBACK_HOME_SOURCE_KEY, true) }
            ?: sources.firstOrNull {
                it.key?.contains(FALLBACK_HOME_SOURCE_KEY, ignoreCase = true) == true ||
                    it.api?.contains(FALLBACK_HOME_SOURCE_KEY, ignoreCase = true) == true
            }
        return listOfNotNull(preferred, fallback).distinctBy { it.key }
    }

    sealed interface PartitionState {
        data object Idle : PartitionState
        data object Loading : PartitionState
        data object Empty : PartitionState
        data object Ready : PartitionState
        data object Error : PartitionState
    }

    data class Partition(
        val sort: MovieSort.SortData,
        val state: PartitionState,
        val videos: List<Movie.Video>,
        val nextPage: Int,
        val maxPage: Int,
    ) {
        companion object {
            const val FIRST_PAGE = 1
        }

        val hasMore: Boolean get() = !(maxPage > 0 && nextPage > maxPage)
    }

    data class Rec(val state: PartitionState, val videos: List<Movie.Video>)

    val currentSource = MutableStateFlow<SourceBean?>(null)
    val sources = MutableStateFlow<List<SourceBean>>(emptyList())
    val allSorts = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val sorts = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val recTitle = MutableStateFlow("推荐")
    val rec = MutableStateFlow(Rec(PartitionState.Loading, emptyList()))
    val partitions = MutableStateFlow<List<Partition>>(emptyList())

    val pageLoading = MutableStateFlow(true)
    private val sortsLoaded = MutableStateFlow(false)
    private val bootReady = MutableStateFlow(false)
    val pageErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val scope = viewModelScope
    private val sortViewModel = SourceViewModel()
    private val actionViewModel = SourceViewModel()
    private val recViewModel = SourceViewModel()
    private val loaders = HashMap<String, PartitionLoader>()
    private val loadSemaphore = Semaphore(HOME_PARTITION_CONCURRENCY)
    /**
     * A request can wait on the semaphore for a while. Reserve the partition
     * before launching that coroutine so repeated recompositions/retries do
     * not enqueue the same category more than once.
     */
    private val inFlightPartitions = HashSet<String>()
    private var loadGeneration = 0
    private var loadingSourceKey: String? = null
    private var fallbackAttempted = false
    private var watchdogJob: Job? = null

    /**
     * Keep the last usable home state per source so switching back does not
     * recreate the skeleton screen while the source cache is being consulted.
     * The snapshot is intentionally small and bounded; reload() clears it.
     */
    private data class HomeSnapshot(
        val rec: Rec,
        val partitions: List<Partition>,
        val sorts: List<MovieSort.SortData>,
        val allSorts: List<MovieSort.SortData>,
        val activeSortId: String?,
    )

    private val homeSnapshots = object : LinkedHashMap<String, HomeSnapshot>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HomeSnapshot>?): Boolean =
            size > 4
    }

    var activeSortId: String? = null
        private set

    var defaultLiveLaunched = false
    var lastBackTime = 0L

    private val sortObserver = Observer<AbsSortXml> { absXml: AbsSortXml? -> onSortResult(absXml) }

    private val recObserver = Observer<AbsSortXml> { absXml: AbsSortXml? -> onRecResult(absXml) }

    val actionMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val actionObserver = Observer<JSONObject?> { json ->
        val msg = json?.optString("msg").orEmpty()
        if (msg.isNotEmpty()) actionMessages.tryEmit(msg)
    }

    init {
        EventBus.getDefault().register(this)
        sortViewModel.sortResult.observeForever(sortObserver)
        recViewModel.sortResult.observeForever(recObserver)
        actionViewModel.actionResult.observeForever(actionObserver)
        sources.value = fixedHomeSources()
        currentSource.value = fixedHomeSource()
        scope.launch {
            AppBootstrap.state.collect {
                bootReady.value = it is AppBootstrap.Boot.Ready
                if (it is AppBootstrap.Boot.Ready) loadHome()
            }
        }
        scope.launch {
            combine(bootReady, rec, partitions, sortsLoaded) { ready, r, ps, loaded ->
                shouldShowHomeContent(ready, loaded, r.state, ps.map { it.state })
            }.collect { ready ->
                if (ready && pageLoading.value) {
                    pageLoading.value = false
                    watchdogJob?.cancel()
                }
            }
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
        sortViewModel.sortResult.removeObserver(sortObserver)
        recViewModel.sortResult.removeObserver(recObserver)
        actionViewModel.actionResult.removeObserver(actionObserver)
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            reload()
        }
    }

    fun reload() {
        SourceViewModel.clearRuntimeCache()
        homeSnapshots.clear()
        loadHome()
    }

    fun loadHome() {
        // 每次重新加载首页都从新资源站的第一个分类开始,避免复用旧站点分类 ID。
        activeSortId = null
        fallbackAttempted = false
        // 新一轮加载使用新的代际,旧请求不能继续占用去重集合。
        synchronized(inFlightPartitions) {
            inFlightPartitions.clear()
        }
        sources.value = fixedHomeSources()
        val home = fixedHomeSource()
        if (home == null) {
            loadingSourceKey = null
            currentSource.value = null
            pageLoading.value = false
            sortsLoaded.value = true
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            return
        }
        loadingSourceKey = if (home.key.isNullOrEmpty()) null else home.key
        currentSource.value = home
        pageLoading.value = true
        sortsLoaded.value = false
        sorts.value = emptyList()
        allSorts.value = emptyList()
        recTitle.value = "推荐"
        rec.value = Rec(PartitionState.Loading, emptyList())
        partitions.value = emptyList()
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
        loadGeneration++
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(20_000)
            onHomeLoadTimeout()
        }
        val restored = loadingSourceKey?.let { restoreSnapshot(it) } == true
        if (restored) {
            // The snapshot is already usable. Continue only requests that were
            // still loading when it was saved; do not block on a fresh sort call.
            pageLoading.value = !shouldShowHomeContent(
                bootReady.value,
                sortsLoaded.value,
                rec.value.state,
                partitions.value.map { it.state },
            )
            partitions.value
                .filter { it.state == PartitionState.Loading }
                .forEach { requestPartition(it, Partition.FIRST_PAGE) }
        } else {
            // The home screen always uses the Xigua-style template, which
            // needs the recommendation feed and all category sections.
            sortViewModel.getSort(loadingSourceKey, true)
        }
    }

    private fun restoreSnapshot(sourceKey: String): Boolean {
        val snapshot = homeSnapshots[sourceKey] ?: return false
        rec.value = snapshot.rec.copy(videos = snapshot.rec.videos.toList())
        partitions.value = snapshot.partitions.map { partition ->
            partition.copy(videos = partition.videos.toList())
        }
        sorts.value = snapshot.sorts.toList()
        allSorts.value = snapshot.allSorts.toList()
        // A restored source must always open on its first category, never on
        // the last category selected in a previous visit.
        activeSortId = defaultSortId(snapshot.sorts)
        sortsLoaded.value = true
        return true
    }

    private fun saveSnapshot() {
        val key = loadingSourceKey ?: return
        if (!sortsLoaded.value && sorts.value.isEmpty() && partitions.value.isEmpty()) return
        homeSnapshots[key] = HomeSnapshot(
            rec = rec.value.copy(videos = rec.value.videos.toList()),
            partitions = partitions.value.map { it.copy(videos = it.videos.toList()) },
            sorts = sorts.value.toList(),
            allSorts = allSorts.value.toList(),
            activeSortId = activeSortId,
        )
    }

    private fun onHomeLoadTimeout() {
        val recLoading = rec.value.state == PartitionState.Loading
        val partitionLoading = partitions.value.any { it.state == PartitionState.Loading }
        if (!recLoading && !partitionLoading) return
        if (isGuaziSource() && !hasHomeContent() && switchToXigua()) return
        if (recLoading) {
            rec.value = Rec(PartitionState.Error, emptyList())
        }
        if (partitionLoading) {
            partitions.value = partitions.value.map { p ->
                if (p.state == PartitionState.Loading) p.copy(state = PartitionState.Error) else p
            }
        }
        pageErrorEvents.tryEmit(
            if (recLoading) "首页加载失败，请检查网络后重试"
            else "部分内容加载超时，可下拉刷新重试"
        )
        pageLoading.value = false
    }

    fun retryPartition(partition: Partition) {
        if (partition.state != PartitionState.Error) return
        partitions.value = partitions.value.map {
            if (it.sort.id == partition.sort.id) it.copy(state = PartitionState.Loading) else it
        }
        requestPartition(partition, Partition.FIRST_PAGE, replaceExisting = true)
    }

    fun ensureLoaded(sortId: String) {
        activeSortId = sortId
        val current = partitions.value.firstOrNull { it.sort.id == sortId } ?: return
        if (current.state != PartitionState.Idle) return
        partitions.value = partitions.value.map {
            if (it.sort.id == sortId) it.copy(state = PartitionState.Loading) else it
        }
        requestPartition(current, Partition.FIRST_PAGE)
    }

    fun onLayoutChanged() {
        val idle = partitions.value.filter { it.state == PartitionState.Idle }
        if (idle.isNotEmpty()) {
            partitions.value = partitions.value.map {
                if (it.state == PartitionState.Idle) it.copy(state = PartitionState.Loading) else it
            }
            idle.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
        }
        val key = loadingSourceKey
        val hasRecSort = allSorts.value.any { it.id == "my0" }
        if (key != null && hasRecSort && rec.value.videos.isEmpty() &&
            rec.value.state != PartitionState.Loading
        ) {
            rec.value = Rec(PartitionState.Loading, emptyList())
            recViewModel.getSort(key, true)
        }
    }

    private fun onSortResult(absXml: AbsSortXml?) {
        val key = loadingSourceKey
        if (key == null) {
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            sorts.value = emptyList()
            allSorts.value = emptyList()
            sortsLoaded.value = true
            return
        }
        // 首页响应必须明确属于当前资源站；未标记的迟到响应也不能覆盖切源后的状态。
        if (absXml == null) {
            if (isGuaziSource() && switchToXigua()) return
            return
        }
        if (!isCurrentSourceResult(key, absXml.sourceKey)) return

        val adjusted = if (absXml.classes?.sortList != null) {
            DefaultConfig.adjustSort(key, absXml.classes.sortList, true)
        } else {
            DefaultConfig.adjustSort(key, ArrayList(), true)
        }
        if (isGuaziSource() && adjusted.none { it.id != "my0" }) {
            if (switchToXigua()) return
        }
        allSorts.value = adjusted

        val isGuazi = isGuaziSource()
        val recSort = adjusted.firstOrNull { it.id == "my0" }
        if (recSort != null && !isGuazi) {
            loadRec(absXml)
        } else if (isGuazi) {
            // 瓜子顶部推荐不再复用首页接口的第一组固定内容，改由前三个标签的结果随机组成。
            rec.value = Rec(PartitionState.Loading, emptyList())
        } else {
            rec.value = Rec(PartitionState.Empty, emptyList())
        }

        val sourceSorts = adjusted.filter { it.id != "my0" }
        recTitle.value = if (isGuazi) {
            "推荐"
        } else {
            "推荐"
        }
        // 瓜子首页的第一个标签也要显示在模板中；顶部推荐单独从前三个标签取样。
        val visible = sourceSorts
        sorts.value = visible
        val vertical = false
        val active = activeSortId?.takeIf { id -> visible.any { it.id == id } } ?: visible.firstOrNull()?.id
        activeSortId = active
        val newPartitions = visible.map { sort ->
            if (vertical && sort.id != active) {
                Partition(sort, PartitionState.Idle, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                Partition(sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            }
        }
        partitions.value = newPartitions
        sortsLoaded.value = true
        saveSnapshot()
        requestPartitionsProgressively(newPartitions)
    }

    private fun loadRec(absXml: AbsSortXml?) {
        val videos = absXml?.videoList ?: emptyList()
        rec.value = if (videos.isEmpty()) Rec(PartitionState.Empty, videos) else Rec(PartitionState.Ready, videos)
        saveSnapshot()
    }

    private fun onRecResult(absXml: AbsSortXml?) {
        val key = loadingSourceKey ?: return
        if (!isCurrentSourceResult(key, absXml?.sourceKey)) return
        loadRec(absXml)
    }

    private fun requestPartitionsProgressively(partitionsToLoad: List<Partition>) {
        val generation = loadGeneration
        partitionsToLoad
            .filter { it.state == PartitionState.Loading }
            .forEachIndexed { index, partition ->
                scope.launch {
                    if (index >= IMMEDIATE_HOME_PARTITIONS) {
                        delay((index - IMMEDIATE_HOME_PARTITIONS + 1) * LAZY_HOME_PARTITION_DELAY_MS)
                    }
                    if (generation != loadGeneration || loadingSourceKey.isNullOrEmpty()) return@launch
                    requestPartition(partition, Partition.FIRST_PAGE)
                }
            }
    }

    private class LoaderResult(val stale: Boolean, val absXml: AbsXml?)

    private fun requestPartition(
        current: Partition,
        page: Int,
        replaceExisting: Boolean = false,
    ) {
        val generation = loadGeneration
        val requestKey = partitionRequestKey(generation, current.sort.id)
        synchronized(inFlightPartitions) {
            if (replaceExisting) inFlightPartitions.remove(requestKey)
            if (!inFlightPartitions.add(requestKey)) return
        }
        val sourceKey = loadingSourceKey ?: return
        val loader = loaders.getOrPut(current.sort.id) { PartitionLoader(sourceKey, current.sort) }
        scope.launch {
            try {
                loadSemaphore.withPermit {
                    if (generation != loadGeneration || loader.released) return@withPermit
                    val result = suspendCancellableCoroutine<LoaderResult> { cont ->
                        loader.request(page) { r -> if (cont.isActive) cont.resume(r) }
                    }
                    if (!result.stale) {
                        applyPartitionResult(generation, current.sort.id, page, result.absXml)
                    }
                }
            } finally {
                synchronized(inFlightPartitions) {
                    inFlightPartitions.remove(requestKey)
                }
            }
        }
    }

    private fun applyPartitionResult(generation: Int, sortId: String, page: Int, absXml: AbsXml?) {
        if (generation != loadGeneration) return
        val videos = absXml?.movie?.videoList ?: emptyList()
        val maxPage = absXml?.movie?.pagecount ?: 0
        partitions.value = partitions.value.map { p ->
            if (p.sort.id != sortId) {
                p
            } else if (videos.isEmpty() && page == Partition.FIRST_PAGE) {
                Partition(p.sort, PartitionState.Empty, emptyList(), Partition.FIRST_PAGE, maxPage)
            } else {
                val merged = if (page == 0) videos else p.videos + videos
                Partition(p.sort, PartitionState.Ready, merged, page + 1, maxPage)
            }
        }
        saveSnapshot()
        if (isGuaziSource()) refreshGuaziRecommendation()
        if (isGuaziSource() && !hasHomeContent()
            && partitions.value.isNotEmpty()
            && partitions.value.all { it.state == PartitionState.Ready
                    || it.state == PartitionState.Empty
                    || it.state == PartitionState.Error }
        ) {
            switchToXigua()
        }
    }

    private fun isGuaziSource(): Boolean = loadingSourceKey.equals(PREFERRED_HOME_SOURCE_KEY, true)

    private fun hasHomeContent(): Boolean = rec.value.videos.isNotEmpty()
            || partitions.value.any { it.videos.isNotEmpty() }

    private fun refreshGuaziRecommendation() {
        if (rec.value.state == PartitionState.Ready && rec.value.videos.isNotEmpty()) return
        val firstTwo = partitions.value.take(2)
        if (firstTwo.size < 2 || firstTwo.any { it.state == PartitionState.Loading }) return

        val candidates = ArrayList<Movie.Video>()
        val seen = HashSet<String>()
        partitions.value
            .take(GUAZI_RECOMMENDATION_SOURCE_COUNT)
            .flatMap { it.videos }
            .forEach { video ->
                val identity = recommendationIdentity(video)
                if (seen.add(identity)) candidates.add(video)
            }
        if (candidates.isEmpty()) return

        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val cached = readDailyRecommendation(day, candidates)
        val selected = if (cached.isNotEmpty()) {
            cached
        } else {
            val first = partitions.value.getOrNull(0)?.videos.orEmpty()
            val second = partitions.value.getOrNull(1)?.videos.orEmpty()
            val seed = day.toLongOrNull() ?: day.hashCode().toLong()
            val picked = ArrayList<Movie.Video>()
            addStableSample(picked, first, 3, seed + 11L)
            addStableSample(picked, second, 2, seed + 29L)
            if (picked.size < GUAZI_RECOMMENDATION_SIZE) {
                addStableSample(picked, candidates, GUAZI_RECOMMENDATION_SIZE - picked.size, seed + 47L)
            }
            persistDailyRecommendation(day, picked)
            picked
        }
        if (selected.isEmpty()) return
        rec.value = Rec(PartitionState.Ready, selected.take(GUAZI_RECOMMENDATION_SIZE))
        saveSnapshot()
    }

    private fun recommendationIdentity(video: Movie.Video): String =
        video.id?.takeIf { it.isNotBlank() } ?: "${video.name}|${video.pic}"

    private fun addStableSample(
        target: MutableList<Movie.Video>,
        source: List<Movie.Video>,
        count: Int,
        seed: Long,
    ) {
        if (count <= 0) return
        val existing = target.mapTo(HashSet()) { recommendationIdentity(it) }
        source.asSequence()
            .distinctBy { recommendationIdentity(it) }
            .toList()
            .shuffled(Random(seed))
            .filter { existing.add(recommendationIdentity(it)) }
            .take(count)
            .forEach(target::add)
    }

    private fun readDailyRecommendation(day: String, candidates: List<Movie.Video>): List<Movie.Video> {
        return runCatching {
            if (KV.get(GUAZI_RECOMMENDATION_DAY_KEY, "") != day) return@runCatching emptyList()
            val byId = candidates.associateBy { recommendationIdentity(it) }
            KV.get(GUAZI_RECOMMENDATION_IDS_KEY, "")
                .split("\n")
                .filter { it.isNotBlank() }
                .mapNotNull { byId[it] }
                .take(GUAZI_RECOMMENDATION_SIZE)
        }.getOrDefault(emptyList())
    }

    private fun persistDailyRecommendation(day: String, videos: List<Movie.Video>) {
        runCatching {
            KV.put(GUAZI_RECOMMENDATION_DAY_KEY, day)
            KV.put(
                GUAZI_RECOMMENDATION_IDS_KEY,
                videos.take(GUAZI_RECOMMENDATION_SIZE).joinToString("\n") { recommendationIdentity(it) },
            )
        }
    }

    private fun switchToXigua(): Boolean {
        if (!isGuaziSource() || fallbackAttempted) return false
        val fallback = fixedHomeSources().firstOrNull { it.key.equals(FALLBACK_HOME_SOURCE_KEY, true) }
            ?: return false
        fallbackAttempted = true
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
        synchronized(inFlightPartitions) { inFlightPartitions.clear() }
        loadGeneration++
        loadingSourceKey = fallback.key
        currentSource.value = fallback
        sources.value = fixedHomeSources()
        sortsLoaded.value = false
        sorts.value = emptyList()
        allSorts.value = emptyList()
        recTitle.value = "推荐"
        rec.value = Rec(PartitionState.Loading, emptyList())
        partitions.value = emptyList()
        pageLoading.value = true
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(20_000)
            onHomeLoadTimeout()
        }
        sortViewModel.getSort(fallback.key, true)
        return true
    }

    fun loadMorePartition(partition: Partition) {
        if (partition.state != PartitionState.Ready || !partition.hasMore) return
        val loader = loaders[partition.sort.id] ?: return
        if (loader.busy) return
        requestPartition(partition, partition.nextPage)
    }

    fun applyFilter(partition: Partition, filterSelect: Map<String, String>) {
        partition.sort.filterSelect = HashMap(filterSelect)
        partitions.value = partitions.value.map {
            if (it.sort.id == partition.sort.id) {
                Partition(it.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                it
            }
        }
        requestPartition(
            partition.copy(sort = partition.sort),
            Partition.FIRST_PAGE,
            replaceExisting = true,
        )
    }

    fun handleAction(video: Movie.Video) {
        actionViewModel.action(video.sourceKey, video.action)
    }

    fun refreshPartitions() {
        val vertical = false
        val active = activeSortId
        val targets = if (vertical) {
            partitions.value.filter { it.sort.id == active }
        } else {
            partitions.value
        }
        partitions.value = partitions.value.map { p ->
            if (targets.any { it.sort.id == p.sort.id }) {
                Partition(p.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                p
            }
        }
        targets.forEach { p -> requestPartition(p, Partition.FIRST_PAGE, replaceExisting = true) }
    }

    private inner class PartitionLoader(
        private val sourceKey: String,
        val sort: MovieSort.SortData,
    ) {
        private val svm = SourceViewModel()
        @Volatile
        private var pending: ((LoaderResult) -> Unit)? = null
        @Volatile
        private var lastIssuedSeq: Int = 0
        @Volatile
        private var requestSeq: Int = 0

        @Volatile
        var busy: Boolean = false
            private set

        @Volatile
        var released: Boolean = false
            private set

        private val observer = Observer<AbsXml> { abs: AbsXml? ->
            val current = pending
            val seq = requestSeq
            pending = null
            busy = false
            if (current != null && seq == lastIssuedSeq) {
                current.invoke(LoaderResult(stale = false, absXml = abs))
            }
        }

        init {
            svm.listResult.observeForever(observer)
        }

        /**
         * 每次发起请求都会递增代际;旧请求(切源后停更的 LiveData 分支)的结果
         * 会被 seq 代际丢弃,由新请求独占 pending 槽。缓解并发下 listResult
         * 共享导致的"上一分类响应覆盖下一分类首屏"竞态。
         */
        fun request(page: Int, onDone: (LoaderResult) -> Unit) {
            requestSeq += 1
            val seq = requestSeq
            pending = onDone
            lastIssuedSeq = seq
            busy = true
            svm.getList(sourceKey, sort, page)
        }

        fun release() {
            released = true
            pending?.invoke(LoaderResult(stale = true, absXml = null))
            pending = null
            busy = false
            svm.listResult.removeObserver(observer)
            lastIssuedSeq += 1
        }
    }
}
