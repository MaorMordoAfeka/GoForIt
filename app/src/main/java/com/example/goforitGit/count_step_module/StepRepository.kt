package com.example.goforitGit.count_step_module

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import kotlin.math.roundToInt

/**
 * Repository for step-related data access.
 *
 * Provides lifecycle-aware LiveData streams for UI consumption
 * and utility methods for step calculations.
 *
 * Over view of the Data flow: Service → StepBus → Repository → UI
 */
class StepRepository private constructor(app: Application) {

    // ============================================================================
    // region COMPANION OBJECT - Singleton
    // ============================================================================

    companion object {
        @Volatile
        private var INSTANCE: StepRepository? = null

        fun get(app: Application): StepRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepRepository(app).also { INSTANCE = it }
            }
        }
    }

    // endregion

    // ============================================================================
    // region DEPENDENCIES
    // ============================================================================

    private val store = StepHistoryStore(app)

    // endregion

    // ============================================================================
    // region INITIALIZATION
    // ============================================================================

    init {
        preloadAccumulatedSteps()
    }

    /** Preloads accumulated steps into StepBus from SharedPreferences */
    private fun preloadAccumulatedSteps() {
        val initialSteps = store.loadTotalSteps() ?: store.loadStepTimestamps().size

        // Only override the default 0 if we actually have history
        if (initialSteps > 0 && StepBus.steps.value == 0) {
            StepBus.steps.value = initialSteps
        }
    }

    // endregion

    // ============================================================================
    // region LIVE DATA STREAMS - Direct from StepBus
    // ============================================================================

    /** Current speed in m/s (nullable) */
    val kmhLD: LiveData<Float?> = StepBus.speedMps.asLiveData()

    /** Total step count */
    val stepsLD: LiveData<Int> = StepBus.steps.asLiveData()

    /** Current motion mode (WALKING, RUNNING, etc.) */
    val modeLD: LiveData<StepCounterZC.MotionMode> = StepBus.mode.asLiveData()

    /** Sensor diagnostics snapshot */
    val sensorsLD: LiveData<StepBus.SensorsSnapshot> = StepBus.sensors.asLiveData()

    /** Steps per minute */
    val spmLD: LiveData<Float> = StepBus.spm.asLiveData()

    /**
     * Steps taken today.
     *
     * Recomputes only when someone is observing; triggers on step changes.
     */
    val stepsTodayLD: LiveData<Int> = liveData {
        StepBus.steps.collect {
            emit(stepsToday())
        }
    }

    // endregion

    // ============================================================================
    // region PUBLIC API - Step Calculations
    // ============================================================================

    /**
     * Returns average steps per minute over the last [timeMinutes] minutes.
     *
     * @param timeMinutes Is the Duration in minutes to average over (clamped to 1..1440 minutes which is 24 hours)
     * @return Average SPM (steps per minute), clamped to human range (0..240)
     */
    fun computeAvgStepsPerDuration(timeMinutes: Long): Int {
        if (timeMinutes <= 0) return 0

        // Guard & clamp to max 24 hours
        val minutes = timeMinutes.coerceAtLeast(1L).coerceAtMost(24L * 60L)
        val windowMs = minutes * 60_000L
        val since = System.currentTimeMillis() - windowMs

        // Count steps in the window (timestamps are persisted in ms)
        val stepsInWindow = store.loadStepTimestamps().count { it >= since }

        // Average SPM = steps / minutes (equivalently: steps * 60000 / windowMs)
        val spm = (stepsInWindow.toDouble() * 60_000.0) / windowMs.toDouble()

        // Round and clamp to sane human range
        return spm.roundToInt().coerceIn(0, 240)
    }

    /**
     * Steps taken within the last [minutes] minutes.
     *
     * @param minutes Is the Duration in minutes to count (must be positive)
     * @return Number of steps in that time window of minutes
     */
    fun stepsInLastMinutes(minutes: Int): Int {
        if (minutes <= 0) return 0

        val since = System.currentTimeMillis() - minutes * 60_000L
        val times = store.loadStepTimestamps()

        return times.count { it >= since }
    }

    // endregion

    // ============================================================================
    // region PRIVATE HELPERS
    // ============================================================================

    /** Calculates steps taken since midnight today */
    private fun stepsToday(): Int {
        val times = store.loadStepTimestamps()

        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        return times.count { it >= startOfDay }
    }

    // endregion
}