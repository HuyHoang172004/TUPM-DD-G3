package com.adaptivenews.app.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adaptivenews.app.adaptive.PolicyController
import com.adaptivenews.app.core.contract.ArticleDetail
import com.adaptivenews.app.core.contract.ArticleSummary
import com.adaptivenews.app.core.contract.NewsRepository
import com.adaptivenews.app.core.contract.Variant
import com.adaptivenews.app.telemetry.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AppScreen { FEED, SEARCH, DETAIL, SAVED, LAB }

class AppViewModel(
    private val repository: NewsRepository,
    val controller: PolicyController,
    val telemetry: Telemetry,
    val variant: Variant,
    val isFixture: Boolean
) : ViewModel() {
    private val mutableScreen = MutableStateFlow(AppScreen.FEED)
    val screen: StateFlow<AppScreen> = mutableScreen
    private val mutableItems = MutableStateFlow<List<ArticleSummary>>(emptyList())
    val items: StateFlow<List<ArticleSummary>> = mutableItems
    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery
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
    private var activeQuery = ""
    private var searchStartedAt: Long? = null
    private var generation = 0

    init {
        viewModelScope.launch { repository.saved().collect { mutableSaved.value = it } }
        refresh("")
    }

    fun navigate(target: AppScreen) {
        mutableScreen.value = target
        if (target == AppScreen.FEED && activeQuery.isNotEmpty()) refresh("")
        if (target == AppScreen.SEARCH && activeQuery != mutableQuery.value.trim()) refresh(mutableQuery.value)
    }

    fun setQuery(value: String) {
        mutableQuery.value = value
        searchStartedAt = SystemClock.elapsedRealtime()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            val normalized = value.trim()
            if (normalized != activeQuery) refresh(normalized)
        }
    }

    fun refresh(query: String = activeQuery) {
        generation++
        pageJob?.cancel()
        activeQuery = query.trim()
        nextCursor = null
        hasFirstPage = false
        mutableItems.value = emptyList()
        mutableError.value = null
        mutableLoading.value = false
        loadMore()
    }

    fun loadMore() {
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
                    if (!hasFirstPage && requestQuery.isNotEmpty()) {
                        searchStartedAt?.let { started ->
                            telemetry.record("search_ready", mode = controller.policy.value.mode.name,
                                source = if (isFixture) "fixture" else "real",
                                value = SystemClock.elapsedRealtime() - started, unit = "ms",
                                attrs = mapOf("query" to requestQuery))
                        }
                        searchStartedAt = null
                    }
                    hasFirstPage = true
                } while (variant == Variant.V1 && nextCursor != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestGeneration == generation) mutableError.value = e.message ?: "Không thể tải bài viết"
            } finally {
                if (requestGeneration == generation) mutableLoading.value = false
            }
        }
    }

    fun openArticle(id: Long) {
        detailJob?.cancel()
        mutableDetail.value = null
        mutableError.value = null
        mutableScreen.value = AppScreen.DETAIL
        detailJob = viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            try {
                mutableDetail.value = repository.detail(id)
                telemetry.record("detail_ready", mode = controller.policy.value.mode.name,
                    source = if (isFixture) "fixture" else "real",
                    value = SystemClock.elapsedRealtime() - start, unit = "ms", attrs = mapOf("id" to id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableError.value = e.message ?: "Không thể mở bài viết"
            }
        }
    }

    fun toggleSave() {
        val article = mutableDetail.value ?: return
        val alreadySaved = mutableSaved.value.any { it.article.id == article.article.id }
        viewModelScope.launch { repository.setSaved(article, !alreadySaved) }
    }
}
