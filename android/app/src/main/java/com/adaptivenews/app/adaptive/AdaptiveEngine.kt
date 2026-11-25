package com.adaptivenews.app.adaptive

import com.adaptivenews.app.core.contract.LinkQuality
import com.adaptivenews.app.core.contract.MemoryPressure
import com.adaptivenews.app.core.contract.Mode
import com.adaptivenews.app.core.contract.Policy
import com.adaptivenews.app.core.contract.ResourceSnapshot
import com.adaptivenews.app.core.contract.Thermal
import com.adaptivenews.app.core.contract.Variant

/** Pure decision logic. All time comes from ResourceSnapshot.elapsedMs (monotonic). */
class AdaptiveEngine(private val variant: Variant) {
    private var mode = when (variant) {
        Variant.V1 -> Mode.BASELINE
        else -> Mode.BALANCED
    }
    private var lastModeChangeMs: Long? = null
    private var pendingMode: Mode? = null
    private var consecutiveSamples = 0

    fun evaluate(snapshot: ResourceSnapshot): Policy {
        if (variant == Variant.V1) return baseline(snapshot)
        if (variant == Variant.V2) return balanced(snapshot)

        val candidate = chooseMode(snapshot)
        if (candidate == mode) {
            pendingMode = null
            consecutiveSamples = 0
        } else {
            if (pendingMode == candidate) consecutiveSamples++ else {
                pendingMode = candidate
                consecutiveSamples = 1
            }
            val emergency = candidate == Mode.ECO && (
                snapshot.memory == MemoryPressure.CRITICAL ||
                    snapshot.thermal == Thermal.SEVERE || snapshot.powerSave == true
                )
            val downgrade = rank(candidate) < rank(mode)
            val recovered = when (mode) {
                Mode.ECO -> snapshot.batteryPct == null || snapshot.batteryPct > 25
                Mode.BALANCED -> snapshot.batteryPct == null || snapshot.batteryPct > 45
                else -> true
            }
            val cooldownPassed = lastModeChangeMs == null ||
                snapshot.elapsedMs - lastModeChangeMs!! >= 30_000L
            if (emergency || (downgrade && consecutiveSamples >= 2) ||
                (!downgrade && recovered && consecutiveSamples >= 3 && cooldownPassed)
            ) {
                mode = candidate
                lastModeChangeMs = snapshot.elapsedMs
                pendingMode = null
                consecutiveSamples = 0
            }
        }
        val profile = when (mode) {
            Mode.PERFORMANCE -> performance(snapshot)
            Mode.ECO -> eco(snapshot)
            else -> balanced(snapshot)
        }
        return constrainNetwork(profile, snapshot).copy(reason = reason(snapshot))
    }

    fun chooseMode(snapshot: ResourceSnapshot): Mode = when {
        snapshot.memory == MemoryPressure.CRITICAL || snapshot.batteryPct != null && snapshot.batteryPct <= 20 ||
            snapshot.powerSave == true || snapshot.thermal == Thermal.SEVERE -> Mode.ECO
        snapshot.memory == MemoryPressure.MODERATE || snapshot.batteryPct != null && snapshot.batteryPct <= 40 ||
            snapshot.thermal == Thermal.MODERATE || snapshot.link == LinkQuality.SLOW ||
            snapshot.memory == MemoryPressure.UNKNOWN || snapshot.batteryPct == null ||
            snapshot.powerSave == null || snapshot.link == LinkQuality.UNKNOWN -> Mode.BALANCED
        // Thermal UNKNOWN is expected on API 26–28 and alone does not block Performance.
        else -> Mode.PERFORMANCE
    }

    private fun rank(value: Mode) = when (value) {
        Mode.ECO -> 0
        Mode.BALANCED -> 1
        Mode.PERFORMANCE -> 2
        Mode.BASELINE -> 1
    }

    private fun reason(s: ResourceSnapshot): String = when {
        s.memory == MemoryPressure.CRITICAL -> "memory_critical"
        s.thermal == Thermal.SEVERE -> "thermal_severe"
        s.powerSave == true -> "power_save"
        s.batteryPct != null && s.batteryPct <= 20 -> "battery_critical"
        s.link == LinkQuality.OFFLINE -> "offline"
        s.metered != false -> "metered_or_unknown"
        s.link == LinkQuality.SLOW -> "link_slow"
        s.memory == MemoryPressure.MODERATE -> "memory_moderate"
        s.batteryPct != null && s.batteryPct <= 40 -> "battery_low"
        s.thermal == Thermal.MODERATE -> "thermal_moderate"
        s.memory == MemoryPressure.UNKNOWN || s.link == LinkQuality.UNKNOWN ||
            s.batteryPct == null || s.powerSave == null -> "signal_unknown"
        else -> "normal"
    }

    private fun baseline(s: ResourceSnapshot) = Policy(
        Mode.BASELINE, 1080, 100, 0L, 0, 0, true, 15,
        s.link != LinkQuality.OFFLINE, false, "baseline"
    )

    private fun balanced(s: ResourceSnapshot) = Policy(
        Mode.BALANCED, 720, 20, 32L * MIB, 4, 4, false, 30,
        s.link != LinkQuality.OFFLINE, true, "fixed_balanced"
    )

    private fun performance(s: ResourceSnapshot) = Policy(
        Mode.PERFORMANCE, 1080, 40, 64L * MIB, 10, 6, true, 15,
        s.link != LinkQuality.OFFLINE, true, "normal"
    )

    private fun eco(s: ResourceSnapshot) = Policy(
        Mode.ECO, 360, 10, 16L * MIB, 0, 2, false, 60,
        s.link != LinkQuality.OFFLINE, true, "eco"
    )

    private fun constrainNetwork(policy: Policy, s: ResourceSnapshot): Policy {
        if (s.link == LinkQuality.OFFLINE) return policy.copy(allowNetwork = false, prefetchCount = 0)
        var prefetch = policy.prefetchCount
        var concurrent = policy.maxConcurrentRequests
        if (s.metered != false) {
            prefetch = 0
            concurrent = minOf(concurrent, 2)
        }
        if (s.link == LinkQuality.SLOW) {
            prefetch = minOf(prefetch, 2)
            concurrent = minOf(concurrent, 2)
        }
        return policy.copy(prefetchCount = prefetch, maxConcurrentRequests = concurrent)
    }

    companion object { private const val MIB = 1024L * 1024L }
}
