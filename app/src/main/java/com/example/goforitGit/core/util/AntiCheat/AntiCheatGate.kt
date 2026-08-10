package com.example.goforitGit.core.util.AntiCheat

import android.content.Context
import android.util.Log

/**
 * Single decision point that turns an [AntiCheatManager.Snapshot] into the one
 * boolean the upload path needs.
 *
 * Exists so the "which states count as benign" list is written down exactly
 * once. It is used from two call sites — FourHourUploadWorker (interval steps)
 * and StepService (campus steps) — and if those two lists ever drifted apart,
 * the same device state would be trusted by one uploader and flagged by the
 * other. That inconsistency is worse than having no check at all, because the
 * resulting Firestore data would contradict itself.
 *
 * Deliberately NOT part of AntiCheatManager: that class reports raw detector
 * state and stays policy-free. Where the line between "benign" and "suspect"
 * sits is a product decision, and it lives here.
 */
object AntiCheatGate {

    private const val TAG = "AntiCheat"

    /**
     * States that must NOT be treated as cheating:
     *
     * - FIRST_RUN  no prior baseline exists, so there is nothing to compare.
     * - REBOOT     elapsedRealtime resets on boot, making cross-boot deltas
     *              meaningless. A user who restarts their phone is not a cheat.
     * - KEY_ERROR  the Android Keystore failed. That is a device/OEM problem;
     *              blaming the user would flag honest accounts on specific
     *              hardware.
     *
     * Getting this list wrong is how an anti-cheat system starts punishing
     * honest users — a far more damaging failure mode than missing the
     * occasional cheat, given that this app's whole value proposition is a
     * leaderboard people trust.
     */
    private val BENIGN_TIME_STATES = setOf(
        TimeIntegrityMonitor.State.OK,
        TimeIntegrityMonitor.State.FIRST_RUN,
        TimeIntegrityMonitor.State.REBOOT,
    )

    private val BENIGN_DATA_STATES = setOf(
        DataIntegrityMonitor.State.OK,
        DataIntegrityMonitor.State.FIRST_RUN,
        DataIntegrityMonitor.State.KEY_ERROR,
    )

    /**
     * Runs both detectors and returns whether the local step data can be
     * presented to the server as trustworthy.
     *
     * Fails OPEN: if the detectors themselves throw, this returns true. A bug in
     * the integrity layer must never silently mark a legitimate user's steps as
     * suspect — the same fail-open principle already used for the device-trust
     * check in LoginActivity.proceedAfterAuth().
     *
     * @param source short label for the log line, e.g. "interval" / "campus".
     */
    fun evaluate(context: Context, source: String): Boolean = runCatching {
        val snapshot = AntiCheatManager.get(context).snapshot()

        val timeOk = snapshot.time.state in BENIGN_TIME_STATES
        val dataOk = snapshot.data.state in BENIGN_DATA_STATES

        if (!timeOk || !dataOk) {
            Log.w(
                TAG,
                "[$source] flagged — clock=${snapshot.time.state} (${snapshot.time.message}) | " +
                        "data=${snapshot.data.state} (${snapshot.data.message})"
            )
        }

        timeOk && dataOk
    }.getOrElse { t ->
        Log.e(TAG, "[$source] integrity evaluation crashed; failing open", t)
        true
    }
}