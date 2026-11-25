package com.adaptivenews.app.platform

import com.adaptivenews.app.core.contract.ResourceMonitor
import com.adaptivenews.app.core.contract.ResourceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

class FakeResourceMonitor(initial: ResourceSnapshot) : ResourceMonitor {
    private val state = MutableStateFlow(initial)
    override val snapshots = state
    fun emit(snapshot: ResourceSnapshot) { state.value = snapshot }
}
