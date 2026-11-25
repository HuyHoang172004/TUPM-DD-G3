package com.adaptivenews.app.telemetry

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.adaptivenews.app.core.contract.Policy
import com.adaptivenews.app.core.contract.ResourceSnapshot
import com.adaptivenews.app.core.contract.Variant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Same event schema and writer path for every variant. Never writes on the UI thread. */
class Telemetry(
    private val context: Context,
    private val scope: CoroutineScope,
    val runId: String,
    val scenarioId: String,
    private val variant: Variant
) {
    private sealed interface Command {
        data class Event(val line: String) : Command
        data class Flush(val result: CompletableDeferred<Unit>) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    val file: File = File(context.filesDir, "telemetry/$runId/events.jsonl")

    init {
        scope.launch(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                var pending = 0
                while (true) {
                    val command = withTimeoutOrNull(5_000L) { commands.receive() }
                    if (command == null) {
                        if (pending > 0) { writer.flush(); pending = 0 }
                        continue
                    }
                    when (command) {
                    is Command.Event -> {
                        writer.appendLine(command.line)
                        if (++pending >= 16) { writer.flush(); pending = 0 }
                    }
                    is Command.Flush -> {
                        writer.flush()
                        pending = 0
                        command.result.complete(Unit)
                    }
                    }
                }
            }
        }
    }

    fun record(
        event: String,
        mode: String? = null,
        source: String = "real",
        reason: String? = null,
        value: Number? = null,
        unit: String? = null,
        attrs: Map<String, Any?> = emptyMap()
    ) {
        val json = JSONObject()
            .put("schema_version", 1)
            .put("run_id", runId)
            .put("scenario_id", scenarioId)
            .put("variant", variant.name)
            .put("event", event)
            .put("elapsed_ms", SystemClock.elapsedRealtime())
            .put("mode", mode ?: JSONObject.NULL)
            .put("source", source)
            .put("reason", reason ?: JSONObject.NULL)
            .put("value", value ?: JSONObject.NULL)
            .put("unit", unit ?: JSONObject.NULL)
            .put("attrs", JSONObject(attrs.mapValues { it.value ?: JSONObject.NULL }))
        commands.trySend(Command.Event(json.toString()))
    }

    fun snapshot(s: ResourceSnapshot, source: String) = record(
        "resource_snapshot", source = source, attrs = mapOf(
            "memory" to s.memory.name, "battery_pct" to s.batteryPct,
            "power_save" to s.powerSave, "thermal" to s.thermal.name,
            "link" to s.link.name, "metered" to s.metered,
            "app_cpu_pct" to s.appCpuPct
        )
    )

    fun policyChange(old: Policy, new: Policy, source: String) = record(
        "policy_change", mode = new.mode.name, source = source, reason = new.reason,
        attrs = mapOf(
            "old_mode" to old.mode.name,
            "image_width" to new.imageWidth, "page_size" to new.pageSize,
            "memory_cache_bytes" to new.memoryCacheBytes,
            "prefetch_count" to new.prefetchCount,
            "max_concurrent_requests" to new.maxConcurrentRequests,
            "animations" to new.animations, "sync_minutes" to new.syncMinutes,
            "allow_network" to new.allowNetwork, "optimized" to new.optimized
        )
    )

    suspend fun flush() {
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Flush(result))
        result.await()
    }

    suspend fun export(uri: Uri) {
        flush()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Cannot open export destination")
        }
    }
}
