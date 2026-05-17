package com.example.goforitGit.core.util.TrackingLifecycle

import android.content.Context

/**
 * Tiny SharedPreferences-backed liveness signal.
 *
 * The step service writes a timestamp every ~30s while it is alive.
 * The periodic health worker reads it; if the value is too old, the
 * service is assumed dead and a TrackingRestartWorker is enqueued.
 *
 * This is intentionally simpler than ActivityManager#getRunningServices
 * (which is unreliable on modern Android) or bindService heuristics.
 */
object TrackingHeartbeat {

    private const val PREFS = "tracking_heartbeat"
    private const val KEY_STEP_LAST_ALIVE_MS = "step_last_alive_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markStepServiceAlive(context: Context) {
        prefs(context)
            .edit()
            .putLong(KEY_STEP_LAST_ALIVE_MS, System.currentTimeMillis())
            .apply()
    }

    /**
     * Returns true if a heartbeat was written within [maxAgeMs].
     * Returns false if no heartbeat exists yet (e.g. just after boot)
     * so the worker will enqueue a restart in that case too.
     */
    fun isStepServiceAlive(context: Context, maxAgeMs: Long): Boolean {
        val last = prefs(context).getLong(KEY_STEP_LAST_ALIVE_MS, 0L)
        if (last == 0L) return false
        return (System.currentTimeMillis() - last) <= maxAgeMs
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}