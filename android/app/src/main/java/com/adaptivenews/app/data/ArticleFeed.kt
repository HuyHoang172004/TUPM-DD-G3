package com.adaptivenews.app.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.adaptivenews.app.core.contract.ArticleSummary
import com.adaptivenews.app.core.contract.NewsRepository
import com.adaptivenews.app.core.contract.PolicyProvider
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ArticleFeed(
    private val repository: NewsRepository,
    private val policies: PolicyProvider,
) {
    /** V1 feed/search. Each query loads all summaries, in contract-sized batches. */
    fun eager(
        queries: Flow<String> = flowOf(""),
        refreshes: Flow<Unit> = emptyFlow(),
    ): Flow<List<ArticleSummary>> = results(queries, refreshes) { query ->
        flow {
            val articles = mutableListOf<ArticleSummary>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val page = repository.page(query, cursor, limit = 100)
                articles.addAll(page.items)
                cursor = page.nextCursor
                if (cursor != null && !seenCursors.add(cursor)) throw IOException("Repeated cursor")
            } while (cursor != null)
            emit(articles)
        }
    }

    /** V2 and V3 feed/search. Use refreshes for policy-aware explicit refreshes. */
    fun paged(
        queries: Flow<String> = flowOf(""),
        refreshes: Flow<Unit> = emptyFlow(),
    ): Flow<PagingData<ArticleSummary>> = results(queries, refreshes) { query ->
        val size = policies.policy.value.pageSize
        Pager(
            PagingConfig(
                pageSize = size,
                initialLoadSize = size,
                prefetchDistance = 1,
                enablePlaceholders = false,
                maxSize = size * 5,
            ),
        ) {
            // A native Paging refresh also starts a new source and reads the current policy.
            ArticlePagingSource(repository, query, policies.policy.value.pageSize)
        }.flow
    }

    private fun <T> results(
        queries: Flow<String>,
        refreshes: Flow<Unit>,
        load: (String) -> Flow<T>,
    ): Flow<T> = queries.map(String::trim)
        .distinctUntilChanged()
        .debounce(300)
        .flatMapLatest { query -> refreshes.onStart { emit(Unit) }.flatMapLatest { load(query) } }
        .buffer(0)
}

private data class CursorKey(val cursor: String?, val position: Int)

private class ArticlePagingSource(
    private val repository: NewsRepository,
    private val query: String,
    private val pageSize: Int,
) : PagingSource<CursorKey, ArticleSummary>() {
    private val first = CursorKey(null, 0)
    // Only cursors are retained when Paging drops item pages outside its bounded window.
    private val keys = ConcurrentHashMap<Int, CursorKey>().apply { put(0, first) }

    override fun getRefreshKey(state: PagingState<CursorKey, ArticleSummary>): CursorKey? = null

    override suspend fun load(params: LoadParams<CursorKey>): LoadResult<CursorKey, ArticleSummary> {
        val key = params.key ?: first
        return try {
            val page = repository.page(query, key.cursor, pageSize)
            val next = page.nextCursor?.let { cursor ->
                if (cursor == key.cursor || page.items.isEmpty()) throw IOException("Invalid page cursor")
                CursorKey(cursor, key.position + 1).also { keys[it.position] = it }
            }
            LoadResult.Page(page.items, keys[key.position - 1], next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LoadResult.Error(failure)
        }
    }
}
