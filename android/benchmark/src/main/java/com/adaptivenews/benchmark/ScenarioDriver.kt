package com.adaptivenews.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Deterministic UIAutomator scenario routines for S1–S8.
 * Uses only the stable test tags and labels defined in CORE.md Section 9.
 */
object ScenarioDriver {

    fun MacrobenchmarkScope.launchVariant(
        variant: String,
        runId: String,
        scenarioId: String,
        resetMode: String = "cold_cache"
    ) {
        val intent = BenchmarkConfig.buildLaunchIntent(
            variant = variant,
            runId = runId,
            scenarioId = scenarioId,
            resetMode = resetMode
        )
        startActivityAndWait(intent)
    }

    fun waitForTag(device: UiDevice, tag: String, timeoutMs: Long = BenchmarkConfig.DEFAULT_WAIT_MS): UiObject2 {
        return checkNotNull(device.wait(Until.findObject(By.res(tag)), timeoutMs)) {
            "Timed out after ${timeoutMs}ms waiting for testTag '$tag'"
        }
    }

    fun clickText(device: UiDevice, text: String, timeoutMs: Long = BenchmarkConfig.DEFAULT_WAIT_MS) {
        val target = checkNotNull(device.wait(Until.findObject(By.text(text)), timeoutMs)) {
            "Timed out after ${timeoutMs}ms waiting for text '$text'"
        }
        target.click()
        device.waitForIdle()
    }

    /**
     * S1: Startup verification — wait until feed_list is rendered on screen.
     */
    fun runS1Startup(device: UiDevice) {
        waitForTag(device, BenchmarkConfig.TAG_FEED_LIST)
        device.waitForIdle()
    }

    /**
     * S2 & S3: Deterministic 60-second feed scrolling script.
     * Repeats a fixed pattern of downward flings/scrolls and periodic upward scrolls
     * so V1, V2, and V3 traverse the exact same UI gesture trajectory.
     */
    fun runS2OrS3FeedScroll(
        device: UiDevice,
        durationMs: Long = BenchmarkConfig.SCROLL_DURATION_S2_S3_MS
    ) {
        waitForTag(device, BenchmarkConfig.TAG_FEED_LIST)
        val startedAt = SystemClock.elapsedRealtime()
        var step = 0
        while (SystemClock.elapsedRealtime() - startedAt < durationMs) {
            val feed = device.findObject(By.res(BenchmarkConfig.TAG_FEED_LIST))
                ?: waitForTag(device, BenchmarkConfig.TAG_FEED_LIST)
            feed.setGestureMargin(device.displayWidth / 10)
            val direction = if (step % 10 == 9) Direction.UP else Direction.DOWN
            feed.scroll(direction, 0.75f)
            device.waitForIdle()
            SystemClock.sleep(1_200L)
            step++
        }
    }

    /**
     * S4: Search fixture query ("công nghệ 00001"), wait past 300ms debounce,
     * verify search_results and article_1, then open article_1 and wait for detail_body.
     */
    fun runS4SearchAndDetail(device: UiDevice) {
        waitForTag(device, BenchmarkConfig.TAG_FEED_LIST)
        clickText(device, BenchmarkConfig.NAV_SEARCH)

        val searchInput = waitForTag(device, BenchmarkConfig.TAG_SEARCH_INPUT)
        searchInput.click()
        searchInput.text = BenchmarkConfig.FIXTURE_SEARCH_QUERY
        device.waitForIdle()

        // Wait past the 300ms debounce + network response
        SystemClock.sleep(BenchmarkConfig.SEARCH_DEBOUNCE_MS + 400L)
        waitForTag(device, BenchmarkConfig.TAG_SEARCH_RESULTS)

        val articleCard = waitForTag(device, BenchmarkConfig.TAG_ARTICLE_1)
        articleCard.click()
        device.waitForIdle()

        waitForTag(device, BenchmarkConfig.TAG_DETAIL_BODY)
        waitForTag(device, BenchmarkConfig.TAG_SAVE_BUTTON)
    }

    /**
     * S5: Save article_1 while online, disable device network (real offline state),
     * navigate to Saved ("Đã lưu"), open article_1 from saved_list, and verify detail_body.
     * Always restores network connectivity in the finally block.
     */
    fun runS5OfflineSavedArticle(device: UiDevice) {
        waitForTag(device, BenchmarkConfig.TAG_FEED_LIST)
        val articleCard = waitForTag(device, BenchmarkConfig.TAG_ARTICLE_1)
        articleCard.click()
        device.waitForIdle()

        waitForTag(device, BenchmarkConfig.TAG_DETAIL_BODY)
        val saveBtn = waitForTag(device, BenchmarkConfig.TAG_SAVE_BUTTON)
        val alreadySaved = device.findObject(By.text(BenchmarkConfig.BTN_UNSAVE)) != null
        if (!alreadySaved) {
            saveBtn.click()
            device.waitForIdle()
            SystemClock.sleep(500L)
        }

        try {
            // Cut real network connection on device/emulator
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("svc data disable")
            SystemClock.sleep(1_500L)

            clickText(device, BenchmarkConfig.NAV_SAVED)
            waitForTag(device, BenchmarkConfig.TAG_SAVED_LIST)

            val savedCard = waitForTag(device, BenchmarkConfig.TAG_ARTICLE_1)
            savedCard.click()
            device.waitForIdle()

            val detailBody = waitForTag(device, BenchmarkConfig.TAG_DETAIL_BODY)
            check(!detailBody.text.isNullOrBlank()) {
                "S5 offline verification failed: detail_body is empty when offline"
            }
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
            SystemClock.sleep(1_500L)
        }
    }

    /**
     * S6: Context change injection via Lab screen.
     * Injects sequence: Normal -> Critical (immediate Eco downgrade) -> Normal (hysteresis + 30s cooldown)
     * -> Slow (network constraint prefetch<=2, concurrency<=2) -> Normal -> real state.
     * Logs carry source=simulated; strictly used for logic/latency/overhead verification.
     */
    fun runS6ContextInjection(device: UiDevice) {
        waitForTag(device, BenchmarkConfig.TAG_FEED_LIST)
        clickText(device, BenchmarkConfig.NAV_LAB)
        waitForTag(device, BenchmarkConfig.TAG_LAB_SCREEN)
        waitForTag(device, BenchmarkConfig.TAG_MODE_LABEL)

        // 1. Inject Normal (3 samples across 12s to stabilize)
        clickText(device, BenchmarkConfig.BTN_INJECT_NORMAL)
        SystemClock.sleep(12_000L)

        // 2. Inject Critical -> emergency downgrade to ECO immediately
        clickText(device, BenchmarkConfig.BTN_INJECT_CRITICAL)
        SystemClock.sleep(6_000L)

        // 3. Inject Normal -> requires 3 consecutive samples AND >=30s cooldown from last mode switch
        clickText(device, BenchmarkConfig.BTN_INJECT_NORMAL)
        SystemClock.sleep(BenchmarkConfig.HYSTERESIS_COOLDOWN_WAIT_MS)

        // 4. Inject Slow -> network constraint (BALANCED, prefetch<=2, concurrency<=2)
        clickText(device, BenchmarkConfig.BTN_INJECT_SLOW)
        SystemClock.sleep(11_000L)

        // 5. Return to Normal & restore real state
        clickText(device, BenchmarkConfig.BTN_INJECT_NORMAL)
        SystemClock.sleep(6_000L)
        clickText(device, BenchmarkConfig.BTN_USE_REAL_STATE)
        device.waitForIdle()
    }

    /**
     * S7: 5-minute stress test repeating Feed scroll -> Search queries -> Detail view.
     */
    fun runS7StressLoop(
        device: UiDevice,
        durationMs: Long = BenchmarkConfig.STRESS_DURATION_S7_MS
    ) {
        val queries = listOf("công nghệ", "khoa học", "kinh doanh", "văn hóa", "thể thao")
        val startedAt = SystemClock.elapsedRealtime()
        var cycle = 0

        while (SystemClock.elapsedRealtime() - startedAt < durationMs) {
            // Phase A: Feed scrolling (20 seconds)
            clickText(device, BenchmarkConfig.NAV_FEED)
            runS2OrS3FeedScroll(device, durationMs = 20_000L)
            if (SystemClock.elapsedRealtime() - startedAt >= durationMs) break

            // Phase B: Search with rotating topic query
            clickText(device, BenchmarkConfig.NAV_SEARCH)
            val searchInput = waitForTag(device, BenchmarkConfig.TAG_SEARCH_INPUT)
            searchInput.click()
            searchInput.text = queries[cycle % queries.size]
            device.waitForIdle()
            SystemClock.sleep(BenchmarkConfig.SEARCH_DEBOUNCE_MS + 600L)
            waitForTag(device, BenchmarkConfig.TAG_SEARCH_RESULTS)
            if (SystemClock.elapsedRealtime() - startedAt >= durationMs) break

            // Phase C: Open first visible article card if present, inspect Detail, then return
            val targetId = (cycle % 5) + 1L
            val card = device.findObject(By.res(BenchmarkConfig.articleTag(targetId)))
                ?: device.findObject(By.res(BenchmarkConfig.TAG_ARTICLE_1))
            if (card != null) {
                card.click()
                device.waitForIdle()
                waitForTag(device, BenchmarkConfig.TAG_DETAIL_BODY)
                SystemClock.sleep(1_000L)
            }
            cycle++
        }
    }
}
