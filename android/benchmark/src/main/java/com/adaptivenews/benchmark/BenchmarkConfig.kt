package com.adaptivenews.benchmark

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Shared contract constants for Adaptive News Macrobenchmark & UIAutomator scenarios.
 * Strictly aligned with CORE.md sections 6, 9, 10, 11 and MainActivity.kt.
 */
object BenchmarkConfig {
    const val PACKAGE_NAME = "com.adaptivenews.app"
    const val MAIN_ACTIVITY = "com.adaptivenews.app.ui.MainActivity"

    // Stable Compose test tags exposed via testTagsAsResourceId = true (CORE.md Section 9)
    const val TAG_FEED_LIST = "feed_list"
    const val TAG_ARTICLE_1 = "article_1"
    fun articleTag(id: Long): String = "article_$id"

    const val TAG_SEARCH_INPUT = "search_input"
    const val TAG_SEARCH_RESULTS = "search_results"

    const val TAG_DETAIL_BODY = "detail_body"
    const val TAG_SAVE_BUTTON = "save_button"

    const val TAG_SAVED_LIST = "saved_list"

    const val TAG_LAB_SCREEN = "lab_screen"
    const val TAG_VARIANT_SELECTOR = "variant_selector"
    const val TAG_MODE_LABEL = "mode_label"
    const val TAG_EXPORT_BUTTON = "export_button"

    // Bottom navigation labels defined in AdaptiveNewsApp.kt
    const val NAV_FEED = "Tin"
    const val NAV_SEARCH = "Tìm"
    const val NAV_SAVED = "Đã lưu"
    const val NAV_LAB = "Lab"

    // Action button labels in Detail & Lab screens
    const val BTN_SAVE = "Lưu bài"
    const val BTN_UNSAVE = "Bỏ lưu"
    const val BTN_INJECT_NORMAL = "Normal"
    const val BTN_INJECT_CRITICAL = "Critical"
    const val BTN_INJECT_SLOW = "Slow"
    const val BTN_USE_REAL_STATE = "Dùng trạng thái thật"
    const val BTN_RESET_IMAGE_CACHE = "Reset cache ảnh"
    const val BTN_RESET_ALL_DATA = "Reset dữ liệu"

    // Canonical fixture query & article ID (exists in both 30-item UI fixture and 10,000-item seed-42 dataset)
    const val FIXTURE_SEARCH_QUERY = "công nghệ 00001"
    const val FIXTURE_ARTICLE_ID = 1L
    const val SEARCH_DEBOUNCE_MS = 300L

    // Timeouts & durations
    const val DEFAULT_WAIT_MS = 10_000L
    const val SCROLL_DURATION_S2_S3_MS = 60_000L
    const val STRESS_DURATION_S7_MS = 300_000L
    const val HYSTERESIS_COOLDOWN_WAIT_MS = 32_000L

    private val SAFE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")

    fun sanitizeId(raw: String, fallback: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        return if (SAFE_ID_REGEX.matches(cleaned)) cleaned else fallback
    }

    fun arg(key: String, default: String): String {
        val args = InstrumentationRegistry.getArguments()
        return args.getString(key)?.takeIf { it.isNotBlank() } ?: default
    }

    fun intArg(key: String, default: Int): Int {
        val args = InstrumentationRegistry.getArguments()
        return args.getString(key)?.toIntOrNull() ?: default
    }

    /**
     * Build launch intent matching MainActivity.kt automatedRun requirements:
     * variant, run_id, scenario_id, and optional commit, config_hash, reset_mode.
     */
    fun buildLaunchIntent(
        variant: String,
        runId: String,
        scenarioId: String,
        resetMode: String = arg("reset_mode", "cold_cache"),
        commit: String = arg("commit", "uncommitted"),
        configHash: String = arg("config_hash", "default_cfg")
    ): Intent {
        require(variant in setOf("V1", "V2", "V3")) { "Invalid variant: $variant" }
        val safeRunId = sanitizeId(runId, "run_001")
        val safeScenarioId = sanitizeId(scenarioId, "S1")
        val safeCommit = sanitizeId(commit, "uncommitted")
        val safeConfig = sanitizeId(configHash, "default_cfg")
        val safeReset = sanitizeId(resetMode, "cold_cache")

        return Intent().apply {
            setClassName(PACKAGE_NAME, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("variant", variant)
            putExtra("run_id", safeRunId)
            putExtra("scenario_id", safeScenarioId)
            putExtra("commit", safeCommit)
            putExtra("config_hash", safeConfig)
            putExtra("reset_mode", safeReset)
        }
    }
}
