package com.adaptivenews.app.platform

/** Optional hook for the data layer; separate from CORE's ResourceMonitor contract. */
fun interface ArticleRequestSignal {
    fun successfulRequest(durationMs: Long)
}
