package com.adaptivenews.app.experiment

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Base64
import com.adaptivenews.app.core.contract.*
import com.adaptivenews.app.ui.RepositoryBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

/** Shell-only bridge, entirely absent from the release manifest. */
class BenchmarkProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun authorize() {
        check(Binder.getCallingUid() == 2000 || Binder.getCallingUid() == Process.myUid()) { "Shell-only benchmark bridge" }
    }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize()
        val app = requireNotNull(context).applicationContext
        return try {
            when (method) {
                "ping" -> Bundle().apply { putInt("protocol", 1) }
                "configure" -> {
                    val text = String(Base64.decode(requireNotNull(arg), Base64.DEFAULT), Charsets.UTF_8)
                    require(text.length < 16_384)
                    val json = JSONObject(text)
                    require(json.getString("trial_id").matches(Regex("[A-Za-z0-9_-]{1,64}")))
                    require(json.getString("base_url") == "http://10.0.2.2:8001/")
                    require(json.getString("variant") in listOf("V1", "V2", "V3"))
                    app.getSharedPreferences("experiment", 0).edit().putString("config", text).commit()
                    Experiment.load(app)
                    ok()
                }
                "reset" -> {
                    // Runner force-stops the app first; no active UI/calls survive this boundary.
                    app.cacheDir.resolve("news-http").deleteRecursively()
                    app.cacheDir.resolve("image_cache").deleteRecursively()
                    app.deleteDatabase("news.db")
                    runBlocking { androidx.work.WorkManager.getInstance(app).cancelUniqueWork("news-feed-sync").result.get() }
                    ok()
                }
                "flush" -> { runBlocking { ExperimentRuntime.telemetry?.flush() }; ok() }
                "mark_end" -> {
                    checkNotNull(ExperimentRuntime.telemetry) { "No active measurement telemetry" }
                    runBlocking {
                        ExperimentRuntime.viewModel?.stopPrefetch()
                        ExperimentRuntime.viewModel?.endExperiment()
                        RepositoryBinding.data(app).quiesceNetwork()
                    }
                    ExperimentRuntime.telemetry?.record("composition_count", value = ExperimentRuntime.compositions.get(), unit = "compositions")
                    ExperimentRuntime.telemetry?.record("measure_end")
                    runBlocking { ExperimentRuntime.telemetry?.flush() }
                    ok()
                }
                "offline_ack" -> { ExperimentRuntime.offlineAcknowledged = true; ok() }
                "refresh" -> {
                    runBlocking(Dispatchers.Main) { requireNotNull(ExperimentRuntime.viewModel).refresh() }
                    ok()
                }
                "state" -> Bundle().apply {
                    putBoolean("busy", ExperimentRuntime.harnessBusy)
                    putBoolean("success", ExperimentRuntime.harnessSuccess)
                    putBoolean("offline_ack", ExperimentRuntime.offlineAcknowledged)
                    putString("error", ExperimentRuntime.harnessError)
                    putString("ready_query", ExperimentRuntime.readyQuery)
                }
                "snapshot" -> {
                    require(arg in listOf("normal", "moderate", "critical", "slow", "real"))
                    val vm = requireNotNull(ExperimentRuntime.viewModel)
                    runBlocking(Dispatchers.Main) {
                        if (arg == "real") vm.controller.clearInjection() else vm.controller.inject(
                            ResourceSnapshot(android.os.SystemClock.elapsedRealtime(),
                                when (arg) { "critical" -> MemoryPressure.CRITICAL; "moderate" -> MemoryPressure.MODERATE; else -> MemoryPressure.NORMAL },
                                80, false, Thermal.NORMAL, if (arg == "slow") LinkQuality.SLOW else LinkQuality.FAST, false),
                            repeat = false)
                    }
                    ok()
                }
                "harness" -> {
                    require(arg in listOf("dedup", "concurrency", "room", "schedule"))
                    check(!ExperimentRuntime.harnessBusy)
                    ExperimentRuntime.harnessBusy = true
                    ExperimentRuntime.harnessSuccess = false
                    ExperimentRuntime.harnessError = null
                    scope.launch {
                        val t = requireNotNull(ExperimentRuntime.telemetry)
                        try {
                            val data = RepositoryBinding.data(app)
                            when (arg) {
                                "dedup" -> coroutineScope {
                                    (1..10).map { index -> async {
                                        val started = android.os.SystemClock.elapsedRealtime()
                                        val success = runCatching { data.repository.detail(9999) }.isSuccess
                                        t.record("consumer_complete", attrs = mapOf("consumer" to index, "success" to success))
                                        t.record("detail_ready", value = android.os.SystemClock.elapsedRealtime()-started, unit = "ms")
                                    } }.awaitAll()
                                }
                                "concurrency" -> coroutineScope {
                                    val jobs = (100L..119L).map { id -> async { runCatching {
                                        val article = ArticleSummary(id, "", "", "", "", "/v1/images/$id")
                                        data.imageCalls.newCall(okhttp3.Request.Builder().url(data.imageUrls.url(article, 720)).build()).execute().use { it.body?.bytes() }
                                    } } }
                                    delay(150)
                                    val started = android.os.SystemClock.elapsedRealtime()
                                    data.repository.detail(9999)
                                    t.record("detail_ready", value = android.os.SystemClock.elapsedRealtime()-started, unit = "ms")
                                    jobs.awaitAll()
                                }
                                "room" -> roomProbe(t)
                                "schedule" -> {
                                    val works = androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork("news-feed-sync").get()
                                    val active = works.filter { !it.state.isFinished }
                                    check(active.size == 1) { "Expected one active unique periodic work" }
                                    val interval = requireNotNull(active.single().periodicityInfo).repeatIntervalMillis
                                    val expected = Experiment.int("sync_minutes", 30)*60_000L
                                    t.record("schedule_checked", attrs = mapOf("unique_work_count" to active.size,
                                        "history_count" to works.size, "interval_ms" to interval,
                                        "initial_delay_ms" to active.single().initialDelayMillis,
                                        "network_constraint" to active.single().constraints.requiredNetworkType.name,
                                        "states" to active.map { it.state.name }))
                                    check(interval == expected && active.single().initialDelayMillis == expected)
                                    check(active.single().constraints.requiredNetworkType == androidx.work.NetworkType.CONNECTED)
                                }
                            }
                            t.record("harness_end", attrs = mapOf("success" to true, "kind" to arg))
                            ExperimentRuntime.harnessSuccess = true
                        } catch (e: Exception) {
                            ExperimentRuntime.harnessError = e.message ?: e.javaClass.name
                            t.record("harness_end", attrs = mapOf("success" to false, "error" to (e.message ?: e.javaClass.name), "kind" to arg))
                        } finally { ExperimentRuntime.harnessBusy = false }
                    }
                    ok()
                }
                else -> error("Unsupported benchmark method")
            }
        } catch (error: Exception) {
            Bundle().apply { putBoolean("ok", false); putString("error", error.message) }
        }
    }

    private fun roomProbe(t: com.adaptivenews.app.telemetry.Telemetry) {
        val app = requireNotNull(context)
        val room = androidx.room.Room.inMemoryDatabaseBuilder(app, com.adaptivenews.app.data.NewsDatabase::class.java).build()
        try {
            val db = room.openHelper.writableDatabase
            room.runInTransaction {
                for (id in 1..10_000) {
                    db.execSQL("INSERT INTO saved_articles VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(id, "Fixture article $id", "Summary", "Technology", "%05d".format(id), "/v1/images/$id", "Fixture body $id"))
                }
            }
            if (!Experiment.bool("room_index", true)) db.execSQL("DROP INDEX index_saved_articles_publishedAt_id")
            val sql = "SELECT * FROM saved_articles ORDER BY publishedAt DESC, id DESC"
            val plan = db.query("EXPLAIN QUERY PLAN $sql").use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.getString(3)) } }
            t.record("query_plan", attrs = mapOf("plan" to plan, "rows" to 10_000))
            repeat(36) { index ->
                val started = android.os.SystemClock.elapsedRealtimeNanos()
                val result = runBlocking { room.articles().observeSaved().first() }
                val elapsed = (android.os.SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
                check(result.size == 10_000 && result.first().id == 10_000L)
                if (index >= 5) t.record("room_query", value = elapsed, unit = "ms")
            }
        } finally { room.close() }
    }

    private fun ok() = Bundle().apply { putBoolean("ok", true) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        require(mode == "r" && uri.pathSegments.size == 2 && uri.pathSegments[0] == "events")
        val id = uri.pathSegments[1]
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return ParcelFileDescriptor.open(File(requireNotNull(context).filesDir, "telemetry/$id/events.jsonl"), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/x-ndjson"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? { authorize(); return null }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unsupported")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Unsupported")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unsupported")
}
