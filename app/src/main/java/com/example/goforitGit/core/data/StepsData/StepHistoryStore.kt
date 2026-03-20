package com.example.goforitGit.core.data.StepsData

import android.content.Context

/**
 * Single source of truth for StepCounterZC persistence.
 *
 * Storage: SharedPreferences("stepzc_prefs")
 *
 * Keys:
 *  - stepTimesCsv  : comma-separated epoch millis (legacy; may be absent if you persist totals only)
 *  - recentStepsCsv: semicolon-separated items, each "timeMs:periodS:vRatio:amp"
 *  - lastSaveMs    : epoch millis of last write
 *  - totalSteps    : persisted total step count
 *
 * Additional keys for college-area step bonus sync:
 *  - collegeBonusDayKey                  : YYYY-MM-DD for the currently tracked bonus day
 *  - collegeQualifiedStepsToday          : locally qualified campus steps counted for that day
 *  - collegeLastSyncedQualifiedStepsToday: last qualified total successfully accepted by the server
 */
class StepHistoryStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "stepzc_prefs"

        private const val K_STEP_TIMES = "stepTimesCsv"
        private const val K_RECENT = "recentStepsCsv"
        private const val K_LAST_SAVE = "lastSaveMs"
        private const val K_TOTAL_STEPS = "totalSteps"

        private const val K_COLLEGE_BONUS_DAY_KEY = "collegeBonusDayKey"
        private const val K_COLLEGE_QUALIFIED_STEPS = "collegeQualifiedStepsToday"
        private const val K_COLLEGE_LAST_SYNCED_STEPS = "collegeLastSyncedQualifiedStepsToday"
    }

    /** Represents a single step record with timing and sensor features */
    data class RecentStep(
        val timeMs: Long,
        val periodS: Float,
        val vRatio: Float,
        val amp: Float
    )

    /**
     * Client-side progress for the college-area bonus flow.
     *
     * [qualifiedSteps] is the cumulative number of steps that were accepted locally
     * as "inside college polygon" for the given [dayKey].
     *
     * [lastSyncedQualifiedSteps] is the last cumulative total that the server accepted.
     */
    data class CollegeBonusProgress(
        val dayKey: String,
        val qualifiedSteps: Int,
        val lastSyncedQualifiedSteps: Int
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Loads step timestamps from CSV string */
    fun loadStepTimestamps(): List<Long> {
        return prefs.getString(K_STEP_TIMES, null)
            ?.takeIf { it.isNotBlank() }
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()
    }

    /** Loads recent step features from semicolon-separated format */
    fun loadRecentStepFeatures(): List<RecentStep> {
        return prefs.getString(K_RECENT, null)
            ?.takeIf { it.isNotBlank() }
            ?.split(';')
            ?.mapNotNull { item -> parseRecentStep(item) }
            ?: emptyList()
    }

    /** Loads total step count, or null if not yet persisted */
    fun loadTotalSteps(): Int? {
        val v = prefs.getInt(K_TOTAL_STEPS, -1)
        return if (v >= 0) v else null
    }

    /** Loads the timestamp of the last save operation */
    fun loadLastSaveMs(): Long? {
        val v = prefs.getLong(K_LAST_SAVE, -1L)
        return if (v >= 0) v else null
    }

    /**
     * Loads the persisted college bonus progress for [todayKey].
     *
     * If the stored day does not match [todayKey], a reset-for-today snapshot is returned.
     * This keeps the bonus counter strictly day-bound without disturbing older fields.
     */
    fun loadCollegeBonusProgress(todayKey: String): CollegeBonusProgress {
        val savedDayKey = prefs.getString(K_COLLEGE_BONUS_DAY_KEY, null)
        if (savedDayKey != todayKey) {
            return CollegeBonusProgress(
                dayKey = todayKey,
                qualifiedSteps = 0,
                lastSyncedQualifiedSteps = 0
            )
        }

        val qualified = prefs.getInt(K_COLLEGE_QUALIFIED_STEPS, 0).coerceAtLeast(0)
        val synced = prefs.getInt(K_COLLEGE_LAST_SYNCED_STEPS, 0).coerceIn(0, qualified)

        return CollegeBonusProgress(
            dayKey = todayKey,
            qualifiedSteps = qualified,
            lastSyncedQualifiedSteps = synced
        )
    }

    /**
     * Persists an explicit "today" snapshot if the stored day is stale or missing.
     */
    fun ensureCollegeBonusDay(todayKey: String, nowMs: Long = System.currentTimeMillis()): CollegeBonusProgress {
        val current = loadCollegeBonusProgress(todayKey)
        val storedDayKey = prefs.getString(K_COLLEGE_BONUS_DAY_KEY, null)

        if (storedDayKey == todayKey) {
            return current
        }

        prefs.edit()
            .putString(K_COLLEGE_BONUS_DAY_KEY, todayKey)
            .putInt(K_COLLEGE_QUALIFIED_STEPS, 0)
            .putInt(K_COLLEGE_LAST_SYNCED_STEPS, 0)
            .putLong(K_LAST_SAVE, nowMs)
            .apply()

        return CollegeBonusProgress(
            dayKey = todayKey,
            qualifiedSteps = 0,
            lastSyncedQualifiedSteps = 0
        )
    }

    /**
     * Adds newly qualified college-area steps for [todayKey].
     */
    fun addCollegeQualifiedSteps(
        todayKey: String,
        delta: Int,
        nowMs: Long = System.currentTimeMillis()
    ): CollegeBonusProgress {
        require(delta >= 0) { "delta must be >= 0." }

        val current = ensureCollegeBonusDay(todayKey, nowMs)
        if (delta == 0) return current

        val updatedQualified = current.qualifiedSteps + delta
        val updated = CollegeBonusProgress(
            dayKey = todayKey,
            qualifiedSteps = updatedQualified,
            lastSyncedQualifiedSteps = current.lastSyncedQualifiedSteps.coerceAtMost(updatedQualified)
        )

        prefs.edit()
            .putString(K_COLLEGE_BONUS_DAY_KEY, todayKey)
            .putInt(K_COLLEGE_QUALIFIED_STEPS, updated.qualifiedSteps)
            .putInt(K_COLLEGE_LAST_SYNCED_STEPS, updated.lastSyncedQualifiedSteps)
            .putLong(K_LAST_SAVE, nowMs)
            .apply()

        return updated
    }

    /**
     * Marks the locally counted college-area steps as synced up to [syncedQualifiedTotal].
     *
     * The value is clamped to the valid range [0 .. qualifiedSteps].
     */
    fun markCollegeQualifiedStepsSynced(
        todayKey: String,
        syncedQualifiedTotal: Int,
        nowMs: Long = System.currentTimeMillis()
    ): CollegeBonusProgress {
        val current = ensureCollegeBonusDay(todayKey, nowMs)

        val updated = current.copy(
            lastSyncedQualifiedSteps = syncedQualifiedTotal.coerceIn(0, current.qualifiedSteps)
        )

        prefs.edit()
            .putString(K_COLLEGE_BONUS_DAY_KEY, todayKey)
            .putInt(K_COLLEGE_QUALIFIED_STEPS, updated.qualifiedSteps)
            .putInt(K_COLLEGE_LAST_SYNCED_STEPS, updated.lastSyncedQualifiedSteps)
            .putLong(K_LAST_SAVE, nowMs)
            .apply()

        return updated
    }

    /**
     * Backward-compatible helper: returns only the periodS values from recent steps.
     *
     * @see loadRecentStepFeatures for full recent step records
     */
    @Deprecated("Use loadRecentStepFeatures() for full recent step records.")
    fun loadRecentSteps(): List<Float> = loadRecentStepFeatures().map { it.periodS }

    /**
     * Legacy writer for timestamp CSV. Not required if you persist totals only.
     * Provided to keep persistence responsibilities centralized.
     */
    fun saveStepTimestamps(timestamps: Collection<Long>) {
        val csv = if (timestamps.isEmpty()) "" else timestamps.joinToString(",")
        prefs.edit()
            .putString(K_STEP_TIMES, csv)
            .putLong(K_LAST_SAVE, System.currentTimeMillis())
            .apply()
    }

    /** Saves recent step features in semicolon-separated format */
    fun saveRecentStepFeatures(
        recent: Collection<RecentStep>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val recCsv = formatRecentStepsCsv(recent)
        prefs.edit()
            .putString(K_RECENT, recCsv)
            .putLong(K_LAST_SAVE, nowMs)
            .apply()
    }

    /** Saves total step count */
    fun saveTotalSteps(totalSteps: Int, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(K_TOTAL_STEPS, totalSteps)
            .putLong(K_LAST_SAVE, nowMs)
            .apply()
    }

    /**
     * Snapshot write: updates totalSteps + recentSteps + lastSaveMs together.
     *
     * Does not touch stepTimesCsv unless [timestamps] is provided,
     * to preserve current behavior if you persist totals only.
     */
    fun saveSnapshot(
        totalSteps: Int,
        recent: Collection<RecentStep>,
        timestamps: Collection<Long>? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val recCsv = formatRecentStepsCsv(recent)

        val editor = prefs.edit()
            .putInt(K_TOTAL_STEPS, totalSteps)
            .putString(K_RECENT, recCsv)
            .putLong(K_LAST_SAVE, nowMs)

        if (timestamps != null) {
            val tsCsv = if (timestamps.isEmpty()) "" else timestamps.joinToString(",")
            editor.putString(K_STEP_TIMES, tsCsv)
        }

        editor.apply()
    }

    /** Parses a single RecentStep from "timeMs:periodS:vRatio:amp" format */
    private fun parseRecentStep(item: String): RecentStep? {
        val parts = item.split(':')
        if (parts.size != 4) return null

        val timeMs = parts[0].toLongOrNull() ?: return null
        val periodS = parts[1].toFloatOrNull() ?: return null
        val vRatio = parts[2].toFloatOrNull() ?: return null
        val amp = parts[3].toFloatOrNull() ?: return null

        return RecentStep(timeMs, periodS, vRatio, amp)
    }

    /** Formats a collection of RecentSteps to semicolon-separated CSV */
    private fun formatRecentStepsCsv(recent: Collection<RecentStep>): String {
        return if (recent.isEmpty()) {
            ""
        } else {
            recent.joinToString(";") { "${it.timeMs}:${it.periodS}:${it.vRatio}:${it.amp}" }
        }
    }
}