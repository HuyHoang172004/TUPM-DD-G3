package com.adaptivenews.app.data

import com.adaptivenews.app.core.contract.ArticleDetail
import com.adaptivenews.app.core.contract.ArticlePage
import com.adaptivenews.app.core.contract.ArticleSummary
import org.json.JSONArray
import org.json.JSONObject

internal data class ArticleSummaryDto(
    val id: Long,
    val title: String,
    val summary: String,
    val category: String,
    val published_at: String,
    val image_path: String,
) {
    fun toDomain() = ArticleSummary(id, title, summary, category, published_at, image_path)
}

internal data class ArticleDetailDto(val article: ArticleSummaryDto, val body: String) {
    fun toDomain() = ArticleDetail(article.toDomain(), body)
}

internal data class ArticlePageDto(val items: List<ArticleSummaryDto>, val next_cursor: String?) {
    fun toDomain() = ArticlePage(items.map(ArticleSummaryDto::toDomain), next_cursor)
}

internal object ArticleWire {
    fun page(json: String): ArticlePageDto {
        val root = JSONObject(json)
        val items = root.getJSONArray("items")
        val cursor = if (root.get("next_cursor") == JSONObject.NULL) null
        else root.getString("next_cursor")
        return ArticlePageDto(List(items.length()) { summary(items.getJSONObject(it)) }, cursor)
    }

    fun detail(json: String): ArticleDetailDto = detail(JSONObject(json))

    fun details(json: String): List<ArticleDetailDto> {
        val items = JSONArray(json)
        return List(items.length()) { detail(items.getJSONObject(it)) }
    }

    private fun detail(value: JSONObject) = ArticleDetailDto(summary(value), value.getString("body"))

    private fun summary(value: JSONObject) = ArticleSummaryDto(
        id = value.getLong("id"),
        title = value.getString("title"),
        summary = value.getString("summary"),
        category = value.getString("category"),
        published_at = value.getString("published_at"),
        image_path = value.getString("image_path"),
    )
}
