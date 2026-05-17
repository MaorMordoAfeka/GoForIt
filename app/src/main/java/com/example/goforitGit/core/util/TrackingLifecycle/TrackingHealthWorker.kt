package com.example.goforitGit.tracking_lifecycle

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingHeartbeat
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingPrefs
import java.util.concurrent.TimeUnit

/**
 * Periodic liveness check.
 *
 * This worker is intentionally lightweight: it does NOT try to start
 * a foreground service itself, because that throws on Android 12+
 * outside of a foreground-elevated context.
 *
 * What it does:
 *  1) If tracking is disabled, do nothing.
 *  2) Read the heartbeat that StepService writes every ~30s.
 *  3) If the heartbeat is stale (or missing), enqueue TrackingRestartWorker,
 *     which is the only thing in this app that can reliably restart an FGS
 *     from background.
 *
 * Period is 15 minutes (WorkManager minimum). So the worst-case latency
 * from "service died" to "service restarted" is about that window.
 * That's acceptable for step counting — the cumulative step counter
 * keeps incrementing in the OS sensor stack, and FourHourBucketsSinceBoot
 * correctly attributes the catch-up delta when the service comes back.
 */
class TrackingHealthWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!TrackingPrefs.isTrackingEnabled(applicationContext)) {
                Log.d(TAG, "Tracking disabled; skipping health check.")
                return Result.success()
            }

            val alive = TrackingHeartbeat.isStepServiceAlive(
                context = applicationContext,
                maxAgeMs = MAX_HEARTBEAT_AGE_MS
            )

            if (!alive) {
                Log.i(TAG, "Step service heartbeat stale; enqueueing restart.")
                TrackingRestartWorker.enqueue(applicationContext)
            } else {
                Log.d(TAG, "Step service heartbeat OK.")
            }

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Tracking health check failed.", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TrackingHealthWorker"
        private const val UNIQUE_WORK_NAME = "tracking_health_worker"

        /**
         * Heartbeat is written every ~30s. Allow generous slack
         * (Doze mode, low-priority scheduling) before declaring it dead.
         */
        private const val MAX_HEARTBEAT_AGE_MS = 3 * 60_000L

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<TrackingHealthWorker>(
                    15, TimeUnit.MINUTES
                )
                    .addTag(UNIQUE_WORK_NAME)
                    .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}