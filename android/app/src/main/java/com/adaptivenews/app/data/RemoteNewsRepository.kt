package com.adaptivenews.app.data

import com.adaptivenews.app.core.contract.ArticleDetail
import com.adaptivenews.app.core.contract.ArticlePage
import com.adaptivenews.app.core.contract.NewsRepository
import com.adaptivenews.app.core.contract.PolicyProvider
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

internal interface NewsApi {
    @GET("v1/articles")
    suspend fun page(
        @Query("q") query: String?,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): Response<ResponseBody>

    @GET("v1/articles/{id}")
    suspend fun detail(@Path("id") id: Long): Response<ResponseBody>
}

class OfflineException : IOException("Network access is unavailable")

class NewsHttpException(val status: Int, message: String) : IOException(message)

class RemoteNewsRepository internal constructor(
    private val api: NewsApi,
    private val dao: ArticleDao,
    private val policies: PolicyProvider,
    requests: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NewsRepository {
    private data class PageKey(val query: String, val cursor: String?, val limit: Int)

    private val pages = SharedRequests<PageKey, ArticlePage>(requests)
    private val details = SharedRequests<Long, ArticleDetail>(requests)

    override suspend fun page(query: String, cursor: String?, limit: Int): ArticlePage {
        require(limit in 1..100)
        val trimmed = query.trim()
        if (!policies.policy.value.allowNetwork) {
            if (cursor == null && trimmed.isEmpty()) {
                val cached = dao.firstBatch(limit).map(FeedRow::toDomain)
                if (cached.isNotEmpty()) return ArticlePage(cached, null)
            }
            throw OfflineException()
        }
        return fetchPage(trimmed, cursor, limit)
    }

    private suspend fun fetchPage(query: String, cursor: String?, limit: Int): ArticlePage {
        val fetch: suspend () -> ArticlePage = {
            withContext(io) {
                retryTransientRead {
                    ArticleWire.page(api.page(query.ifEmpty { null }, cursor, limit).readJson()).toDomain()
                }
            }
        }
        return if (policies.policy.value.optimized) {
            pages.run(PageKey(query, cursor, limit), fetch)
        } else fetch()
    }

    override suspend fun detail(id: Long): ArticleDetail {
        require(id > 0)
        dao.savedById(id)?.let { return it.toDomain() }
        if (!policies.policy.value.allowNetwork) throw OfflineException()
        val fetch: suspend () -> ArticleDetail = {
            withContext(io) {
                retryTransientRead { ArticleWire.detail(api.detail(id).readJson()).toDomain() }
            }
        }
        return if (policies.policy.value.optimized) details.run(id, fetch) else fetch()
    }

    override fun saved(): Flow<List<ArticleDetail>> =
        dao.observeSaved().map { it.map(SavedArticleRow::toDomain) }.flowOn(io)

    override suspend fun setSaved(article: ArticleDetail, saved: Boolean) {
        if (saved) dao.save(SavedArticleRow.from(article)) else dao.removeSaved(article.article.id)
    }

    internal suspend fun syncFirstBatch() {
        val policy = policies.policy.value
        if (!policy.allowNetwork) return
        val page = fetchPage("", null, policy.pageSize)
        dao.replaceBatch(page.items.map(FeedRow::from))
    }

    /** A single retry covers a broken reused connection or truncated GET body. */
    private suspend fun <T> retryTransientRead(read: suspend () -> T): T {
        try {
            return read()
        } catch (failure: IOException) {
            if (failure is NewsHttpException || failure is OfflineException ||
                !policies.policy.value.allowNetwork) throw failure
        }
        delay(100)
        return read()
    }

    private fun Response<ResponseBody>.readJson(): String {
        if (isSuccessful) {
            return body()?.use { it.string() } ?: throw IOException("Empty article response")
        }
        val message = errorBody()?.use { body ->
            runCatching { JSONObject(body.string()).getString("message") }.getOrNull()
        }
        throw NewsHttpException(code(), message ?: "HTTP ${code()}")
    }
}
