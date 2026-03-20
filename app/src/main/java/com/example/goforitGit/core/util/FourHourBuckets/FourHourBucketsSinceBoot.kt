package com.example.goforitGit.core.util.FourHourBuckets

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.max

class FourHourBucketsSinceBoot(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val zone = ZoneId.of(TZ)

    fun update(sensorStepsSinceBoot: Int, now: LocalDateTime = LocalDateTime.now(zone)) {
        val todayKey = now.toLocalDate().toString()
        val savedDay = prefs.getString(KEY_DAY, null)

        var lastSensor = prefs.getInt(KEY_LAST_SENSOR, sensorStepsSinceBoot)
        var correction = prefs.getInt(KEY_CORRECTION, 0)
        val lastEffective = prefs.getInt(KEY_LAST_EFFECTIVE, correction + lastSensor)

        // reboot / reset handling
        if (sensorStepsSinceBoot < lastSensor) {
            correction += lastSensor
            lastSensor = sensorStepsSinceBoot
        }

        val effective = correction + sensorStepsSinceBoot
        val delta = max(0, effective - lastEffective)

        // day rollover: snapshot yesterday buckets THEN reset current buckets for today
        if (savedDay != todayKey) {
            if (savedDay != null) snapshotDay(savedDay)

            prefs.edit().apply {
                putString(KEY_DAY, todayKey)
                for (i in 0..5) putInt(curKey(i), 0)

                putInt(KEY_LAST_SENSOR, sensorStepsSinceBoot)
                putInt(KEY_CORRECTION, correction)
                putInt(KEY_LAST_EFFECTIVE, effective)
            }.apply()

            // apply delta to today's slot
            if (delta > 0) {
                val slot = (now.hour / 4).coerceIn(0, 5)
                val cur = prefs.getInt(curKey(slot), 0)
                prefs.edit().putInt(curKey(slot), cur + delta).apply()
            }
            return
        }

        // same day update
        if (delta > 0) {
            val slot = (now.hour / 4).coerceIn(0, 5)
            val cur = prefs.getInt(curKey(slot), 0)
            prefs.edit().putInt(curKey(slot), cur + delta).apply()
        }

        prefs.edit().apply {
            putInt(KEY_LAST_SENSOR, sensorStepsSinceBoot)
            putInt(KEY_CORRECTION, correction)
            putInt(KEY_LAST_EFFECTIVE, effective)
        }.apply()
    }

    fun getTodayKey(): String = LocalDate.now(zone).toString()

    fun getBucketsForDay(dayKey: String): List<Int>? {
        val storedDay = prefs.getString(KEY_DAY, null)
        if (storedDay == dayKey) return List(6) { i -> prefs.getInt(curKey(i), 0) }

        val prevDay = prefs.getString(KEY_PREV_DAY, null)
        if (prevDay == dayKey && hasSnapshot(dayKey)) {
            return List(6) { i -> prefs.getInt(snapKey(dayKey, i), 0) }
        }
        return null
    }

    fun deleteSnapshot(dayKey: String) {
        if (!hasSnapshot(dayKey)) return
        prefs.edit().apply {
            for (i in 0..5) remove(snapKey(dayKey, i))
            remove(snapExistsKey(dayKey))
            val prev = prefs.getString(KEY_PREV_DAY, null)
            if (prev == dayKey) remove(KEY_PREV_DAY)
        }.apply()
    }

    /**
     * YOUR APPROACH: if device boots in the morning (e.g., 08:00), we drop yesterday locally and start today fresh.
     * We do not touch sensor baselines (lastSensor/lastEffective), we only reset the bucket day & arrays.
     */
    fun forceStartTodayDropYesterday(todayKey: String) {
        val storedDay = prefs.getString(KEY_DAY, null)
        if (storedDay == todayKey) return

        // delete snapshot if exists
        val prevDay = prefs.getString(KEY_PREV_DAY, null)
        if (prevDay != null) deleteSnapshot(prevDay)

        prefs.edit().apply {
            putString(KEY_DAY, todayKey)
            for (i in 0..5) putInt(curKey(i), 0)
        }.apply()
    }

    // ---- snapshot internals ----

    private fun snapshotDay(dayKey: String) {
        prefs.edit().apply {
            putString(KEY_PREV_DAY, dayKey)
            for (i in 0..5) putInt(snapKey(dayKey, i), prefs.getInt(curKey(i), 0))
            putBoolean(snapExistsKey(dayKey), true)
        }.apply()
    }

    private fun hasSnapshot(dayKey: String): Boolean =
        prefs.getBoolean(snapExistsKey(dayKey), false)

    private fun curKey(i: Int) = "cur_b$i"
    private fun snapKey(dayKey: String, i: Int) = "snap_${dayKey}_b$i"
    private fun snapExistsKey(dayKey: String) = "snap_${dayKey}_exists"

    companion object {
        private const val TZ = "Asia/Jerusalem"
        private const val PREFS = "step_buckets_boot"

        private const val KEY_DAY = "dayKey"
        private const val KEY_PREV_DAY = "prevDayKey"

        private const val KEY_LAST_SENSOR = "lastSensor"
        private const val KEY_CORRECTION = "correction"
        private const val KEY_LAST_EFFECTIVE = "lastEffective"
    }
}