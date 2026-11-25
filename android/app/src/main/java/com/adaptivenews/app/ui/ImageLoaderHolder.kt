package com.adaptivenews.app.ui

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** One active loader. A cache-budget change replaces it only after the engine changes mode. */
object ImageLoaderHolder {
    private var loader: ImageLoader? = null
    private var budget: Long? = null
    private val dispatcher = Dispatcher()
    /** Hoàn can share this client so image and article requests use one concurrency pool. */
    val sharedClient: OkHttpClient = OkHttpClient.Builder().dispatcher(dispatcher).build()

    @Synchronized
    fun applyRequestLimit(maxConcurrentRequests: Int) {
        val effective = if (maxConcurrentRequests <= 0) 64 else maxConcurrentRequests
        if (dispatcher.maxRequests != effective) dispatcher.maxRequests = effective
        if (dispatcher.maxRequestsPerHost != effective) dispatcher.maxRequestsPerHost = effective
    }

    @Synchronized
    fun get(context: Context, memoryCacheBytes: Long): ImageLoader {
        if (loader == null || budget != memoryCacheBytes) {
            loader?.shutdown()
            val builder = ImageLoader.Builder(context.applicationContext)
            if (memoryCacheBytes > 0) {
                builder.memoryCache { MemoryCache.Builder().maxSizeBytes(memoryCacheBytes).build() }
            }
            builder.components { add(OkHttpNetworkFetcherFactory(callFactory = { sharedClient })) }
            loader = builder.build()
            budget = memoryCacheBytes
        }
        return requireNotNull(loader)
    }

    @Synchronized
    fun clear() {
        loader?.memoryCache?.clear()
        loader?.diskCache?.clear()
    }
}
