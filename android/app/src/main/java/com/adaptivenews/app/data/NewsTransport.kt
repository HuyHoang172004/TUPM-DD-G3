package com.adaptivenews.app.data

import android.os.SystemClock
import com.adaptivenews.app.core.contract.PolicyProvider
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal object ImagePrefetchTag

internal class NewsTransport(
    private val policies: PolicyProvider,
    private val telemetry: DataTelemetry,
    private val usage: PrefetchUsage,
    private val onArticleNetworkSuccess: (Long) -> Unit,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Trace(val start: Long) {
        val bytes = AtomicLong(0)
        val ended = AtomicBoolean(false)
        var cacheSource = "none"
        var responseCode: Int? = null
        var dispatchedAt = start
    }

    private val gate = RequestGate(policies)

    val application = Interceptor { chain ->
        val original = chain.request()
        val prefetch = original.tag(ImagePrefetchTag::class.java) != null
        val path = original.url.encodedPath
        val kind = when {
            path == "/v1/articles" -> "article_page"
            path.startsWith("/v1/articles/") -> "article_detail"
            path.startsWith("/v1/images/") -> "image"
            else -> "other"
        }
        val trace = Trace(monotonicMillis())
        val requestId = UUID.randomUUID().toString()
        val call = chain.call()
        var acquired = false

        fun finish(complete: Boolean, failed: Boolean) {
            if (!trace.ended.compareAndSet(false, true)) return
            val elapsed = monotonicMillis() - trace.start
            val bytes = trace.bytes.get()
            val status = when {
                call.isCanceled() -> "cancelled"
                failed -> "io_error"
                else -> trace.responseCode
            }
            val success = complete && status is Int && status in 200..299
            try {
                telemetry.emit(
                    "request_end", elapsed, "ms", mapOf(
                        "request_id" to requestId,
                        "kind" to kind,
                        "status" to status,
                        "bytes_body" to bytes,
                        "cache_source" to trace.cacheSource,
                        "prefetch" to prefetch,
                    ),
                )
                if (prefetch) {
                    usage.finished(original.url.toString(), success, bytes)
                } else if (kind == "image" && success &&
                    (trace.cacheSource == "cache" || trace.cacheSource == "conditional_cache")
                ) {
                    usage.cacheHit(original.url.toString())
                }
                if (success && kind.startsWith("article_") &&
                    (trace.cacheSource == "network" || trace.cacheSource == "conditional_cache")
                ) {
                    onArticleNetworkSuccess(trace.start + elapsed - trace.dispatchedAt)
                }
            } finally {
                if (acquired) gate.release(call)
            }
        }

        try {
            gate.acquire(call, prefetch)
            acquired = true
            val policy = policies.policy.value
            val builder = original.newBuilder().tag(Trace::class.java, trace)
            when {
                !policy.allowNetwork -> builder.cacheControl(CacheControl.FORCE_CACHE)
                !policy.optimized -> builder.cacheControl(uncached)
            }
            trace.dispatchedAt = monotonicMillis()
            val response = chain.proceed(builder.build())
            trace.responseCode = response.code
            trace.cacheSource = when {
                response.cacheResponse != null && response.networkResponse?.code == 304 ->
                    "conditional_cache"
                response.networkResponse != null -> "network"
                response.cacheResponse != null -> "cache"
                else -> "none"
            }
            val body = response.body
            if (body == null) {
                finish(complete = true, failed = false)
                response
            } else {
                var eof = false
                var consumed = 0L
                val expected = body.contentLength()
                response.newBuilder().body(body.wrap { source ->
                    object : ForwardingSource(source) {
                        override fun read(sink: Buffer, byteCount: Long): Long = try {
                            super.read(sink, byteCount).also {
                                if (it == -1L) {
                                    eof = true
                                    finish(complete = true, failed = false)
                                } else consumed += it
                            }
                        } catch (error: IOException) {
                            finish(complete = false, failed = true)
                            throw error
                        }

                        override fun close() {
                            try {
                                super.close()
                                finish(complete = eof || expected == consumed, failed = false)
                            } catch (error: IOException) {
                                finish(complete = false, failed = true)
                                throw error
                            }
                        }
                    }
                }).build()
            }
        } catch (error: Exception) {
            finish(complete = false, failed = true)
            throw error
        }
    }

    val network = Interceptor { chain ->
        if (!policies.policy.value.allowNetwork) throw OfflineException()
        val trace = chain.request().tag(Trace::class.java)
        val response = chain.proceed(chain.request())
        val body = response.body
        if (trace == null || body == null) response else response.newBuilder()
            .body(body.wrap { source ->
                object : ForwardingSource(source) {
                    override fun read(sink: Buffer, byteCount: Long): Long =
                        super.read(sink, byteCount).also { if (it > 0) trace.bytes.addAndGet(it) }
                }
            }).build()
    }

    private fun ResponseBody.wrap(transform: (okio.BufferedSource) -> ForwardingSource): ResponseBody {
        val original = this
        val wrapped = transform(original.source()).buffer()
        return object : ResponseBody() {
            override fun contentType() = original.contentType()
            override fun contentLength() = original.contentLength()
            override fun source() = wrapped
        }
    }

    private companion object {
        val uncached = CacheControl.Builder().noCache().noStore().build()
    }
}
