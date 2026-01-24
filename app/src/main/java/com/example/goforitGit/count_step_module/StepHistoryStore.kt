package com.example.goforitGit.count_step_module

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
 */
class StepHistoryStore(context: Context) {

    // ============================================================================
    // region COMPANION OBJECT - Constants & Keys
    // ============================================================================

    companion object {
        private const val PREFS_NAME = "stepzc_prefs"

        // SharedPreferences keys
        private const val K_STEP_TIMES = "stepTimesCsv"
        private const val K_RECENT = "recentStepsCsv"
        private const val K_LAST_SAVE = "lastSaveMs"
        private const val K_TOTAL_STEPS = "totalSteps"
    }

    // endregion

    // ============================================================================
    // region DATA CLASS
    // ============================================================================

    /** Represents a single step record with timing and sensor features */
    data class RecentStep(
        val timeMs: Long,
        val periodS: Float,
        val vRatio: Float,
        val amp: Float
    )

    // endregion

    // ============================================================================
    // region SHARED PREFERENCES
    // ============================================================================

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // endregion

    // ============================================================================
    // region LOAD OPERATIONS
    // ============================================================================

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
     * Backward-compatible helper: returns only the periodS values from recent steps.
     *
     * @see loadRecentStepFeatures for full data
     */
    @Deprecated("Use loadRecentStepFeatures() for full recent step records.")
    fun loadRecentSteps(): List<Float> = loadRecentStepFeatures().map { it.periodS }

    // endregion

    // ============================================================================
    // region SAVE OPERATIONS
    // ============================================================================

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

    // endregion

    // ============================================================================
    // region PRIVATE HELPERS
    // ============================================================================

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

    // endregion
}