package com.example.goforitGit.core.data.StepsData

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Per-day step-total history.
 *
 * The four-hour buckets only keep today + yesterday, and the raw timestamp
 * list is pruned to ~3 days, so neither can answer "best day this week" or
 * "personal best". This store records one number per calendar day (the day's
 * step total) and keeps a rolling window, so those stats become accurate as
 * days accumulate.
 *
 * A day's value is monotonic within the day (it only grows), so recording the
 * current day total repeatedly is safe — we keep the maximum seen.
 */
class DailyStepsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val zone = ZoneId.of(TZ)

    data class DayTotal(val date: LocalDate, val steps: Int)

    fun todayKey(): String = LocalDate.now(zone).toString()

    /** Record [dayTotalSteps] for [dayKey] (keeps the max), then prune old days. */
    fun record(dayKey: String, dayTotalSteps: Int) {
        if (dayTotalSteps <= 0) return
        val existing = prefs.getInt(dayKey, 0)
        if (dayTotalSteps > existing) {
            prefs.edit().putInt(dayKey, dayTotalSteps).apply()
        }
        prune()
    }

    private fun parseEntries(): List<DayTotal> {
        return prefs.all.mapNotNull { (key, value) ->
            val steps = (value as? Int) ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            DayTotal(date, steps)
        }.sortedBy { it.date }
    }

    /** All recorded days, oldest first. */
    fun history(): List<DayTotal> = parseEntries()

    /** Highest single-day total ever recorded. */
    fun personalBest(): DayTotal? = parseEntries().maxByOrNull { it.steps }

    /** Highest single-day total within the last [days] calendar days (inclusive). */
    fun bestDayInLast(days: Int): DayTotal? {
        val cutoff = LocalDate.now(zone).minusDays((days - 1).toLong())
        return parseEntries()
            .filter { !it.date.isBefore(cutoff) }
            .maxByOrNull { it.steps }
    }

    private fun prune() {
        val today = LocalDate.now(zone)
        val stale = prefs.all.keys.filter { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@filter true
            ChronoUnit.DAYS.between(date, today) > KEEP_DAYS
        }
        if (stale.isNotEmpty()) {
            prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        }
    }

    companion object {
        private const val PREFS = "daily_steps_history"
        private const val TZ = "Asia/Jerusalem"
        private const val KEEP_DAYS = 60L
    }
}
