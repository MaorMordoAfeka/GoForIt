package com.example.goforitGit.core.util.FourHourBuckets

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.google.firebase.auth.FirebaseAuth
import java.time.ZoneId
import java.time.ZonedDateTime

class FourHourUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone = ZoneId.of("Asia/Jerusalem")

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            FourHourUploadScheduler.scheduleNext(applicationContext)
            return Result.success()
        }

        val now = ZonedDateTime.now(zone)
        val currentInterval = (now.hour / 4).coerceIn(0, 5)

        val buckets = FourHourBucketsSinceBoot(applicationContext)
        val sentPrefs = applicationContext.getSharedPreferences(SENT_PREFS, Context.MODE_PRIVATE)

        // 00:05: try to upload yesterday's bucket 5 if the phone is ON.
        // If phone was OFF overnight, this won't run (your chosen policy).
        if (currentInterval == 0) {
            val yesterdayKey = now.minusDays(1).toLocalDate().toString()
            val steps5 = buckets.getBucketsForDay(yesterdayKey)?.getOrNull(5)

            if (steps5 != null) {
                val sentKey = "sent_${yesterdayKey}_5"
                val lastSent = sentPrefs.getInt(sentKey, -1)

                if (steps5 != lastSent) {
                    val r = FirebaseServerApi.uploadStepIntervalResult(
                        dayKey = yesterdayKey,
                        intervalIndex = 5,
                        stepsTotal = steps5,              // ✅ FIXED
                        uploadIntervalIndex = 0,
                        attributedIntervalIndex = 5
                    )

                    if (r.isFailure) {
                        // If day is finalized, server may reject and that's OK per your approach.
                        val msg = r.exceptionOrNull()?.message ?: ""
                        Log.w("STEPS", "00:05 bucket5 upload failed: $msg")
                    } else {
                        sentPrefs.edit().putInt(sentKey, steps5).apply()
                    }
                }
            }

            FourHourUploadScheduler.scheduleNext(applicationContext)
            return Result.success()
        }

        // Normal boundary upload: upload the previous interval for TODAY
        val dayKey = now.toLocalDate().toString()
        val intervalToUpload = (currentInterval + 5) % 6

        val stepsTotal = buckets.getBucketsForDay(dayKey)?.getOrNull(intervalToUpload) ?: 0
        val sentKey = "sent_${dayKey}_$intervalToUpload"
        val lastSent = sentPrefs.getInt(sentKey, -1)

        if (stepsTotal != lastSent) {
            val r = FirebaseServerApi.uploadStepIntervalResult(
                dayKey = dayKey,
                intervalIndex = intervalToUpload,
                stepsTotal = stepsTotal,               // ✅ FIXED
                uploadIntervalIndex = currentInterval,
                attributedIntervalIndex = intervalToUpload
            )

            if (r.isFailure) {
                val msg = r.exceptionOrNull()?.message ?: ""
                Log.e("STEPS", "Upload failed day=$dayKey interval=$intervalToUpload: $msg", r.exceptionOrNull())
                return Result.retry()
            }

            sentPrefs.edit().putInt(sentKey, stepsTotal).apply()
        }

        FourHourUploadScheduler.scheduleNext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val SENT_PREFS = "four_hour_upload_sent_values"
    }
}