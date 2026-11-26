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
    private var runIdentity: Map<String, Any?> = emptyMap()
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) lifecycleScope.launch { telemetry.export(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("lab", MODE_PRIVATE)
        val benchmark = BuildConfig.BUILD_TYPE == "benchmark"
        val variantExtra = if (benchmark) intent.getStringExtra("variant") else null
        val runExtra = if (benchmark) intent.getStringExtra("run_id") else null
        val scenarioExtra = if (benchmark) intent.getStringExtra("scenario_id") else null
        val automatedRun = benchmark &&
            (variantExtra != null || runExtra != null || scenarioExtra != null)
        val safeId = Regex("[A-Za-z0-9_-]{1,64}")
        if (automatedRun) {
            require(Variant.entries.any { it.name == variantExtra }) { "Invalid benchmark variant" }
            require(runExtra != null && safeId.matches(runExtra)) { "Invalid benchmark run_id" }
            require(scenarioExtra != null && safeId.matches(scenarioExtra)) { "Invalid benchmark scenario_id" }
        }
        val variant = if (automatedRun) Variant.valueOf(requireNotNull(variantExtra)) else
            runCatching { Variant.valueOf(prefs.getString("variant", "V2")!!) }
                .getOrDefault(Variant.V2)
        if (benchmark) prefs.edit().putString("variant", variant.name).apply()
        val runId = if (automatedRun) requireNotNull(runExtra) else "local-${UUID.randomUUID()}"
        val scenarioId = if (automatedRun) requireNotNull(scenarioExtra) else "manual"
        fun metadataExtra(name: String): String? {
            val value = if (automatedRun) intent.getStringExtra(name) else null
            require(value == null || safeId.matches(value)) { "Invalid benchmark $name" }
            return value
        }
        runIdentity = mapOf(
            "commit" to metadataExtra("commit"),
            "config_hash" to metadataExtra("config_hash"),
            "reset_mode" to (metadataExtra("reset_mode") ?: if (automatedRun) null else "manual"),
        )
        telemetry = Telemetry(applicationContext, ProcessTelemetryScope.scope, runId, scenarioId, variant)
        monitor = AndroidResourceMonitor(applicationContext, activityScope)
        controller = PolicyController(monitor, telemetry, activityScope, variant)
        val newsData = RepositoryBinding.bind(applicationContext, controller, monitor, telemetry, activityScope)
        newsData.resetPrefetchUsage()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(newsData, controller, telemetry, variant) as T
        }
        viewModel = ViewModelProvider(this, factory)[AppViewModel::class.java]
        telemetry.record("run_start", attrs = runIdentity + mapOf(
            "status" to "started",
            "dataset" to "backend",
            "started_utc" to Instant.now().toString()
        ))
        setContent {
            AdaptiveNewsApp(
                viewModel = viewModel,
                onVariantSelected = { selected ->
                    prefs.edit().putString("variant", selected.name).apply()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                },
                onExport = { exportLauncher.launch("adaptive-news-$runId.jsonl") },
                onResetImageCache = {
                    lifecycleScope.launch {
                        viewModel.stopPrefetch()
                        try {
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                ImageLoaderHolder.clear()
                                newsData.clearHttpCache()
                            }
                        } finally {
                            viewModel.restartPrefetch()
                        }
                    }
                },
                onResetAllData = {
                    lifecycleScope.launch {
                        viewModel.stopPrefetch()
                        viewModelStore.clear()
                        RepositoryBinding.resetAll(applicationContext)
                        kotlinx.coroutines.withContext(Dispatchers.IO) { ImageLoaderHolder.reset() }
                        startActivity(Intent(this@MainActivity, MainActivity::class.java))
                        finish()
                    }
                }
            )
        }
    }

    override fun onStart() { super.onStart(); controller.setForeground(true); monitor.start() }
    override fun onStop() { controller.setForeground(false); monitor.stop(); super.onStop() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (::monitor.isInitialized) monitor.onTrimMemory(level) }
    override fun onDestroy() {
        telemetry.record("run_end", attrs = runIdentity + mapOf(
            "status" to "finished", "ended_utc" to Instant.now().toString()))
        RepositoryBinding.unbind(telemetry)
        activityScope.cancel()
        super.onDestroy()
    }
}

private object ProcessTelemetryScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
