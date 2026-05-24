package com.example.goforitGit.tracking_lifecycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.goforitGit.R
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingPrefs
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingServiceManager

/**
 * One-shot worker that brings tracking services back up from background.
 *
 * Why this exists:
 *  - On Android 12+ (API 31+), starting a foreground service from a plain
 *    background context (e.g. a periodic worker, or after a transient
 *    in-service failure) throws ForegroundServiceStartNotAllowedException.
 *  - A WorkManager worker that calls setForeground(...) runs as a
 *    short-lived foreground service. From that foreground context, starting
 *    another FGS is allowed.
 *
 * Flow:
 *  - enqueue() -> system runs the worker
 *  - getForegroundInfo() posts a brief "Reactivating tracking" notification
 *  - doWork() calls TrackingServiceManager.ensureTrackingRunning, which
 *    starts StepService and BleAdvertScanService
 *  - worker exits, its notification disappears, the two services keep
 *    running on their own FGS notifications
 *
 * Notes:
 *  - Uses FOREGROUND_SERVICE_TYPE_DATA_SYNC because that's already in your
 *    manifest. The worker only needs FGS status for a few milliseconds.
 *  - Unique work name with KEEP policy: rapid repeat boots / repeated
 *    health-check failures won't pile up duplicate workers.
 *  - setExpedited with RUN_AS_NON_EXPEDITED_WORK_REQUEST fallback so the
 *    worker still runs even if the system declines expedited quota.
 */
class TrackingRestartWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createChannelIfNeeded(applicationContext)

        val notification: Notification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Reactivating GoForIt tracking")
                .setContentText("Restoring step counter and BLE scanner")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: foreground type is required for setForeground calls.
            ForegroundInfo(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return try {
            if (!TrackingPrefs.isTrackingEnabled(applicationContext)) {
                Log.d(TAG, "Tracking disabled; nothing to restart.")
                return androidx.work.ListenableWorker.Result.success()
            }

            // Elevate this worker to a foreground context so the
            // ContextCompat.startForegroundService calls below are allowed.
            setForeground(getForegroundInfo())

            TrackingServiceManager.ensureTrackingRunning(applicationContext)

            // Give the services a brief moment to actually promote themselves
            // before we tear down our own foreground notification.
            kotlinx.coroutines.delay(1_500L)

            androidx.work.ListenableWorker.Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "TrackingRestartWorker failed.", t)
            androidx.work.ListenableWorker.Result.retry()
        }
    }

    companion object {
        private const val TAG = "TrackingRestartWorker"
        private const val NOTIF_ID = 60042
        private const val CHANNEL_ID = "tracking_restart_channel"
        private const val UNIQUE_WORK_NAME = "tracking_restart_worker"

        /**
         * Enqueue a restart attempt. KEEP policy means a queued restart is
         * preserved if you call enqueue() multiple times in quick succession.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrackingRestartWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(UNIQUE_WORK_NAME)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }

        private fun createChannelIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager ?: return

            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Tracking restart",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description =
                            "Briefly shown while step counter and BLE scanner are restored."
                    }
                )
            }
        }
    }
}