package com.example.goforitGit.core.util.Receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.goforitGit.core.service.StepService
import com.example.goforitGit.core.util.FourHourBuckets.FourHourBucketsSinceBoot
import com.example.goforitGit.core.util.FourHourBuckets.FourHourUploadScheduler
import java.time.ZoneId
import java.time.ZonedDateTime

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        var action = intent.action ?: return
        val appCtx = context.applicationContext

        val isBootEvent =
            action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                    action == Intent.ACTION_USER_UNLOCKED

        if (!isBootEvent) return

        // Start StepService (so buckets update continuously)
        val stepsIntent = Intent(appCtx, StepService::class.java).apply {
            action = StepService.Companion.ACTION_START_FGS
        }
        runCatching { ContextCompat.startForegroundService(appCtx, stepsIntent) }

        // Schedule next boundary upload
        FourHourUploadScheduler.scheduleNext(appCtx)

        // YOUR POLICY: if boot happens after finalize (00:30), drop yesterday locally and start today fresh.
        // We use a conservative "after 01:00" heuristic (finalizeDay already ran).
        val zone = ZoneId.of("Asia/Jerusalem")
        val now = ZonedDateTime.now(zone)
        if (now.hour >= 1) {
            val buckets = FourHourBucketsSinceBoot(appCtx)
            buckets.forceStartTodayDropYesterday(now.toLocalDate().toString())

            // Clear sent-values cache (safe; prevents blocking uploads after reboot)
            appCtx.getSharedPreferences("four_hour_upload_sent_values", Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }
}