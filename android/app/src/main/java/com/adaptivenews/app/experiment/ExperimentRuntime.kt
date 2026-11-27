package com.adaptivenews.app.experiment

import com.adaptivenews.app.ui.AppViewModel
import com.adaptivenews.app.telemetry.Telemetry

/** Set only during an active benchmark Activity. */
object ExperimentRuntime {
    val compositions = java.util.concurrent.atomic.AtomicLong(0)
    var telemetry: Telemetry? = null
    var viewModel: AppViewModel? = null
    @Volatile var harnessBusy = false
    @Volatile var harnessSuccess = false
    @Volatile var harnessError: String? = null
    @Volatile var offlineAcknowledged = false
    @Volatile var readyQuery: String? = null
}
