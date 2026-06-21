package com.example.goforitGit.core.util.AntiCheat

import android.content.Context

/**
 * Single entry point for the anti-cheat detectors.
 *
 * Usage (from anywhere in the app, e.g. Application.onCreate, repositories, sync
 * jobs, or before showing stats):
 *
 * ```
 * val ac = AntiCheatManager.get(context)
 * ac.startMonitoring()                // optional: auto-resign on in-process writes
 * val snap = ac.snapshot()            // verify clock + data integrity now
 * if (snap.time.state != TimeIntegrityMonitor.State.OK &&
 *     snap.time.state != TimeIntegrityMonitor.State.FIRST_RUN &&
 *     snap.time.state != TimeIntegrityMonitor.State.REBOOT) {
 *     // distrust today's step total for server upload, show a warning, etc.
 * }
 * ```
 *
 * Intentionally does **not** touch [com.example.goforitGit.core.util.StepsUtils.StepCounterZC];
 * both detectors only read the same SharedPreferences file the counter writes.
 */
class AntiCheatManager private constructor(context: Context) {

    val time: TimeIntegrityMonitor = TimeIntegrityMonitor(context)
    val data: DataIntegrityMonitor = DataIntegrityMonitor(context)

    /** Begin auto-resigning the data tag on legitimate in-process writes. */
    fun startMonitoring() = data.startMonitoring()

    /** Stop the auto-resign listener (does not change recorded tamper counts). */
    fun stopMonitoring() = data.stopMonitoring()

    /** Run both checks and return their reports together. */
    fun snapshot(): Snapshot = Snapshot(
        time = time.check(),
        data = data.verifyAndResign()
    )

    data class Snapshot(
        val time: TimeIntegrityMonitor.Report,
        val data: DataIntegrityMonitor.Report
    )

    companion object {
        @Volatile private var instance: AntiCheatManager? = null

        fun get(context: Context): AntiCheatManager {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: AntiCheatManager(app).also { instance = it }
            }
        }
    }
}
