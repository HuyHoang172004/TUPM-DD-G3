package com.adaptivenews.app.ui

import android.content.Context
import android.os.Build
import androidx.work.WorkManager
import com.adaptivenews.app.adaptive.AdaptiveEngine
import com.adaptivenews.app.adaptive.PolicyController
import com.adaptivenews.app.core.contract.Policy
import com.adaptivenews.app.core.contract.PolicyProvider
import com.adaptivenews.app.core.contract.ResourceSnapshot
import com.adaptivenews.app.core.contract.MemoryPressure
import com.adaptivenews.app.core.contract.Thermal
import com.adaptivenews.app.core.contract.LinkQuality
import com.adaptivenews.app.core.contract.Variant
import com.adaptivenews.app.data.DataTelemetry
import com.adaptivenews.app.data.NewsData
import com.adaptivenews.app.platform.ArticleRequestSignal
import com.adaptivenews.app.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** One data graph per process; each Activity binds its current policy and run telemetry. */
object RepositoryBinding {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var policyState: MutableStateFlow<Policy>
    private val policyProvider = object : PolicyProvider {
        override val policy: StateFlow<Policy> get() = policyState
    }
    private var graph: NewsData? = null
    private var syncJob: Job? = null
    private var policyJob: Job? = null
    @Volatile private var currentTelemetry: Telemetry? = null
    @Volatile private var currentController: PolicyController? = null
    @Volatile private var articleSignal: ArticleRequestSignal? = null

    @Synchronized
    fun bind(
        context: Context,
        controller: PolicyController,
        signal: ArticleRequestSignal,
        telemetry: Telemetry,
        activityScope: CoroutineScope,
    ): NewsData {
        policyJob?.cancel()
        if (!::policyState.isInitialized) policyState = MutableStateFlow(controller.policy.value)
        policyState.value = controller.policy.value
        currentTelemetry = telemetry
        currentController = controller
        articleSignal = signal
        policyJob = activityScope.launch { controller.policy.collect { policyState.value = it } }
        val bound = data(context)
        if (syncJob == null && com.adaptivenews.app.experiment.Experiment.bool("sync_enabled", true)) syncJob = bound.startSync(processScope)
        return bound
    }

    /** Also available when WorkManager starts the process without an Activity. */
    @Synchronized
    fun data(context: Context): NewsData {
        if (!::policyState.isInitialized) policyState = MutableStateFlow(initialPolicy(context))
        return graph ?: NewsData(
            context,
            (if (com.adaptivenews.app.experiment.Experiment.active) "http://10.0.2.2:8001/" else if (Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.FINGERPRINT.contains("generic", ignoreCase = true))
                "http://10.0.2.2:8000/" else "http://127.0.0.1:8000/").toHttpUrl(),
            policyProvider,
            DataTelemetry { event, value, unit, attrs ->
                currentTelemetry?.record(
                    event, mode = currentController?.policy?.value?.mode?.name,
                    source = currentController?.source?.value ?: "real",
                    value = value, unit = unit, attrs = attrs,
                )
            },
            onArticleNetworkSuccess = { duration -> articleSignal?.successfulRequest(duration) },
        ).also { graph = it }
    }

    private fun initialPolicy(context: Context): Policy {
        val selected = context.getSharedPreferences("lab", Context.MODE_PRIVATE)
            .getString("variant", "V2")
            ?.let { runCatching { Variant.valueOf(it) }.getOrNull() } ?: Variant.V2
        return AdaptiveEngine(selected).evaluate(ResourceSnapshot(
            0, MemoryPressure.UNKNOWN, null, null, Thermal.UNKNOWN, LinkQuality.UNKNOWN, null,
        ))
    }

    fun unbind(telemetry: Telemetry) {
        if (currentTelemetry === telemetry) {
            policyJob?.cancel()
            policyJob = null
            currentTelemetry = null
            currentController = null
            articleSignal = null
        }
    }

    suspend fun resetAll(context: Context) {
        val old = synchronized(this) {
            policyJob?.cancel()
            policyJob = null
            syncJob?.cancel()
            syncJob = null
            graph.also { graph = null }
        }
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("news-feed-sync").result.get()
        }
        old?.clearHttpCache()
        old?.close()
        context.applicationContext.deleteDatabase("news.db")
    }
}
