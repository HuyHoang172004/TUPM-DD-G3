package com.adaptivenews.app.data

import android.content.Context
import com.adaptivenews.app.core.contract.ArticleDetail
import com.adaptivenews.app.core.contract.ArticlePage
import com.adaptivenews.app.core.contract.NewsRepository
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class FakeNewsRepository(
    articles: List<ArticleDetail>,
    private val failRequests: Boolean = false,
) : NewsRepository {
    private data class Position(val query: String, val lastId: Long)

    private val ordered = articles.sortedBy { it.article.id }
    private val saved = MutableStateFlow<Map<Long, ArticleDetail>>(emptyMap())
    private val cursors = mutableMapOf<String, Position>()

    override suspend fun page(query: String, cursor: String?, limit: Int): ArticlePage {
        if (failRequests) throw IOException("Fixture request failed")
        require(limit in 1..100)
        val normalized = query.trim().lowercase(Locale.ROOT)
        val after = if (cursor == null) 0L else synchronized(cursors) {
            val position = cursors[cursor]
            require(position != null && position.query == normalized) { "Invalid fixture cursor" }
            position.lastId
        }
        val result = ordered.asSequence().map { it.article }
            .filter { it.id > after && it.title.lowercase(Locale.ROOT).contains(normalized) }
            .take(limit + 1).toList()
        val items = result.take(limit)
        val next = if (result.size > limit) synchronized(cursors) {
            val position = Position(normalized, items.last().id)
            val token = "${position.lastId}:${position.query}"
            cursors[token] = position
            token
        } else null
        return ArticlePage(items, next)
    }

    override suspend fun detail(id: Long): ArticleDetail {
        saved.value[id]?.let { return it }
        if (failRequests) throw IOException("Fixture request failed")
        return ordered.firstOrNull { it.article.id == id }
            ?: throw NewsHttpException(404, "Fixture article not found")
    }

    override fun saved(): Flow<List<ArticleDetail>> = saved.map { articles ->
        articles.values.sortedWith(
            compareByDescending<ArticleDetail> { it.article.publishedAt }
                .thenByDescending { it.article.id },
        )
    }

    override suspend fun setSaved(article: ArticleDetail, saved: Boolean) {
        this.saved.update { current ->
            if (saved) current + (article.article.id to article) else current - article.article.id
        }
    }

    companion object {
        suspend fun fromAssets(context: Context): FakeNewsRepository = withContext(Dispatchers.IO) {
            val details = context.assets.open("news_fixture/details.json").bufferedReader().use {
                it.readText()
            }
            fromFixture(details)
        }

        suspend fun fromFixture(detailsJson: String): FakeNewsRepository =
            withContext(Dispatchers.Default) {
                FakeNewsRepository(ArticleWire.details(detailsJson).map(ArticleDetailDto::toDomain))
            }
    }
}
