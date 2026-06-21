package com.example.goforitGit.core.util.AntiCheat

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * Time-manipulation detection.
 * ----------------------------
 * Catches users who change the system clock to forge step history
 * (rewinding to redo "today", or jumping forward to age-out caps).
 *
 * Technique: compare two clocks the OS exposes:
 *  - [System.currentTimeMillis]    — wall clock, **user-mutable** (Settings → Date & Time).
 *  - [SystemClock.elapsedRealtime] — monotonic ms since last boot, **not** user-mutable.
 *
 * Between two samples taken in the same boot the deltas must agree (modulo a small
 * NTP-style drift). When they diverge, the user moved the wall clock.
 *
 * Also flags an obvious post-mortem signal: the step store's persisted `lastSaveMs`
 * being in the future relative to "now" — that can only happen if the clock was
 * rewound between sessions.
 *
 * Does **not** touch [com.example.goforitGit.core.util.StepsUtils.StepCounterZC] —
 * it only reads from the same `stepzc_prefs` file the counter writes.
 */
class TimeIntegrityMonitor(context: Context) {

    companion object {
        private const val TAG = "TimeIntegrity"

        private const val PREFS = "anticheat_time"

        private const val K_LAST_WALL    = "lastWallMs"
        private const val K_LAST_ELAPSED = "lastElapsedMs"
        private const val K_BOOT_WALL    = "bootWallMs"
        private const val K_REBOOTS      = "reboots"
        private const val K_TAMPERS      = "tampers"
        private const val K_TAMPER_LAST  = "tamperLastMs"
        private const val K_TAMPER_KIND  = "tamperLastKind"

        private const val STEPS_PREFS         = "stepzc_prefs"
        private const val STEPS_KEY_LAST_SAVE = "lastSaveMs"

        /** Wall vs. elapsed-time drift tolerance. NTP can correct by tens of seconds. */
        private const val DRIFT_TOLERANCE_MS = 60_000L

        /** If (wall − elapsed) shifts more than this between samples, treat as a reboot. */
        private const val REBOOT_BOOTWALL_TOLERANCE_MS = 5_000L

        /** Allow persisted lastSaveMs to be slightly ahead of now (NTP/leap-second slip). */
        private const val FUTURE_LAST_SAVE_TOLERANCE_MS = 60_000L
    }

    /** Outcome of a single integrity check. */
    enum class State {
        FIRST_RUN,           // no prior sample yet
        OK,                  // two clocks agree
        REBOOT,              // benign: elapsedRealtime reset since last sample
        CLOCK_REWIND,        // wall clock moved backwards
        CLOCK_FORWARD_JUMP,  // wall clock advanced way more than monotonic time did
        FUTURE_LAST_SAVE     // the step store remembers a save that is "in the future"
    }

    data class Report(
        val state: State,
        val wallNowMs: Long,
        val elapsedNowMs: Long,
        val wallDeltaMs: Long,
        val elapsedDeltaMs: Long,
        val driftMs: Long,
        val message: String
    )

    private val ctx        = context.applicationContext
    private val prefs      = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val stepsPrefs = ctx.getSharedPreferences(STEPS_PREFS, Context.MODE_PRIVATE)

    // ---- read-only telemetry ----

    val rebootCount: Int       get() = prefs.getInt(K_REBOOTS, 0)
    val tamperCount: Int       get() = prefs.getInt(K_TAMPERS, 0)
    val lastTamperMs: Long     get() = prefs.getLong(K_TAMPER_LAST, 0L)
    val lastTamperKind: String? get() = prefs.getString(K_TAMPER_KIND, null)

    /**
     * Run one integrity check. Records any anomaly to internal counters and updates
     * the rolling baseline so the next call compares against a fresh sample.
     */
    @Synchronized
    fun check(
        nowWallMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Report {
        val bootWallNow = nowWallMs - nowElapsedMs

        // (a) Future lastSaveMs — possible only if the clock was rewound between sessions.
        val lastSaveMs = stepsPrefs.getLong(STEPS_KEY_LAST_SAVE, -1L)
        if (lastSaveMs > 0 && lastSaveMs > nowWallMs + FUTURE_LAST_SAVE_TOLERANCE_MS) {
            recordTamper(State.FUTURE_LAST_SAVE, nowWallMs)
            persistSample(nowWallMs, nowElapsedMs, bootWallNow)
            return Report(
                State.FUTURE_LAST_SAVE, nowWallMs, nowElapsedMs,
                0L, 0L, 0L,
                "Persisted lastSaveMs is ${lastSaveMs - nowWallMs} ms ahead of now"
            )
        }

        val prevWall     = prefs.getLong(K_LAST_WALL, -1L)
        val prevElapsed  = prefs.getLong(K_LAST_ELAPSED, -1L)
        val prevBootWall = prefs.getLong(K_BOOT_WALL, Long.MIN_VALUE)

        // (b) First run — nothing to compare against.
        if (prevWall < 0L || prevElapsed < 0L) {
            persistSample(nowWallMs, nowElapsedMs, bootWallNow)
            return Report(
                State.FIRST_RUN, nowWallMs, nowElapsedMs,
                0L, 0L, 0L, "First sample, no prior baseline"
            )
        }

        // (c) Reboot — elapsedRealtime resets, so cross-boot deltas are meaningless.
        if (prevBootWall != Long.MIN_VALUE &&
            abs(bootWallNow - prevBootWall) > REBOOT_BOOTWALL_TOLERANCE_MS
        ) {
            val newReboots = rebootCount + 1
            prefs.edit().putInt(K_REBOOTS, newReboots).apply()
            persistSample(nowWallMs, nowElapsedMs, bootWallNow)
            return Report(
                State.REBOOT, nowWallMs, nowElapsedMs,
                nowWallMs - prevWall, nowElapsedMs - prevElapsed, 0L,
                "Reboot since last sample (#$newReboots)"
            )
        }

        // (d) Same boot — the deltas must agree.
        val dWall    = nowWallMs    - prevWall
        val dElapsed = nowElapsedMs - prevElapsed
        val drift    = dWall - dElapsed

        val state: State
        val msg: String
        when {
            dWall < 0L -> {
                state = State.CLOCK_REWIND
                msg = "Wall clock moved backwards by ${-dWall} ms"
            }
            drift > DRIFT_TOLERANCE_MS -> {
                state = State.CLOCK_FORWARD_JUMP
                msg = "Wall clock jumped $drift ms ahead of monotonic time"
            }
            drift < -DRIFT_TOLERANCE_MS -> {
                state = State.CLOCK_REWIND
                msg = "Wall clock lagged ${-drift} ms behind monotonic time"
            }
            else -> {
                state = State.OK
                msg = "OK (drift=$drift ms)"
            }
        }

        if (state == State.CLOCK_REWIND || state == State.CLOCK_FORWARD_JUMP) {
            recordTamper(state, nowWallMs)
        }

        persistSample(nowWallMs, nowElapsedMs, bootWallNow)
        return Report(state, nowWallMs, nowElapsedMs, dWall, dElapsed, drift, msg)
    }

    /** Clears the baseline sample and all counters. */
    @Synchronized
    fun reset() {
        prefs.edit().clear().apply()
    }

    // -----------------------------------------------------------------------

    private fun persistSample(wall: Long, elapsed: Long, bootWall: Long) {
        prefs.edit()
            .putLong(K_LAST_WALL,    wall)
            .putLong(K_LAST_ELAPSED, elapsed)
            .putLong(K_BOOT_WALL,    bootWall)
            .apply()
    }

    private fun recordTamper(kind: State, nowWallMs: Long) {
        val newCount = tamperCount + 1
        prefs.edit()
            .putInt(K_TAMPERS,    newCount)
            .putLong(K_TAMPER_LAST, nowWallMs)
            .putString(K_TAMPER_KIND, kind.name)
            .apply()
        Log.w(TAG, "time tamper recorded: $kind at $nowWallMs (total=$newCount)")
    }
}
