package com.adaptivenews.app.data

import com.adaptivenews.app.core.contract.PolicyProvider
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.Call

/** Runs on OkHttp threads. The permit covers response-body consumption as well as headers. */
internal class RequestGate(private val policies: PolicyProvider) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val foreground = ArrayDeque<Call>()
    private val active = linkedMapOf<Call, Boolean>()

    fun acquire(call: Call, prefetch: Boolean) = lock.withLock {
        if (!prefetch) foreground.addLast(call)
        try {
            while (true) {
                val policy = policies.policy.value
                if (call.isCanceled()) throw IOException("Canceled")
                if (prefetch && (!policy.allowNetwork || policy.prefetchCount == 0)) {
                    throw IOException("Prefetch disabled")
                }
                val overlapping = active.entries.firstOrNull { entry ->
                    entry.key.request().url == call.request().url && entry.value != prefetch
                }
                if (overlapping != null) {
                    if (prefetch) throw IOException("Image already requested in the foreground")
                    overlapping.key.cancel()
                }
                val underLimit = !policy.optimized || active.size < policy.maxConcurrentRequests
                val hasPriority = if (prefetch) foreground.isEmpty() else foreground.peekFirst() === call
                if (hasPriority && underLimit) {
                    active[call] = prefetch
                    return@withLock
                }
                if (!prefetch && hasPriority && !underLimit) {
                    active.entries.firstOrNull { it.value && !it.key.isCanceled() }?.key?.cancel()
                }
                changed.await(50, TimeUnit.MILLISECONDS)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Request wait interrupted", interrupted)
        } finally {
            foreground.remove(call)
            changed.signalAll()
        }
    }

    fun release(call: Call) = lock.withLock {
        active.remove(call)
        changed.signalAll()
    }
}
