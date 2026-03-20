package com.example.goforitGit.core.util.FourHourBuckets

import android.content.Context
import androidx.work.*
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object FourHourUploadScheduler {

    private val zone = ZoneId.of("Asia/Jerusalem")

    fun scheduleNext(context: Context) {
        val now = ZonedDateTime.now(zone)
        val currentSlotStartHour = (now.hour / 4) * 4
        val nextBoundary = now
            .withHour(currentSlotStartHour)
            .withMinute(0).withSecond(0).withNano(0)
            .plusHours(4)
            .plusMinutes(5)

        val delayMs = Duration.between(now, nextBoundary).toMillis().coerceAtLeast(0)

        val boundaryKey = "%s_%02d%02d".format(
            nextBoundary.toLocalDate().toString(),
            nextBoundary.hour,
            nextBoundary.minute
        )
        val uniqueName = "four_hour_bucket_upload_$boundaryKey"

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<FourHourUploadWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("four_hour_bucket_upload")
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
    }
}