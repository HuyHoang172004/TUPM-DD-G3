package com.adaptivenews.app.platform

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.adaptivenews.app.core.contract.LinkQuality
import com.adaptivenews.app.core.contract.MemoryPressure
import com.adaptivenews.app.core.contract.ResourceMonitor
import com.adaptivenews.app.core.contract.ResourceSnapshot
import com.adaptivenews.app.core.contract.Thermal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Foreground-only sampler. Successful article request durations feed the network EWMA. */
class AndroidResourceMonitor(
    context: Context,
    private val scope: CoroutineScope
) : ResourceMonitor, ArticleRequestSignal {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val events = MutableSharedFlow<ResourceSnapshot>(replay = 1, extraBufferCapacity = 2)
    override val snapshots = events
    private var job: Job? = null
    private var registered = false
    private var previousMemory = MemoryPressure.NORMAL
    private var forcedCritical = false
    private var ewmaMs: Double? = null
    private var requestSamples = 0
    private var previousConnectedLink = LinkQuality.UNKNOWN
    private var previousCpuMs = 0L
    private var previousElapsedMs = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { sampleAsync() }
        override fun onLost(network: Network) { sampleAsync() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { sampleAsync() }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { sampleAsync() }
    }

    fun start() {
        if (job != null) return
        if (!registered) {
            runCatching {
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    networkCallback
                )
                appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                registered = true
            }
        }
        job = scope.launch {
            while (true) {
                events.emit(readSnapshot())
                delay(5_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (registered) {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            runCatching { appContext.unregisterReceiver(batteryReceiver) }
            registered = false
        }
    }

    fun onTrimMemory(level: Int) {
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            forcedCritical = true
            sampleAsync()
        }
    }

    /** Invoke for successful article API requests only; excludes image decode latency. */
    override fun successfulRequest(durationMs: Long) {
        if (durationMs < 0) return
        scope.launch {
            ewmaMs = ewmaMs?.let { 0.3 * durationMs + 0.7 * it } ?: durationMs.toDouble()
            requestSamples++
            sampleAsync()
        }
    }

    private fun sampleAsync() { if (job != null) scope.launch { events.emit(readSnapshot()) } }

    private fun readSnapshot(): ResourceSnapshot {
        val now = SystemClock.elapsedRealtime()
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val heapRatio = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / runtime.maxMemory()
        val memory = when {
            memoryInfo.lowMemory || forcedCritical || heapRatio >= 0.85 -> MemoryPressure.CRITICAL
            previousMemory == MemoryPressure.CRITICAL && heapRatio >= 0.80 -> MemoryPressure.CRITICAL
            heapRatio >= 0.70 || previousMemory == MemoryPressure.MODERATE && heapRatio >= 0.65 -> MemoryPressure.MODERATE
            else -> MemoryPressure.NORMAL
        }
        previousMemory = memory
        forcedCritical = false

        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val network = connectivity.activeNetwork
        val hasNetwork = network != null && connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val link = when {
            !hasNetwork -> LinkQuality.OFFLINE
            requestSamples < 3 -> LinkQuality.UNKNOWN
            (ewmaMs ?: 0.0) > 800.0 -> LinkQuality.SLOW
            (ewmaMs ?: 0.0) <= 400.0 -> LinkQuality.FAST
            else -> previousConnectedLink
        }
        if (link == LinkQuality.FAST || link == LinkQuality.SLOW) previousConnectedLink = link
        val thermal = if (Build.VERSION.SDK_INT < 29) Thermal.UNKNOWN else when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> Thermal.SEVERE
            PowerManager.THERMAL_STATUS_MODERATE -> Thermal.MODERATE
            else -> Thermal.NORMAL
        }
        val cpuMs = Process.getElapsedCpuTime()
        val elapsedDelta = now - previousElapsedMs
        val cpuPct = if (previousElapsedMs > 0 && elapsedDelta > 0)
            (cpuMs - previousCpuMs).toDouble() / elapsedDelta * 100.0 / Runtime.getRuntime().availableProcessors()
        else null
        previousCpuMs = cpuMs
        previousElapsedMs = now
        return ResourceSnapshot(
            now, memory, batteryPct, power.isPowerSaveMode, thermal, link,
            if (hasNetwork) connectivity.isActiveNetworkMetered else null, cpuPct
        )
    }
}
