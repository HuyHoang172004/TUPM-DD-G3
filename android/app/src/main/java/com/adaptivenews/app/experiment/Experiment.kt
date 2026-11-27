package com.adaptivenews.app.experiment

import android.content.Context
import com.adaptivenews.app.BuildConfig
import com.adaptivenews.app.core.contract.Policy
import org.json.JSONObject

/** Optional benchmark overrides. No persisted experimental setting is read in debug/release. */
object Experiment {
    private var stored: JSONObject? = null
    val config: JSONObject get() = requireNotNull(stored)
    val active get() = BuildConfig.BUILD_TYPE == "benchmark" && stored?.has("trial_id") == true
    val nativeVariant get() = active && config.optBoolean("native_variant", false)
    val isolated get() = active && !nativeVariant
    fun load(context: Context) {
        stored = if (BuildConfig.BUILD_TYPE == "benchmark")
            JSONObject(context.getSharedPreferences("experiment", Context.MODE_PRIVATE).getString("config", "{}") ?: "{}")
        else null
    }
    fun bool(name: String, default: Boolean) = if (isolated) config.optBoolean(name, default) else default
    fun int(name: String, default: Int) = if (isolated) config.optInt(name, default) else default
    val trialId get() = config.optString("trial_id", "")
    val paging: Boolean? get() = if (isolated) config.optBoolean("paging", true) else null
    val adaptive get() = isolated && config.optBoolean("adaptive", false)
    fun policy(value: Policy): Policy {
        if (!isolated || adaptive) return value
        return value.copy(
            imageWidth = int("image_width", value.imageWidth),
            pageSize = if (paging == false) 100 else 20,
            memoryCacheBytes = int("memory_cache_mib", 32).toLong() * 1024 * 1024,
            prefetchCount = int("prefetch_count", 0),
            maxConcurrentRequests = int("request_limit", 4),
            animations = bool("animations", false),
            syncMinutes = int("sync_minutes", 30).toLong(),
            reason = "experiment_" + config.optString("arm"),
        )
    }
}
