package com.adaptivenews.app.ui

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.adaptivenews.app.experiment.Experiment
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.adaptivenews.app.core.contract.ArticleSummary
import com.adaptivenews.app.core.contract.LinkQuality
import com.adaptivenews.app.core.contract.MemoryPressure
import com.adaptivenews.app.core.contract.Policy
import com.adaptivenews.app.core.contract.ResourceSnapshot
import com.adaptivenews.app.core.contract.Thermal
import com.adaptivenews.app.core.contract.Variant
import com.adaptivenews.app.data.NewsImageUrls
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AdaptiveNewsApp(
    viewModel: AppViewModel,
    onVariantSelected: (Variant) -> Unit,
    onExport: () -> Unit,
    onResetImageCache: () -> Unit,
    onResetAllData: () -> Unit
) {
    val screen by viewModel.screen.collectAsState()
    val policy by viewModel.controller.policy.collectAsState()
    val context = LocalContext.current
    val imageLoader = remember(policy.memoryCacheBytes, viewModel.newsData.imageCalls) {
        ImageLoaderHolder.get(context, policy.memoryCacheBytes, viewModel.newsData.imageCalls)
    }
    val topLevel = listOf(AppScreen.FEED, AppScreen.SEARCH, AppScreen.SAVED, AppScreen.LAB)

    MaterialTheme {
        Scaffold(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            topBar = {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Adaptive News", style = MaterialTheme.typography.headlineSmall)
                    Text("${viewModel.variant} · ${policy.mode} · Backend",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            bottomBar = {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav_${item.name.lowercase()}"),
                            selected = screen == item,
                            onClick = { viewModel.navigate(item) },
                            icon = { Text(when (item) {
                                AppScreen.FEED -> "▤"
                                AppScreen.SEARCH -> "⌕"
                                AppScreen.SAVED -> "★"
                                AppScreen.LAB -> "⚙"
                                else -> ""
                            }) },
                            label = { Text(item.label()) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    AppScreen.FEED, AppScreen.SEARCH -> FeedScreen(viewModel, screen == AppScreen.SEARCH, policy, imageLoader)
                    AppScreen.DETAIL -> DetailScreen(viewModel, policy, imageLoader)
                    AppScreen.SAVED -> SavedScreen(viewModel, policy, imageLoader)
                    AppScreen.LAB -> LabScreen(viewModel, policy, onVariantSelected, onExport, onResetImageCache, onResetAllData)
                }
            }
        }
    }
}

private fun AppScreen.label() = when (this) {
    AppScreen.FEED -> "Tin"
    AppScreen.SEARCH -> "Tìm"
    AppScreen.SAVED -> "Đã lưu"
    AppScreen.LAB -> "Lab"
    AppScreen.DETAIL -> "Chi tiết"
}

@Composable
private fun FeedScreen(viewModel: AppViewModel, search: Boolean, policy: Policy, imageLoader: coil3.ImageLoader) {
    val articles by viewModel.items.collectAsState()
    val query by viewModel.query.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val listState = remember(search) { LazyListState() }
    LaunchedEffect(query, search) { listState.scrollToItem(0) }
    Column {
        if (search) OutlinedTextField(
            value = query, onValueChange = viewModel::setQuery,
            label = { Text("Tìm theo tiêu đề") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("search_input")
        )
        if (viewModel.useEager) {
            LaunchedEffect(articles.isNotEmpty()) {
                if (articles.isNotEmpty()) viewModel.onEagerPresented()
            }
            LaunchedEffect(listState.firstVisibleItemIndex, articles.size, loading) {
                if (!loading && articles.isNotEmpty() && listState.firstVisibleItemIndex >= articles.size - 5) viewModel.loadMore()
            }
            if (error != null) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                OutlinedButton(onClick = { viewModel.refresh() }, modifier = Modifier.padding(12.dp)) {
                    Text("Thử lại")
                }
            }
            if (!loading && articles.isEmpty() && error == null) Text("Không có bài viết", modifier = Modifier.padding(16.dp))
            LazyColumn(state = listState,
                modifier = Modifier.fillMaxSize().testTag(if (search) "search_results" else "feed_list")) {
                items(articles,
                    key = if (Experiment.bool("stable_keys", false)) ({ article: ArticleSummary -> article.id }) else null,
                    contentType = { if (Experiment.bool("stable_keys", false)) "article" else null }) { article ->
                    ArticleCard(article, policy, imageLoader, viewModel.newsData.imageUrls,
                        onImageVisible = { elapsed, cacheSource ->
                            viewModel.imageDisplayed(article, policy.imageWidth)
                            viewModel.telemetry.record("image_visible", mode = policy.mode.name,
                                value = elapsed, unit = "ms",
                                attrs = mapOf("article_id" to article.id, "cache_source" to cacheSource))
                        }, onClick = { viewModel.openArticle(article.id) })
                }
                if (loading) item(contentType = "loading") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        } else {
            val paging = viewModel.pagedItems.collectAsLazyPagingItems()
            val refresh = paging.loadState.refresh
            var sawRefreshLoading by remember(search) { mutableStateOf(false) }
            LaunchedEffect(refresh, paging.itemCount) {
                if (refresh is LoadState.Loading) sawRefreshLoading = true
                if (refresh is LoadState.NotLoading) {
                    if (paging.itemCount > 0) viewModel.onFeedPresented()
                    if (sawRefreshLoading) {
                        viewModel.onPagedReady()
                        sawRefreshLoading = false
                    }
                }
            }
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            LaunchedEffect(search, lastVisible, paging.itemCount, policy.imageWidth, policy.prefetchCount) {
                val upcoming = if (lastVisible < 0) emptyList() else
                    ((lastVisible + 1) until minOf(paging.itemCount, lastVisible + 1 + policy.prefetchCount))
                        .mapNotNull(paging::peek)
                viewModel.updatePrefetch(upcoming)
            }
            if (refresh is LoadState.Error) {
                Text(refresh.error.message ?: "Không thể tải bài viết",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                OutlinedButton(onClick = paging::retry, modifier = Modifier.padding(12.dp)) {
                    Text("Thử lại")
                }
            }
            if (refresh is LoadState.NotLoading && paging.itemCount == 0)
                Text("Không có bài viết", modifier = Modifier.padding(16.dp))
            LazyColumn(state = listState,
                modifier = Modifier.fillMaxSize().testTag(if (search) "search_results" else "feed_list")) {
                items(count = paging.itemCount, key = if (Experiment.bool("stable_keys", true)) paging.itemKey { it.id } else null,
                    contentType = { if (Experiment.bool("stable_keys", true)) "article" else null }) { index ->
                    paging[index]?.let { article ->
                        ArticleCard(article, policy, imageLoader, viewModel.newsData.imageUrls,
                            onImageVisible = { elapsed, cacheSource ->
                                viewModel.imageDisplayed(article, policy.imageWidth)
                                viewModel.telemetry.record("image_visible", mode = policy.mode.name,
                                    value = elapsed, unit = "ms",
                                    attrs = mapOf("article_id" to article.id, "cache_source" to cacheSource))
                            }, onClick = { viewModel.openArticle(article.id) })
                    }
                }
                if (paging.loadState.append is LoadState.Loading || refresh is LoadState.Loading)
                    item(contentType = "loading") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                if (paging.loadState.append is LoadState.Error) item(contentType = "retry") {
                    OutlinedButton(onClick = paging::retry) { Text("Thử tải tiếp") }
                }
            }
        }
    }
}

@Composable
private fun ArticleCard(
    article: ArticleSummary, policy: Policy, imageLoader: coil3.ImageLoader,
    imageUrls: NewsImageUrls, onImageVisible: (Long, String) -> Unit, onClick: () -> Unit
) {
    if (Experiment.active) SideEffect {
        com.adaptivenews.app.experiment.ExperimentRuntime.compositions.incrementAndGet()
    }
    val animationModifier = if (policy.animations) Modifier.animateContentSize() else Modifier
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .then(animationModifier).testTag("article_${article.id}").clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ArticleImage(article, policy, imageLoader, imageUrls, Modifier.size(88.dp), onImageVisible)
            Column {
                Text(article.title, style = MaterialTheme.typography.titleMedium)
                Text(article.category, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Text(article.summary, maxLines = 2, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ArticleImage(
    article: ArticleSummary, policy: Policy, imageLoader: coil3.ImageLoader,
    imageUrls: NewsImageUrls, modifier: Modifier, onImageVisible: (Long, String) -> Unit
) {
    val context = LocalContext.current
    val boundAt = remember(article.id, policy.imageWidth) { SystemClock.elapsedRealtime() }
    var logged by remember(article.id, policy.imageWidth) { mutableStateOf(false) }
    val url = imageUrls.url(article, policy.imageWidth).toString()
    val makeRequest = {
        ImageRequest.Builder(context).data(url).crossfade(policy.animations)
            .apply {
                if (Experiment.isolated) {
                    diskCachePolicy(CachePolicy.DISABLED)
                    memoryCachePolicy(if (Experiment.bool("memory_cache", false)) CachePolicy.ENABLED else CachePolicy.DISABLED)
                    if (Experiment.bool("decode_original", false)) size(Size.ORIGINAL)
                }
            }
            .networkCachePolicy(if (policy.allowNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED).build()
    }
    val request = if (Experiment.bool("memoize_requests", true))
        remember(url, policy.allowNetwork, policy.animations) { makeRequest() } else makeRequest()
    AsyncImage(
        model = request, imageLoader = imageLoader, contentDescription = article.title,
        contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(8.dp)),
        onSuccess = { state ->
            if (!logged) {
                logged = true
                onImageVisible(SystemClock.elapsedRealtime() - boundAt, state.result.dataSource.name)
            }
        }
    )
}

@Composable
private fun DetailScreen(viewModel: AppViewModel, policy: Policy, imageLoader: coil3.ImageLoader) {
    val detail by viewModel.detail.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val error by viewModel.error.collectAsState()
    if (detail == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Column {
                Text(error ?: "Không thể mở bài viết", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = viewModel::retryDetail) { Text("Thử lại") }
                OutlinedButton(onClick = { viewModel.navigate(AppScreen.FEED) }) { Text("Về danh sách") }
            }
        }
        return
    }
    val article = requireNotNull(detail)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(article.article.title, style = MaterialTheme.typography.headlineMedium)
                Text(article.article.category, style = MaterialTheme.typography.labelMedium)
                ArticleImage(article.article, policy, imageLoader, viewModel.newsData.imageUrls,
                    Modifier.fillMaxWidth().height(200.dp), onImageVisible = { _, _ -> })
                Button(onClick = viewModel::toggleSave, modifier = Modifier.testTag("save_button")) {
                    Text(if (saved.any { it.article.id == article.article.id }) "Bỏ lưu" else "Lưu bài")
                }
                Text(article.body, modifier = Modifier.testTag("detail_body"))
            }
        }
    }
}

@Composable
private fun SavedScreen(viewModel: AppViewModel, policy: Policy, imageLoader: coil3.ImageLoader) {
    val saved by viewModel.saved.collectAsState()
    if (saved.isEmpty()) { Text("Chưa có bài đã lưu", Modifier.padding(16.dp).testTag("saved_list")); return }
    LazyColumn(Modifier.fillMaxSize().testTag("saved_list")) {
        items(saved, key = { it.article.id }, contentType = { "saved_article" }) { detail ->
            ArticleCard(detail.article, policy, imageLoader, viewModel.newsData.imageUrls,
                onImageVisible = { _, _ -> }, onClick = { viewModel.openArticle(detail.article.id) })
        }
    }
}

@Composable
private fun LabScreen(
    viewModel: AppViewModel, policy: Policy,
    onVariantSelected: (Variant) -> Unit, onExport: () -> Unit,
    onResetImageCache: () -> Unit, onResetAllData: () -> Unit
) {
    var displayedPolicy by remember { mutableStateOf(policy) }
    var displayedSnapshot by remember { mutableStateOf(viewModel.controller.snapshot.value) }
    var displayedSource by remember { mutableStateOf(viewModel.controller.source.value) }
    LaunchedEffect(viewModel.controller) {
        while (true) {
            displayedPolicy = viewModel.controller.policy.value
            displayedSnapshot = viewModel.controller.snapshot.value
            displayedSource = viewModel.controller.source.value
            delay(5_000L)
        }
    }
    val current = displayedSnapshot
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("lab_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Thiết lập thí nghiệm", style = MaterialTheme.typography.titleLarge)
        if (Experiment.active) Text("Workload: ${Experiment.config.optString("method_id")} · nhánh ${Experiment.config.optString("arm")}")
        Text("Phiên bản: ${viewModel.variant}")
        Row(Modifier.fillMaxWidth().testTag("variant_selector"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Variant.entries.forEach { v ->
                OutlinedButton(onClick = { onVariantSelected(v) }, enabled = v != viewModel.variant) { Text(v.name) }
            }
        }
        Text("Chế độ: ${displayedPolicy.mode} · ${displayedPolicy.reason}", modifier = Modifier.testTag("mode_label"))
        Text("Nguồn: $displayedSource · RAM: ${current?.memory ?: "—"} · Pin: ${current?.batteryPct ?: "—"}%")
        Text("Nhiệt: ${current?.thermal ?: "—"} · Mạng: ${current?.link ?: "—"} · Metered: ${current?.metered ?: "—"}")
        Text("Ảnh ${displayedPolicy.imageWidth}px · Trang ${displayedPolicy.pageSize} · Cache ${displayedPolicy.memoryCacheBytes / (1024 * 1024)} MiB")
        Text("Prefetch ${displayedPolicy.prefetchCount} · Request ${displayedPolicy.maxConcurrentRequests} · Hiệu ứng ${displayedPolicy.animations}")
        Text("Đồng bộ ${displayedPolicy.syncMinutes} phút · Mạng ${displayedPolicy.allowNetwork}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                viewModel.controller.inject(simulatedSnapshot(MemoryPressure.NORMAL, LinkQuality.FAST))
            }) { Text("Normal") }
            OutlinedButton(onClick = {
                viewModel.controller.inject(simulatedSnapshot(MemoryPressure.CRITICAL, LinkQuality.FAST))
            }) { Text("Critical") }
            OutlinedButton(onClick = {
                viewModel.controller.inject(simulatedSnapshot(MemoryPressure.NORMAL, LinkQuality.SLOW))
            }) { Text("Slow") }
        }
        OutlinedButton(onClick = viewModel.controller::clearInjection) { Text("Dùng trạng thái thật") }
        Button(onClick = onExport, modifier = Modifier.testTag("export_button")) { Text("Xuất log JSONL") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onResetImageCache) { Text("Reset cache ảnh") }
            OutlinedButton(onClick = onResetAllData) { Text("Reset dữ liệu") }
        }
    }
}

private fun simulatedSnapshot(memory: MemoryPressure, link: LinkQuality) = ResourceSnapshot(
    elapsedMs = SystemClock.elapsedRealtime(), memory = memory, batteryPct = 80,
    powerSave = false, thermal = Thermal.NORMAL, link = link, metered = false
)
