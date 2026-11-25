package com.adaptivenews.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Cancels the producer when the last consumer leaves. */
internal class SharedRequests<K, V>(private val scope: CoroutineScope) {
    private class Entry<V>(val result: Deferred<V>, var consumers: Int = 0)
    private val active = mutableMapOf<K, Entry<V>>()

    suspend fun run(key: K, fetch: suspend () -> V): V {
        val entry = synchronized(active) {
            val existing = active[key]?.takeUnless { it.result.isCompleted }
            val current = existing ?: Entry(scope.async(start = CoroutineStart.LAZY) { fetch() })
                .also { active[key] = it }
            current.consumers++
            current
        }
        try {
            return entry.result.await()
        } finally {
            synchronized(active) {
                if (--entry.consumers == 0) {
                    if (active[key] === entry) active.remove(key)
                    entry.result.cancel()
                }
            }
        }
    }
}
