package com.adaptivenews.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.adaptivenews.benchmark.ScenarioDriver.launchVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AndroidX Macrobenchmark suite covering scenarios S1–S7 for V1, V2, and V3.
 *
 * Variant and run metadata can be parameterized via instrumentation runner arguments:
 *   -Pandroid.testInstrumentationRunnerArguments.variant=V3
 *   -Pandroid.testInstrumentationRunnerArguments.run_prefix=R_OFFICIAL
 *   -Pandroid.testInstrumentationRunnerArguments.iterations=5
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class AdaptiveNewsMacrobenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val defaultIterations: Int
        get() = BenchmarkConfig.intArg("iterations", 5)

    private val startupIterations: Int
        get() = BenchmarkConfig.intArg("startup_iterations", 10)

    private val runPrefix: String
        get() = BenchmarkConfig.arg("run_prefix", "MB")

    // =========================================================================
    // S1: Startup (Cold and Warm separated per CORE.md Section 11.1)
    // =========================================================================

    @Test
    fun s1_coldStartup_v1() = measureStartup("V1", StartupMode.COLD, "cold")

    @Test
    fun s1_coldStartup_v2() = measureStartup("V2", StartupMode.COLD, "cold")

    @Test
    fun s1_coldStartup_v3() = measureStartup("V3", StartupMode.COLD, "cold")

    @Test
    fun s1_warmStartup_v1() = measureStartup("V1", StartupMode.WARM, "warm")

    @Test
    fun s1_warmStartup_v2() = measureStartup("V2", StartupMode.WARM, "warm")

    @Test
    fun s1_warmStartup_v3() = measureStartup("V3", StartupMode.WARM, "warm")

    private fun measureStartup(variant: String, startupMode: StartupMode, cacheLabel: String) {
        var iteration = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = startupMode,
            iterations = startupIterations,
            setupBlock = {
                iteration++
                if (startupMode == StartupMode.COLD) {
                    killProcess()
                }
            }
        ) {
            val runId = "${runPrefix}_S1_${variant}_${cacheLabel}_r${iteration}"
            launchVariant(
                variant = variant,
                runId = runId,
                scenarioId = "S1",
                resetMode = "${cacheLabel}_startup"
            )
            ScenarioDriver.runS1Startup(device)
        }
    }

    // =========================================================================
    // S2: Feed scrolling (60s, fast network)
    // =========================================================================

    @Test
    fun s2_feedScrollingFast_v1() = measureFeedScroll("S2", "V1")

    @Test
    fun s2_feedScrollingFast_v2() = measureFeedScroll("S2", "V2")

    @Test
    fun s2_feedScrollingFast_v3() = measureFeedScroll("S2", "V3")

    // =========================================================================
    // S3: Feed scrolling (60s, slow shaped network)
    // =========================================================================

    @Test
    fun s3_feedScrollingSlow_v1() = measureFeedScroll("S3", "V1")

    @Test
    fun s3_feedScrollingSlow_v2() = measureFeedScroll("S3", "V2")

    @Test
    fun s3_feedScrollingSlow_v3() = measureFeedScroll("S3", "V3")

    private fun measureFeedScroll(scenarioId: String, variant: String) {
        val cacheState = BenchmarkConfig.arg("cache_state", "cold")
        var iteration = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = defaultIterations,
            setupBlock = {
                iteration++
                killProcess()
                if (cacheState == "cold") {
                    device.executeShellCommand("pm clear ${BenchmarkConfig.PACKAGE_NAME}")
                }
            }
        ) {
            val runId = "${runPrefix}_${scenarioId}_${variant}_${cacheState}_r${iteration}"
            launchVariant(
                variant = variant,
                runId = runId,
                scenarioId = scenarioId,
                resetMode = "${cacheState}_cache"
            )
            ScenarioDriver.runS2OrS3FeedScroll(device)
        }
    }

    // =========================================================================
    // S4: Search fixture query + Detail view
    // =========================================================================

    @Test
    fun s4_searchAndDetail_v1() = measureSearchAndDetail("V1")

    @Test
    fun s4_searchAndDetail_v2() = measureSearchAndDetail("V2")

    @Test
    fun s4_searchAndDetail_v3() = measureSearchAndDetail("V3")

    private fun measureSearchAndDetail(variant: String) {
        val cacheState = BenchmarkConfig.arg("cache_state", "cold")
        var iteration = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = defaultIterations,
            setupBlock = {
                iteration++
                killProcess()
                if (cacheState == "cold") {
                    device.executeShellCommand("pm clear ${BenchmarkConfig.PACKAGE_NAME}")
                }
            }
        ) {
            val runId = "${runPrefix}_S4_${variant}_${cacheState}_r${iteration}"
            launchVariant(
                variant = variant,
                runId = runId,
                scenarioId = "S4",
                resetMode = "${cacheState}_cache"
            )
            ScenarioDriver.runS4SearchAndDetail(device)
        }
    }

    // =========================================================================
    // S5: Offline saved article reading
    // =========================================================================

    @Test
    fun s5_offlineSaved_v1() = measureOfflineSaved("V1")

    @Test
    fun s5_offlineSaved_v2() = measureOfflineSaved("V2")

    @Test
    fun s5_offlineSaved_v3() = measureOfflineSaved("V3")

    private fun measureOfflineSaved(variant: String) {
        var iteration = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = defaultIterations,
            setupBlock = {
                iteration++
                killProcess()
                device.executeShellCommand("svc wifi enable")
                device.executeShellCommand("svc data enable")
            }
        ) {
            val runId = "${runPrefix}_S5_${variant}_warm_r${iteration}"
            launchVariant(
                variant = variant,
                runId = runId,
                scenarioId = "S5",
                resetMode = "seed_saved_offline"
            )
            ScenarioDriver.runS5OfflineSavedArticle(device)
        }
    }

    // =========================================================================
    // S6: Context change injection via Lab screen (simulated source)
    // =========================================================================

    @Test
    fun s6_contextInjection_v3() {
        var iteration = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = defaultIterations,
            setupBlock = {
                iteration++
                killProcess()
            }
        ) {
            val runId = "${runPrefix}_S6_V3_warm_r${iteration}"
            launchVariant(
                variant = "V3",
                runId = runId,
                scenarioId = "S6",
                resetMode = "simulated_injection"
            )
            ScenarioDriver.runS6ContextInjection(device)
        }
    }

    // =========================================================================
    // S7: 5-minute stress test
    // =========================================================================

    @Test
    fun s7_stressFiveMinutes_v1() = measureStress("V1")

    @Test
    fun s7_stressFiveMinutes_v2() = measureStress("V2")

    @Test
    fun s7_stressFiveMinutes_v3() = measureStress("V3")

    private fun measureStress(variant: String) {
        val stressIterations = BenchmarkConfig.intArg("stress_iterations", 1)
        var iteration = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = stressIterations,
            setupBlock = {
                iteration++
                killProcess()
                device.executeShellCommand("pm clear ${BenchmarkConfig.PACKAGE_NAME}")
            }
        ) {
            val runId = "${runPrefix}_S7_${variant}_cold_r${iteration}"
            launchVariant(
                variant = variant,
                runId = runId,
                scenarioId = "S7",
                resetMode = "cold_cache"
            )
            ScenarioDriver.runS7StressLoop(device)
        }
    }
}
