package com.adaptivenews.app.core.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class Variant { V1, V2, V3 }
enum class Mode { BASELINE, PERFORMANCE, BALANCED, ECO }
enum class MemoryPressure { NORMAL, MODERATE, CRITICAL, UNKNOWN }
enum class Thermal { NORMAL, MODERATE, SEVERE, UNKNOWN }
enum class LinkQuality { FAST, SLOW, UNKNOWN, OFFLINE }

data class ResourceSnapshot(
    val elapsedMs: Long, val memory: MemoryPressure,
    val batteryPct: Int?, val powerSave: Boolean?,
    val thermal: Thermal, val link: LinkQuality,
    val metered: Boolean?, val appCpuPct: Double? = null
)

data class Policy(
    val mode: Mode, val imageWidth: Int, val pageSize: Int,
    val memoryCacheBytes: Long, val prefetchCount: Int,
    val maxConcurrentRequests: Int, val animations: Boolean,
    val syncMinutes: Long, val allowNetwork: Boolean,
    val optimized: Boolean, val reason: String
)

data class ArticleSummary(
    val id: Long, val title: String, val summary: String,
    val category: String, val publishedAt: String, val imagePath: String
)
data class ArticleDetail(val article: ArticleSummary, val body: String)
data class ArticlePage(val items: List<ArticleSummary>, val nextCursor: String?)

interface ResourceMonitor { val snapshots: Flow<ResourceSnapshot> }
interface PolicyProvider { val policy: StateFlow<Policy> }
interface NewsRepository {
    suspend fun page(query: String, cursor: String?, limit: Int): ArticlePage
    suspend fun detail(id: Long): ArticleDetail
    fun saved(): Flow<List<ArticleDetail>>
    suspend fun setSaved(article: ArticleDetail, saved: Boolean)
}
