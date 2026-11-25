package com.adaptivenews.app.data

import android.content.Context
import androidx.room.Room
import com.adaptivenews.app.core.contract.PolicyProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/** One instance per app process; share imageCalls with Coil's network fetcher. */
class NewsData(
    context: Context,
    baseUrl: HttpUrl,
    private val policies: PolicyProvider,
    telemetry: DataTelemetry,
    onArticleNetworkSuccess: (Long) -> Unit = {},
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val usage = PrefetchUsage(telemetry)
    private val transport = NewsTransport(policies, telemetry, usage, onArticleNetworkSuccess)
    private val httpCache = Cache(app.cacheDir.resolve("news-http"), 64L * 1024 * 1024)
    private val client = OkHttpClient.Builder()
        .cache(httpCache)
        .dispatcher(Dispatcher().apply {
            // In optimized variants the shared gate controls the limit, including 6 in Performance.
            if (policies.policy.value.optimized) maxRequestsPerHost = maxRequests
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .addInterceptor(transport.application)
        .addNetworkInterceptor(transport.network)
        .build()

    private val database = Room.databaseBuilder(app, NewsDatabase::class.java, "news.db").build()
    private val api = Retrofit.Builder().baseUrl(baseUrl).callFactory(client).build()
        .create(NewsApi::class.java)

    val repository = RemoteNewsRepository(api, database.articles(), policies, scope)
    val feed = ArticleFeed(repository, policies)
    val imageUrls = NewsImageUrls(baseUrl)
    val imageCalls: Call.Factory get() = client
    val syncWorkerFactory = NewsSyncWorkerFactory(repository)

    fun prefetcher(screenScope: CoroutineScope) =
        ImagePrefetcher(screenScope, policies, imageUrls, client, usage)

    /** Call when an image with this exact URL first becomes ready on a visible item. */
    fun imageDisplayed(url: HttpUrl) = usage.displayed(url.toString())

    fun startSync(schedulerScope: CoroutineScope) = scheduleNewsSync(app, policies, schedulerScope)

    /** Call after cancelling viewport prefetch and quiescing image requests at the reset boundary. */
    suspend fun clearHttpCache() = withContext(Dispatchers.IO) {
        httpCache.evictAll()
        usage.reset()
    }

    fun resetPrefetchUsage() = usage.reset()

    /** For tests or explicit process-graph teardown, after UI and workers have been stopped. */
    suspend fun close() = withContext(Dispatchers.IO) {
        scope.cancel()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        httpCache.close()
        database.close()
    }
}
