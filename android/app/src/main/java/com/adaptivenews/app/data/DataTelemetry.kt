package com.adaptivenews.app.data

/** Payload for Hoàng's telemetry layer, which supplies the common run envelope. */
fun interface DataTelemetry {
    fun emit(event: String, value: Long?, unit: String?, attrs: Map<String, Any?>)
}

internal class PrefetchUsage(private val telemetry: DataTelemetry) {
    private val pending = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()
    private val unused = mutableSetOf<String>()
    private val served = mutableSetOf<String>()

    @Synchronized
    fun reserve(key: String) = key !in completed && pending.add(key)

    @Synchronized
    fun release(key: String) {
        pending.remove(key)
    }

    @Synchronized
    fun finished(key: String, success: Boolean, bytes: Long) {
        if (success) {
            completed.add(key)
            if (bytes > 0) unused.add(key)
        }
        telemetry.emit(
            "prefetch_end", bytes, "body_bytes", mapOf("key" to key, "success" to success),
        )
    }

    @Synchronized
    fun cacheHit(key: String) {
        if (key in unused) served.add(key)
    }

    @Synchronized
    fun displayed(key: String) {
        if (served.remove(key) && unused.remove(key)) {
            telemetry.emit("prefetch_used", null, null, mapOf("key" to key))
        }
    }

    @Synchronized
    fun reset() {
        completed.clear()
        unused.clear()
        served.clear()
    }
}
