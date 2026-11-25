package com.adaptivenews.app.ui.fixture

import android.content.Context
import com.adaptivenews.app.core.contract.ArticleDetail
import com.adaptivenews.app.core.contract.ArticlePage
import com.adaptivenews.app.core.contract.ArticleSummary
import com.adaptivenews.app.core.contract.NewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thirty deterministic UI fixtures. This does not stand in for benchmark data. */
class FakeNewsRepository(context: Context) : NewsRepository {
    private val prefs = context.applicationContext.getSharedPreferences("fixture_saved", Context.MODE_PRIVATE)
    private val categories = listOf("Technology", "Science", "Health", "Business", "Culture")
    private val details = (1L..30L).associateWith { id ->
        val category = categories[((id - 1) % categories.size).toInt()]
        val article = ArticleSummary(
            id, "Tin ${category.lowercase()} ${id.toString().padStart(5, '0')}",
            "Tóm tắt cố định cho bài viết $id.", category,
            "2026-01-01T00:00:00Z", "/v1/images/$id"
        )
        ArticleDetail(article, "Nội dung minh họa cho bài viết $id.\n\nĐây là fixture giao diện, không phải nội dung thí nghiệm.")
    }
    private val mutableSaved = MutableStateFlow(readSaved())
    override fun saved(): StateFlow<List<ArticleDetail>> = mutableSaved

    override suspend fun page(query: String, cursor: String?, limit: Int): ArticlePage {
        require(limit in 1..100)
        val q = query.trim().lowercase()
        if (q == "__error__") error("Fixture error: lỗi tải danh sách")
        val filtered = details.values.map { it.article }.filter { it.title.lowercase().contains(q) }.sortedBy { it.id }
        val start = cursor?.let {
            val parts = it.split(':')
            require(parts.size == 3 && parts[0] == "fixture" && parts[1] == q.hashCode().toString())
            parts[2].toInt()
        } ?: 0
        require(start in 0..filtered.size)
        val items = filtered.drop(start).take(limit)
        val next = start + items.size
        return ArticlePage(items, if (next < filtered.size) "fixture:${q.hashCode()}:$next" else null)
    }

    override suspend fun detail(id: Long): ArticleDetail = details[id] ?: throw NoSuchElementException("Article $id")

    override suspend fun setSaved(article: ArticleDetail, saved: Boolean) {
        val ids = prefs.getStringSet("ids", emptySet()).orEmpty().toMutableSet()
        if (saved) ids.add(article.article.id.toString()) else ids.remove(article.article.id.toString())
        prefs.edit().putStringSet("ids", ids).apply()
        mutableSaved.value = readSaved()
    }

    fun reset() {
        prefs.edit().clear().apply()
        mutableSaved.value = emptyList()
    }

    private fun readSaved() = prefs.getStringSet("ids", emptySet()).orEmpty()
        .mapNotNull { it.toLongOrNull()?.let(details::get) }.sortedBy { it.article.id }
}
