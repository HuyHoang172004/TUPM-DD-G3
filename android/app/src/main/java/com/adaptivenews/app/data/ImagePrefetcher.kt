package com.adaptivenews.app.data

import com.adaptivenews.app.core.contract.ArticleSummary
import com.adaptivenews.app.core.contract.PolicyProvider
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

class NewsImageUrls internal constructor(private val baseUrl: HttpUrl) {
    fun url(article: ArticleSummary, width: Int): HttpUrl {
        require(width == 360 || width == 720 || width == 1080)
        require(article.imagePath.startsWith("/v1/images/"))
        val path = requireNotNull(baseUrl.resolve(article.imagePath))
        return path.newBuilder().setQueryParameter("width", width.toString()).build()
    }
}

class ImagePrefetcher internal constructor(
    screenScope: CoroutineScope,
    policies: PolicyProvider,
    private val urls: NewsImageUrls,
    private val calls: Call.Factory,
    private val usage: PrefetchUsage,
) : AutoCloseable {
    private val upcoming = MutableStateFlow<List<ArticleSummary>>(emptyList())
    private val observer = screenScope.launch {
        val jobs = mutableMapOf<HttpUrl, Job>()
        val slot = Semaphore(1)
        val candidates = combine(upcoming, policies.policy) { articles, policy ->
            if (policy.optimized && policy.allowNetwork) {
                articles.asSequence().map { urls.url(it, policy.imageWidth) }
                    .distinct().take(policy.prefetchCount).toSet()
            } else emptySet()
        }.distinctUntilChanged()
        try {
            candidates.collect { desired ->
                (jobs.keys - desired).forEach { jobs.remove(it)?.cancel() }
                desired.forEach { url ->
                    jobs.getOrPut(url) { launch { slot.withPermit { download(url) } } }
                }
            }
        } finally {
            jobs.values.forEach(Job::cancel)
        }
    }

    /** Supply items after the viewport, in display order. Pass an empty list on screen stop. */
    fun update(next: List<ArticleSummary>) {
        upcoming.value = next.toList()
    }

    /** Drop pending work on cache reset without stopping observation of subsequent viewports. */
    fun clear() = update(emptyList())

    override fun close() = observer.cancel()

    private suspend fun download(url: HttpUrl) {
        val key = url.toString()
        if (!usage.reserve(key)) return
        suspendCancellableCoroutine<Unit> { waiting ->
            val request = Request.Builder().url(url)
                .tag(ImagePrefetchTag::class.java, ImagePrefetchTag)
                .build()
            val call = calls.newCall(request)
            waiting.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    usage.release(key)
                    waiting.resume(Unit)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.isSuccessful) {
                                val source = it.body?.source()
                                if (source != null) {
                                    val buffer = Buffer()
                                    while (source.read(buffer, 8192) != -1L) buffer.clear()
                                }
                            }
                        }
                    } catch (_: IOException) {
                        // The transport emits the outcome, including partial body bytes.
                    } finally {
                        usage.release(key)
                        waiting.resume(Unit)
                    }
                }
            })
        }
    }
}
