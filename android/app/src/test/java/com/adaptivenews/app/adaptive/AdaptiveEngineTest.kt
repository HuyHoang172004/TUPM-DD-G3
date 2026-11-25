package com.adaptivenews.app.adaptive

import com.adaptivenews.app.core.contract.LinkQuality
import com.adaptivenews.app.core.contract.MemoryPressure
import com.adaptivenews.app.core.contract.Mode
import com.adaptivenews.app.core.contract.ResourceSnapshot
import com.adaptivenews.app.core.contract.Thermal
import com.adaptivenews.app.core.contract.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdaptiveEngineTest {
    private fun snapshot(
        ms: Long = 0, memory: MemoryPressure = MemoryPressure.NORMAL,
        battery: Int? = 80, powerSave: Boolean? = false,
        thermal: Thermal = Thermal.NORMAL, link: LinkQuality = LinkQuality.FAST,
        metered: Boolean? = false
    ) = ResourceSnapshot(ms, memory, battery, powerSave, thermal, link, metered)

    @Test fun normalBecomesPerformanceAfterThreeSamples() {
        val engine = AdaptiveEngine(Variant.V3)
        engine.evaluate(snapshot(0))
        engine.evaluate(snapshot(5_000))
        assertEquals(Mode.PERFORMANCE, engine.evaluate(snapshot(10_000)).mode)
    }

    @Test fun moderateAndLowBatteryBecomeBalancedAfterTwoSamples() {
        val engine = AdaptiveEngine(Variant.V3)
        repeat(3) { engine.evaluate(snapshot(it * 5_000L)) }
        engine.evaluate(snapshot(15_000, memory = MemoryPressure.MODERATE))
        assertEquals(Mode.BALANCED, engine.evaluate(snapshot(20_000, memory = MemoryPressure.MODERATE)).mode)
    }

    @Test fun criticalThermalAndPowerSaverDowngradeImmediately() {
        assertEquals(Mode.ECO, AdaptiveEngine(Variant.V3).evaluate(snapshot(memory = MemoryPressure.CRITICAL)).mode)
        assertEquals(Mode.ECO, AdaptiveEngine(Variant.V3).evaluate(snapshot(thermal = Thermal.SEVERE)).mode)
        assertEquals(Mode.ECO, AdaptiveEngine(Variant.V3).evaluate(snapshot(powerSave = true)).mode)
    }

    @Test fun batteryCriticalDowngradesAfterTwoSamples() {
        val engine = AdaptiveEngine(Variant.V3)
        engine.evaluate(snapshot(battery = 15))
        assertEquals(Mode.ECO, engine.evaluate(snapshot(5_000, battery = 15)).mode)
    }

    @Test fun slowAndMeteredConstrainNetwork() {
        val slow = AdaptiveEngine(Variant.V3).evaluate(snapshot(link = LinkQuality.SLOW))
        assertEquals(Mode.BALANCED, slow.mode)
        assertEquals(2, slow.prefetchCount)
        assertEquals(2, slow.maxConcurrentRequests)
        val engine = AdaptiveEngine(Variant.V3)
        repeat(2) { engine.evaluate(snapshot(it * 5_000L, metered = true)) }
        val metered = engine.evaluate(snapshot(10_000, metered = true))
        assertEquals(Mode.PERFORMANCE, metered.mode)
        assertEquals(0, metered.prefetchCount)
        assertEquals(2, metered.maxConcurrentRequests)
    }

    @Test fun offlineStopsNetworkAndUnknownStaysBalanced() {
        val offline = AdaptiveEngine(Variant.V3).evaluate(snapshot(link = LinkQuality.OFFLINE))
        assertFalse(offline.allowNetwork)
        assertEquals(0, offline.prefetchCount)
        assertEquals(Mode.BALANCED, AdaptiveEngine(Variant.V3).evaluate(snapshot(link = LinkQuality.UNKNOWN)).mode)
    }

    @Test fun oscillationAndCooldownPreventModeFlapping() {
        val engine = AdaptiveEngine(Variant.V3)
        repeat(3) { engine.evaluate(snapshot(it * 5_000L)) }
        engine.evaluate(snapshot(15_000, memory = MemoryPressure.MODERATE))
        assertEquals(Mode.PERFORMANCE, engine.evaluate(snapshot(20_000)).mode)
        assertEquals(Mode.ECO, engine.evaluate(snapshot(25_000, memory = MemoryPressure.CRITICAL)).mode)
        repeat(3) { assertEquals(Mode.ECO, engine.evaluate(snapshot(30_000L + it * 5_000L)).mode) }
        assertEquals(Mode.PERFORMANCE, engine.evaluate(snapshot(55_000)).mode)
    }

    @Test fun alternatingHeapSignalsDoNotFlipMode() {
        val engine = AdaptiveEngine(Variant.V3)
        repeat(3) { engine.evaluate(snapshot(it * 5_000L)) }
        repeat(6) { index ->
            val memory = if (index % 2 == 0) MemoryPressure.MODERATE else MemoryPressure.NORMAL
            assertEquals(Mode.PERFORMANCE, engine.evaluate(snapshot(15_000L + index * 5_000L, memory = memory)).mode)
        }
    }

    @Test fun batteryNeedsRecoveryMarginToLeaveEco() {
        val engine = AdaptiveEngine(Variant.V3)
        engine.evaluate(snapshot(0, battery = 15))
        assertEquals(Mode.ECO, engine.evaluate(snapshot(5_000, battery = 15)).mode)
        repeat(4) { assertEquals(Mode.ECO, engine.evaluate(snapshot(10_000L + it * 5_000L, battery = 23)).mode) }
        repeat(2) { engine.evaluate(snapshot(30_000L + it * 5_000L, battery = 30)) }
        assertEquals(Mode.BALANCED, engine.evaluate(snapshot(40_000, battery = 30)).mode)
    }

    @Test fun fixedVariantsDoNotUseAdaptiveNetworkRules() {
        val v1 = AdaptiveEngine(Variant.V1).evaluate(snapshot(metered = true))
        val v2 = AdaptiveEngine(Variant.V2).evaluate(snapshot(metered = true))
        assertEquals(Mode.BASELINE, v1.mode)
        assertEquals(1080, v1.imageWidth)
        assertEquals(Mode.BALANCED, v2.mode)
        assertEquals(4, v2.prefetchCount)
        assertEquals(4, v2.maxConcurrentRequests)
        assertFalse(AdaptiveEngine(Variant.V2).evaluate(snapshot(link = LinkQuality.OFFLINE)).allowNetwork)
    }
}
