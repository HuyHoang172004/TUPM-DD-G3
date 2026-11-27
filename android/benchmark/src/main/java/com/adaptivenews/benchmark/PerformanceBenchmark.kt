package com.adaptivenews.benchmark

import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** External UI driver: never runs in the app's process and never uses screen coordinates for lookup. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class PerformanceBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val args get() = InstrumentationRegistry.getArguments()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app = "com.adaptivenews.app"
    private val trialId get() = requireNotNull(args.getString("trial_id"))

    private fun control(method: String, arg: String? = null): String {
        val command = "content call --uri content://$app.benchmark --method $method" + (arg?.let { " --arg $it" } ?: "")
        val result = device.executeShellCommand(command)
        check(!result.contains("Exception") && !result.contains("ok=false")) { result }
        return result
    }

    private fun element(tag: String, timeout: Long = 30_000): UiObject2 =
        requireNotNull(device.wait(Until.findObject(By.res(tag)), timeout)) { "Không tìm thấy $tag" }

    private fun log(action: String, state: String, point: Point? = null, point2: Point? = null, error: String? = null) {
        val row = JSONObject().put("trial_id", trialId).put("action", action).put("state", state)
            .put("device_elapsed_ms", SystemClock.elapsedRealtime())
        point?.let { row.put("x", it.x.toDouble()/device.displayWidth).put("y", it.y.toDouble()/device.displayHeight) }
        point2?.let { row.put("x2", it.x.toDouble()/device.displayWidth).put("y2", it.y.toDouble()/device.displayHeight) }
        error?.let { row.put("error", it) }
        Log.i("AN_ACTION", row.toString())
    }

    private fun step(name: String, point: Point? = null, point2: Point? = null, action: () -> Unit) {
        log(name, "sent", point, point2)
        try { action(); log(name, "completed", point, point2) }
        catch (error: Throwable) { log(name, "failed", point, point2, error.message); throw error }
    }

    private fun tap(tag: String) {
        val target = element(tag)
        step("Chạm $tag", target.visibleCenter) { target.click(); device.waitForIdle(2000) }
    }

    private fun swipe(forward: Boolean) {
        val bounds = element("feed_list").visibleBounds
        val x = bounds.centerX()
        val top = bounds.top + bounds.height()/4
        val bottom = bounds.bottom - bounds.height()/4
        val first = Point(x, if (forward) bottom else top)
        val last = Point(x, if (forward) top else bottom)
        step(if (forward) "Cuộn feed xuống" else "Cuộn feed lên", first, last) {
            check(device.swipe(first.x, first.y, last.x, last.y, 35))
        }
    }

    private fun launch() {
        val intent = Intent().setClassName(app, "$app.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("variant", args.getString("variant", "V2"))
            .putExtra("run_id", trialId)
            .putExtra("scenario_id", args.getString("scenario_id"))
            .putExtra("config_hash", args.getString("config_hash"))
            .putExtra("reset_mode", "cold_images")
        InstrumentationRegistry.getInstrumentation().context.startActivity(intent)
        check(device.wait(Until.hasObject(By.res("feed_list")), 30_000))
        element("article_1", 60_000)
    }

    @Test fun runJourney() {
        val phase = args.getString("phase", "performance")
        if (phase == "memory" || args.getString("journey") == "background") {
            launch()
            journey()
            finishJourney()
        } else {
            benchmark.measureRepeated(
                packageName = app,
                metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
                compilationMode = CompilationMode.Ignore(),
                startupMode = StartupMode.COLD,
                iterations = 1,
                setupBlock = { pressHome() },
            ) {
                val intent = Intent().setClassName(app, "$app.ui.MainActivity")
                    .putExtra("variant", args.getString("variant", "V2"))
                    .putExtra("run_id", trialId).putExtra("scenario_id", args.getString("scenario_id"))
                    .putExtra("config_hash", args.getString("config_hash"))
                    .putExtra("reset_mode", "cold_images")
                step("Mở Adaptive News") { startActivityAndWait(intent) }
                element("article_1", 60_000)
                journey()
                finishJourney()
            }
        }
    }

    private fun finishJourney() {
        args.getString("additionalTestOutputDir")?.let { directory ->
            val output = File(directory).apply { mkdirs() }
            check(device.takeScreenshot(File(output, "ui.png"))) { "Cannot capture UI evidence" }
        }
        control("mark_end")
        control("flush")
    }

    private fun harness(kind: String) {
        tap("nav_lab")
        step("Chạy workload $kind trong app") {
            control("harness", kind)
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (control("state").contains("busy=true")) {
                check(SystemClock.elapsedRealtime() < deadline) { "Harness timeout" }
                SystemClock.sleep(150)
            }
            check(control("state").contains("success=true")) { control("state") }
        }
    }

    private fun search(distinct: Boolean) {
        tap("nav_search")
        val target = element("search_input")
        if (distinct) {
            for (text in listOf("Tin", "Tin ", " Tin ", "Tin")) {
                step("Nhập truy vấn [$text]", target.visibleCenter) { target.text = text }
                SystemClock.sleep(700)
            }
        } else {
            val center = target.visibleCenter
            step("Focus ô tìm kiếm", center) { target.click() }
            // Let the IME finish opening before starting the controlled typing burst.
            SystemClock.sleep(1000)
            var value = ""
            for (character in "00001") {
                value += character
                // No accessibility idle-wait per character: it could exceed the 300 ms debounce.
                step("Nhập [$value]", center) {
                    val events = requireNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(charArrayOf(character)))
                    // Queue real key events without waiting for rendering after each key.
                    // App query_input timestamps validate the actual delivered cadence.
                    events.forEach { check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(it, false)) }
                }
                SystemClock.sleep(100)
            }
        }
        device.pressBack()
        check(device.wait(Until.hasObject(By.res("search_results")), 15_000))
        val expected = if (distinct) "Tin" else "00001"
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!control("state").contains("ready_query=$expected")) {
            check(SystemClock.elapsedRealtime() < deadline) { "Final search result was not acknowledged" }
            SystemClock.sleep(100)
        }
    }

    private fun journey() {
        val kind = args.getString("journey", "scroll")
        val duration = args.getString("duration_ms", "20000").toLong()
        when (kind) {
            "dedup", "concurrency", "room", "schedule" -> harness(kind)
            "search" -> search(false)
            "distinct" -> search(true)
            "etag" -> {
                repeat(3) { tap("article_1"); element("detail_body"); tap("nav_feed"); element("article_1") }
            }
            "offline" -> {
                tap("article_1"); element("detail_body"); tap("save_button")
                // Runner blocks proxy, then acknowledges before Saved is opened.
                Log.i("AN_EVENT", JSONObject().put("run_id", trialId).put("event", "offline_block").toString())
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (!control("state").contains("offline_ack=true")) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Proxy offline acknowledgement timeout" }
                    SystemClock.sleep(100)
                }
                tap("nav_saved"); element("saved_list"); tap("article_1"); element("detail_body")
            }
            "context", "hysteresis" -> {
                tap("nav_lab")
                val samples = if (kind == "context") List(6) { "normal" } + List(2) { "critical" } + List(9) { "normal" }
                    else List(6) { "normal" } + List(10) { if (it % 2 == 0) "normal" else "moderate" } + listOf("critical") + List(9) { "normal" }
                samples.forEach { value -> step("Snapshot $value") { control("snapshot", value) }; SystemClock.sleep(5000) }
            }
            "background" -> { harness("schedule"); device.pressHome(); step("Quan sát đồng bộ nền 3 giờ") { SystemClock.sleep(duration) } }
            else -> {
                val deadline = SystemClock.elapsedRealtime()+duration
                var index = 0
                while (SystemClock.elapsedRealtime() < deadline) {
                    swipe(kind != "revisit" || (index/5)%2 == 0)
                    SystemClock.sleep(700)
                    index++
                }
                if (kind == "refresh") step("Refresh feed hiện tại") { control("refresh"); element("feed_list") }
                if (kind == "overall") { tap("nav_search"); search(false); tap("nav_feed") }
            }
        }
    }
}
