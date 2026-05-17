package com.example.goforitGit.core.util.Receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.example.goforitGit.core.util.FourHourBuckets.FourHourBucketsSinceBoot
import com.example.goforitGit.core.util.FourHourBuckets.FourHourUploadScheduler
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingHeartbeat
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingPrefs
import com.example.goforitGit.tracking_lifecycle.TrackingHealthWorker
import com.example.goforitGit.tracking_lifecycle.TrackingRestartWorker
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reacts to device reboot and app update.
 *
 * Why this is so short now:
 *  - Direct-boot handling (LOCKED_BOOT_COMPLETED / USER_UNLOCKED) was removed.
 *    Step counting and Firebase calls both need credential-protected storage
 *    and the user account, so they can't usefully run before the device is
 *    unlocked anyway.
 *  - Service restart is delegated to TrackingRestartWorker (foreground-elevated)
 *    instead of being attempted directly here. This unifies the boot path
 *    with the periodic-watchdog recovery path, and avoids subtle differences
 *    in what's allowed from a BroadcastReceiver vs a Worker context.
 *
 * IMPORTANT: corresponding manifest changes:
 *   - remove android:directBootAware="true" from this receiver
 *   - remove LOCKED_BOOT_COMPLETED and USER_UNLOCKED from its intent-filter
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext

        val isRelevant =
            action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!isRelevant) return

        Log.i(TAG, "Received restart event: $action")

        // Clear the stale heartbeat from the previous boot. If we don't, the
        // periodic health check might briefly think the service is alive.
        TrackingHeartbeat.clear(appContext)

        // Periodic liveness check, re-armed on every boot / update.
        TrackingHealthWorker.schedule(appContext)

        // Re-arm the boundary upload chain.
        FourHourUploadScheduler.scheduleNext(appContext)

        // Bring the services back up if the user had them on.
        // We go through the worker (not ContextCompat.startForegroundService
        // here) so that the exact same path is used for boot recovery and
        // for mid-day watchdog recovery, and so we benefit from foreground
        // elevation in any edge case where the platform tightens FGS-start
        // rules at boot.
        if (TrackingPrefs.isTrackingEnabled(appContext)) {
            TrackingRestartWorker.enqueue(appContext)
        } else {
            Log.d(TAG, "Tracking is disabled. Skipping service restart.")
        }

        // Policy: if boot happens after the daily finalize window (00:30),
        // drop yesterday's local buckets and start today fresh. Conservative
        // threshold of >= 01:00.
        val zone = ZoneId.of("Asia/Jerusalem")
        val now = ZonedDateTime.now(zone)
        if (now.hour >= 1) {
            val buckets = FourHourBucketsSinceBoot(appContext)
            buckets.forceStartTodayDropYesterday(now.toLocalDate().toString())

            appContext.getSharedPreferences(
                "four_hour_upload_sent_values",
                Context.MODE_PRIVATE
            ).edit { clear() }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}