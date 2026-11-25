package com.adaptivenews.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.adaptivenews.app.core.contract.PolicyProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONException

internal fun scheduleNewsSync(
    context: Context,
    policies: PolicyProvider,
    scope: CoroutineScope,
) = scope.launch {
    val manager = WorkManager.getInstance(context)
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    policies.policy.map { it.syncMinutes }.distinctUntilChanged().collect { minutes ->
        val request = PeriodicWorkRequestBuilder<NewsSyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork("news-feed-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

internal class NewsSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: RemoteNewsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        repository.syncFirstBatch()
        Result.success()
    } catch (failure: NewsHttpException) {
        if (failure.status >= 500) retryBounded() else Result.failure()
    } catch (_: OfflineException) {
        Result.success()
    } catch (_: IOException) {
        retryBounded()
    } catch (_: JSONException) {
        Result.failure()
    }

    private fun retryBounded() = if (runAttemptCount < 2) Result.retry() else Result.failure()
}

class NewsSyncWorkerFactory internal constructor(
    private val repository: RemoteNewsRepository,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == NewsSyncWorker::class.java.name) {
        NewsSyncWorker(appContext, workerParameters, repository)
    } else null
}
