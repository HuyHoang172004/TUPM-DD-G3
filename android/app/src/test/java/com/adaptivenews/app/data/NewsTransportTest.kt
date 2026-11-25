package com.adaptivenews.app.data

import com.adaptivenews.app.core.contract.ArticleSummary
import com.adaptivenews.app.core.contract.Mode
import com.adaptivenews.app.core.contract.Policy
import com.adaptivenews.app.core.contract.PolicyProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NewsTransportTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun networkCacheHitAndRevalidationAccountForActualBodyBytes() = withServer {
        repeat(2) { assertEquals(200, get("v1/images/1?width=720")) }
        assertEquals(1, serverRequests.get())

        assertEquals(200, get("v1/images/1?width=720", revalidate = true))
        assertEquals(2, serverRequests.get())
        assertEquals(
            listOf("network" to 8192L, "cache" to 0L, "conditional_cache" to 0L),
            events.filter { it.name == "request_end" }.map {
                it.attrs["cache_source"] to it.attrs["bytes_body"]
            },
        )

        state.value = state.value.copy(allowNetwork = false)
        assertEquals(200, get("v1/images/1?width=720"))
        assertEquals(504, get("v1/images/2?width=720"))
        assertEquals(2, serverRequests.get())
    }

    @Test
    fun duplicateViewportEntriesWarmOneImageAndReuseIsReportedOnDisplay() = withServer {
        runBlocking {
            ImagePrefetcher(this, policies, urls, client, usage).use { prefetch ->
                val article = ArticleSummary(
                    1, "Fixture", "Summary", "Technology", "2026-01-01T00:00:00Z",
                    "/v1/images/1",
                )
                prefetch.update(listOf(article, article))
                withTimeout(3_000) {
                    while (events.none { it.name == "prefetch_end" }) delay(20)
                }
                val end = events.single { it.name == "prefetch_end" }
                assertEquals(8192L, end.value)
                assertEquals(true, end.attrs["success"])

                assertEquals(200, get("v1/images/1?width=720"))
                assertEquals(1, serverRequests.get())
                assertTrue(events.none { it.name == "prefetch_used" })
                repeat(2) { usage.displayed(urls.url(article, 720).toString()) }
                assertEquals(1, events.count { it.name == "prefetch_used" })
            }
        }
    }

    @Test
    fun concurrentWaitersShareOneResultAndLastCancellationStopsProducer() = runBlocking {
        val shared = SharedRequests<String, String>(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            shared.run("page") {
                started.complete(Unit)
                release.await()
                "page payload"
            }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            shared.run("page") { error("Duplicate producer") }
        }
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals("page payload", second.await())

        val producerStarted = CompletableDeferred<Unit>()
        val producerCancelled = CompletableDeferred<Unit>()
        val onlyWaiter = async {
            shared.run("detail") {
                producerStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    producerCancelled.complete(Unit)
                }
            }
        }
        producerStarted.await()
        onlyWaiter.cancelAndJoin()
        withTimeout(1_000) { producerCancelled.await() }
    }

    private fun withServer(action: Harness.() -> Unit) {
        Harness(Cache(temporary.newFolder(), 1024L * 1024)).use(action)
    }

    private data class Event(val name: String, val value: Long?, val attrs: Map<String, Any?>)

    private class Harness(cache: Cache) : AutoCloseable {
        val state = MutableStateFlow(
            Policy(Mode.BALANCED, 720, 20, 32L * 1024 * 1024, 4, 4,
                false, 30, true, true, "fixture"),
        )
        val policies = object : PolicyProvider { override val policy = state }
        val serverRequests = AtomicInteger()
        val events = CopyOnWriteArrayList<Event>()
        private val payload = ByteArray(8192) { it.toByte() }
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    serverRequests.incrementAndGet()
                    val response = MockResponse()
                        .setHeader("ETag", "\"fixture\"")
                        .setHeader("Cache-Control", "public, max-age=3600")
                    return if (request.getHeader("If-None-Match") == "\"fixture\"") {
                        response.setResponseCode(304)
                    } else {
                        response.setBody(Buffer().write(payload))
                    }
                }
            }
            start()
        }
        private val base = server.url("/")
        val urls = NewsImageUrls(base)
        private val telemetry = DataTelemetry { event, value, _, attrs ->
            events.add(Event(event, value, attrs))
        }
        val usage = PrefetchUsage(telemetry)
        private val transport = NewsTransport(policies, telemetry, usage, {}) {
            System.nanoTime() / 1_000_000
        }
        val client = OkHttpClient.Builder().cache(cache)
            .addInterceptor(transport.application)
            .addNetworkInterceptor(transport.network)
            .build()

        fun get(path: String, revalidate: Boolean = false): Int {
            val request = Request.Builder().url(requireNotNull(base.resolve(path)))
            if (revalidate) request.cacheControl(CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build())
            return client.newCall(request.build()).execute().use { response ->
                if (response.isSuccessful) {
                    assertTrue(response.body!!.bytes().contentEquals(payload))
                }
                response.code
            }
        }

        override fun close() {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            client.cache?.close()
            server.shutdown()
        }
    }
}
