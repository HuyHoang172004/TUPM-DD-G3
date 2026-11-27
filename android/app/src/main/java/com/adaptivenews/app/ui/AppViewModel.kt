package com.adaptivenews.app.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.adaptivenews.app.adaptive.PolicyController
import com.adaptivenews.app.core.contract.ArticleDetail
import com.adaptivenews.app.core.contract.ArticleSummary
import com.adaptivenews.app.core.contract.NewsRepository
import com.adaptivenews.app.core.contract.Variant
import com.adaptivenews.app.data.NewsData
import com.adaptivenews.app.telemetry.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.adaptivenews.app.experiment.Experiment

enum class AppScreen { FEED, SEARCH, DETAIL, SAVED, LAB }

class AppViewModel(
    val newsData: NewsData,
    val controller: PolicyController,
    val telemetry: Telemetry,
    val variant: Variant
) : ViewModel() {
    val useEager = Experiment.paging?.not() ?: (variant == Variant.V1)
    private val feedStartedAt = SystemClock.elapsedRealtime()
    private var feedReported = false
    private val repository: NewsRepository = newsData.repository
    private val mutableScreen = MutableStateFlow(AppScreen.FEED)
    val screen: StateFlow<AppScreen> = mutableScreen
    private val mutableItems = MutableStateFlow<List<ArticleSummary>>(emptyList())
    val items: StateFlow<List<ArticleSummary>> = mutableItems
    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery
    private val activeQueryFlow = MutableStateFlow("")
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var presentedQuery = ""
    val pagedItems: Flow<PagingData<ArticleSummary>> =
        newsData.feed.paged(activeQueryFlow, refreshes, onQuery = { presentedQuery = it })
            .cachedIn(viewModelScope)
    private var prefetcher = newsData.prefetcher(viewModelScope)
    private var prefetchPaused = false
    private val mutableLoading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = mutableLoading
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private val mutableDetail = MutableStateFlow<ArticleDetail?>(null)
    val detail: StateFlow<ArticleDetail?> = mutableDetail
    private val mutableSaved = MutableStateFlow<List<ArticleDetail>>(emptyList())
    val saved: StateFlow<List<ArticleDetail>> = mutableSaved
    private var nextCursor: String? = null
    private var hasFirstPage = false
    private var searchJob: Job? = null
    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var selectedArticleId: Long? = null
    private var activeQuery = ""
    private var searchStartedAt: Long? = null
    private var generation = 0

    init {
        viewModelScope.launch { repository.saved().collect { mutableSaved.value = it } }
        if (useEager) refresh("")
    }

    fun navigate(target: AppScreen) {
        if (mutableScreen.value != target) clearPrefetch()
        mutableScreen.value = target
        if (target == AppScreen.FEED && activeQuery.isNotEmpty()) refresh("")
        if (target == AppScreen.SEARCH && activeQuery != mutableQuery.value.trim()) refresh(mutableQuery.value)
    }

    fun setQuery(value: String) {
        if (Experiment.active) telemetry.record("query_input", attrs = mapOf("text" to value))
        mutableQuery.value = value
        searchStartedAt = SystemClock.elapsedRealtime()
        searchJob?.cancel()
        if (!useEager) {
            activeQuery = value.trim()
            activeQueryFlow.value = if (Experiment.isolated) value else activeQuery
            return
        }
        searchJob = viewModelScope.launch {
            delay(Experiment.int("debounce_ms", 300).toLong())
            val normalized = value.trim()
            if (normalized != activeQuery) refresh(normalized)
        }
    }

    fun refresh(query: String = activeQuery) {
        if (!useEager) {
            activeQuery = query.trim()
            if (activeQueryFlow.value == activeQuery) refreshes.tryEmit(Unit)
            else activeQueryFlow.value = activeQuery
            return
        }
        generation++
        if (Experiment.bool("cancel_previous", true)) pageJob?.cancel()
        activeQuery = query.trim()
        nextCursor = null
        hasFirstPage = false
        mutableItems.value = emptyList()
        mutableError.value = null
        mutableLoading.value = false
        loadMore()
    }

    fun loadMore() {
        if (!useEager) return
        if (mutableLoading.value || hasFirstPage && nextCursor == null) return
        mutableLoading.value = true
        val requestGeneration = generation
        val requestQuery = activeQuery
        pageJob = viewModelScope.launch {
            try {
                do {
                    val page = repository.page(requestQuery, nextCursor, controller.policy.value.pageSize)
                    if (requestGeneration != generation) return@launch
                    mutableItems.value = mutableItems.value + page.items
                    nextCursor = page.nextCursor
                    hasFirstPage = true
                } while (useEager && nextCursor != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestGeneration == generation) mutableError.value = e.message ?: "Không thể tải bài viết"
            } finally {
                if (requestGeneration == generation) mutableLoading.value = false
            }
        }
    }

    fun onPagedReady() {
        if (presentedQuery == activeQuery) reportSearchReady()
    }

    fun onEagerPresented() {
        onFeedPresented()
        reportSearchReady()
    }

    fun onFeedPresented() {
        if (activeQuery.isEmpty()) reportFeedReady()
    }

    private fun reportSearchReady() {
        val started = searchStartedAt ?: return
        if (activeQuery.isEmpty()) return
        if (Experiment.active) com.adaptivenews.app.experiment.ExperimentRuntime.readyQuery = activeQuery
        searchStartedAt = null
        telemetry.record("search_ready", mode = controller.policy.value.mode.name,
            value = SystemClock.elapsedRealtime() - started, unit = "ms",
            attrs = mapOf("query" to activeQuery))
    }

    fun updatePrefetch(upcoming: List<ArticleSummary>) {
        if (!prefetchPaused && !useEager &&
            (mutableScreen.value == AppScreen.FEED || mutableScreen.value == AppScreen.SEARCH)) {
            prefetcher.update(upcoming)
        }
    }

    fun clearPrefetch() = prefetcher.clear()

    suspend fun stopPrefetch() {
        prefetchPaused = true
        prefetcher.stop()
    }

    fun restartPrefetch() {
        prefetcher = newsData.prefetcher(viewModelScope)
        prefetchPaused = false
    }

    fun imageDisplayed(article: ArticleSummary, width: Int) {
        newsData.imageDisplayed(newsData.imageUrls.url(article, width))
    }

    fun openArticle(id: Long) {
        clearPrefetch()
        selectedArticleId = id
        detailJob?.cancel()
        mutableDetail.value = null
        mutableError.value = null
        mutableScreen.value = AppScreen.DETAIL
        detailJob = viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            try {
                mutableDetail.value = repository.detail(id)
                telemetry.record("detail_ready", mode = controller.policy.value.mode.name,
                    source = "real",
                    value = SystemClock.elapsedRealtime() - start, unit = "ms", attrs = mapOf("id" to id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableError.value = e.message ?: "Không thể mở bài viết"
            }
        }
    }

    fun retryDetail() { selectedArticleId?.let(::openArticle) }

    fun endExperiment() {
        check(Experiment.active)
        viewModelScope.cancel()
    }

    private fun reportFeedReady() {
        if (feedReported || !Experiment.active) return
        feedReported = true
        telemetry.record("feed_ready", value = SystemClock.elapsedRealtime()-feedStartedAt, unit = "ms")
    }

    fun toggleSave() {
        val article = mutableDetail.value ?: return
        val alreadySaved = mutableSaved.value.any { it.article.id == article.article.id }
        viewModelScope.launch { repository.setSaved(article, !alreadySaved) }
    }
}
