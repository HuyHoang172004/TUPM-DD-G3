package com.adaptivenews.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.adaptivenews.app.BuildConfig
import com.adaptivenews.app.adaptive.PolicyController
import com.adaptivenews.app.core.contract.Variant
import com.adaptivenews.app.platform.AndroidResourceMonitor
import com.adaptivenews.app.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var monitor: AndroidResourceMonitor
    private lateinit var telemetry: Telemetry
    private lateinit var controller: PolicyController
    private lateinit var viewModel: AppViewModel
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) lifecycleScope.launch { telemetry.export(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("lab", MODE_PRIVATE)
        val benchmark = BuildConfig.BUILD_TYPE == "benchmark"
        val variant = (if (benchmark) intent.getStringExtra("variant") else null)
            ?.let { runCatching { Variant.valueOf(it) }.getOrNull() }
            ?: runCatching { Variant.valueOf(prefs.getString("variant", "V2")!!) }.getOrDefault(Variant.V2)
        val runId = (if (benchmark) intent.getStringExtra("run_id") else null)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
            ?: "local-${UUID.randomUUID()}"
        val scenarioId = (if (benchmark) intent.getStringExtra("scenario_id") else null)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: "manual"
        telemetry = Telemetry(applicationContext, ProcessTelemetryScope.scope, runId, scenarioId, variant)
        monitor = AndroidResourceMonitor(applicationContext, activityScope)
        controller = PolicyController(monitor, telemetry, activityScope, variant)
        val repository = RepositoryBinding.factory(applicationContext, controller, monitor)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(repository, controller, telemetry, variant, RepositoryBinding.isFixture) as T
        }
        viewModel = ViewModelProvider(this, factory)[AppViewModel::class.java]
        telemetry.record("run_start", attrs = mapOf(
            "commit" to null, "config_hash" to null,
            "reset_mode" to "manual", "status" to "started",
            "dataset" to if (RepositoryBinding.isFixture) "ui_fixture_30" else "backend",
            "started_utc" to Instant.now().toString()
        ))
        setContent {
            AdaptiveNewsApp(
                viewModel = viewModel,
                onVariantSelected = { selected ->
                    prefs.edit().putString("variant", selected.name).apply()
                    startActivity(Intent(this, MainActivity::class.java).putExtra("variant", selected.name))
                    finish()
                },
                onExport = { exportLauncher.launch("adaptive-news-$runId.jsonl") },
                onResetImageCache = { lifecycleScope.launch(Dispatchers.IO) { ImageLoaderHolder.clear() } },
                onResetAllData = {
                    lifecycleScope.launch {
                        RepositoryBinding.resetData(repository)
                        kotlinx.coroutines.withContext(Dispatchers.IO) { ImageLoaderHolder.clear() }
                        viewModel.refresh()
                    }
                }
            )
        }
    }

    override fun onStart() { super.onStart(); controller.setForeground(true); monitor.start() }
    override fun onStop() { controller.setForeground(false); monitor.stop(); super.onStop() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (::monitor.isInitialized) monitor.onTrimMemory(level) }
    override fun onDestroy() {
        telemetry.record("run_end", attrs = mapOf("status" to "finished", "ended_utc" to Instant.now().toString()))
        activityScope.cancel()
        super.onDestroy()
    }
}

private object ProcessTelemetryScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
