package com.zaneschepke.wireguardautotunnel.cat.diagnostics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import timber.log.Timber

class CatDiagnosticsSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val coordinator: CatDiagnosticsSyncCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runCatching { coordinator.sync() }
            .onFailure { Timber.w("Cat diagnostics sync deferred: ${it.javaClass.simpleName}") }
            .fold(
                onSuccess = { Result.success() },
                onFailure = {
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                },
            )

    companion object {
        private const val WORK_NAME = "cat-diagnostics-sync"
        private const val MAX_RETRIES = 3

        fun schedule(context: Context) {
            val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request =
                PeriodicWorkRequestBuilder<CatDiagnosticsSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
