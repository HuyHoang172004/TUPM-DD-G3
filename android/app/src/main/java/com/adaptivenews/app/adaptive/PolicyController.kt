package com.adaptivenews.app.adaptive

import android.os.SystemClock
import com.adaptivenews.app.core.contract.Policy
import com.adaptivenews.app.core.contract.PolicyProvider
import com.adaptivenews.app.core.contract.ResourceMonitor
import com.adaptivenews.app.core.contract.ResourceSnapshot
import com.adaptivenews.app.core.contract.Variant
import com.adaptivenews.app.core.contract.MemoryPressure
import com.adaptivenews.app.core.contract.Thermal
import com.adaptivenews.app.core.contract.LinkQuality
import com.adaptivenews.app.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PolicyController(
    private val monitor: ResourceMonitor,
    private val telemetry: Telemetry,
    private val scope: CoroutineScope,
    variant: Variant
) : PolicyProvider {
    private val engine = AdaptiveEngine(variant)
    private val initial = ResourceSnapshot(0, MemoryPressure.UNKNOWN, null, null, Thermal.UNKNOWN, LinkQuality.UNKNOWN, null)
    private val mutablePolicy = MutableStateFlow(engine.evaluate(initial))
    override val policy: StateFlow<Policy> = mutablePolicy
    private val mutableSnapshot = MutableStateFlow<ResourceSnapshot?>(null)
    val snapshot: StateFlow<ResourceSnapshot?> = mutableSnapshot
    private val mutableSource = MutableStateFlow("real")
    val source: StateFlow<String> = mutableSource
    private var simulated: ResourceSnapshot? = null
    private var lastReal: ResourceSnapshot? = null
    private var simulatedJob: Job? = null
    private var foreground = false

    init {
        scope.launch {
            monitor.snapshots.collect { real ->
                lastReal = real
                if (simulated == null) apply(real, "real")
            }
        }
    }

    fun inject(snapshot: ResourceSnapshot) {
        simulatedJob?.cancel()
        simulated = snapshot
        apply(snapshot.copy(elapsedMs = SystemClock.elapsedRealtime()), "simulated")
        if (foreground) startSimulatedLoop()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        simulatedJob?.cancel()
        simulatedJob = null
        if (value && simulated != null) startSimulatedLoop()
    }

    private fun startSimulatedLoop() {
        simulatedJob = scope.launch {
            while (true) {
                delay(5_000L)
                simulated?.let { apply(it.copy(elapsedMs = SystemClock.elapsedRealtime()), "simulated") }
            }
        }
    }

    fun clearInjection() {
        simulatedJob?.cancel()
        simulatedJob = null
        simulated = null
        lastReal?.let { apply(it.copy(elapsedMs = SystemClock.elapsedRealtime()), "real") }
    }

    private fun apply(snapshot: ResourceSnapshot, source: String) {
        mutableSnapshot.value = snapshot
        mutableSource.value = source
        telemetry.snapshot(snapshot, source)
        val started = SystemClock.elapsedRealtimeNanos()
        val next = engine.evaluate(snapshot)
        val durationMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        val old = mutablePolicy.value
        val changed = old.copy(reason = next.reason) != next
        telemetry.record("engine_eval", mode = next.mode.name, source = source,
            value = durationMs, unit = "ms", attrs = mapOf("changed" to changed))
        if (changed) {
            mutablePolicy.value = next
            telemetry.policyChange(old, next, source)
        }
    }
}
