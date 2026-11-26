package com.adaptivenews.app.ui

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Call

/** One active loader. A cache-budget change replaces it only after the engine changes mode. */
object ImageLoaderHolder {
    private var loader: ImageLoader? = null
    private var budget: Long? = null
    private var calls: Call.Factory? = null

    @Synchronized
    fun get(context: Context, memoryCacheBytes: Long, imageCalls: Call.Factory): ImageLoader {
        if (loader == null || budget != memoryCacheBytes || calls !== imageCalls) {
            loader?.shutdown()
            val builder = ImageLoader.Builder(context.applicationContext)
            if (memoryCacheBytes > 0) {
                builder.memoryCache { MemoryCache.Builder().maxSizeBytes(memoryCacheBytes).build() }
            }
            builder.components { add(OkHttpNetworkFetcherFactory(callFactory = { imageCalls })) }
            loader = builder.build()
            budget = memoryCacheBytes
            calls = imageCalls
        }
        return requireNotNull(loader)
    }

    @Synchronized
    fun clear() {
        loader?.memoryCache?.clear()
        loader?.diskCache?.clear()
    }

    @Synchronized
    fun reset() {
        clear()
        loader?.shutdown()
        loader = null
        budget = null
        calls = null
    }
}
