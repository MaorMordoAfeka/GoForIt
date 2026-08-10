package com.example.goforitGit

import android.app.Application
import android.util.Log
import com.example.goforitGit.core.util.AntiCheat.AntiCheatManager

/**
 * Application entry point.
 *
 * ORDER IS NOT INTERCHANGEABLE:
 *
 *   1. snapshot()         verifies the stored HMAC tag while it still reflects
 *                         the last legitimate in-process write. This is the only
 *                         moment an out-of-band edit to stepzc_prefs is
 *                         detectable.
 *
 *   2. startMonitoring()  installs the listener that silently re-signs the tag
 *                         on every legitimate write from StepCounterZC.
 *
 * Reversing them re-signs tampered data before it is ever checked, which
 * disables DataIntegrityMonitor entirely — silently, with no error anywhere.
 *
 * ---------------------------------------------------------------------------
 * CHAQUOPY: the manifest carries tools:replace="android:name", which implies
 * Chaquopy is in the build. If so, this class MUST extend PyApplication or
 * Python is never initialised:
 *
 *     import com.chaquo.python.android.PyApplication
 *     class GoForItApp : PyApplication() {
 * ---------------------------------------------------------------------------
 */
class GoForItApp : Application() {          // <-- Chaquopy: PyApplication()

    override fun onCreate() {
        super.onCreate()                     // <-- Chaquopy: required, boots Python

        initAntiCheat()
    }

    /**
     * Wrapped in runCatching throughout: a failure in the integrity layer must
     * never prevent the process from starting. An app that will not launch is a
     * worse outcome than an app whose cheat detection is temporarily blind.
     */
    private fun initAntiCheat() {
        val antiCheat = AntiCheatManager.get(this)

        val snapshot = runCatching { antiCheat.snapshot() }
            .onFailure { Log.e(TAG, "startup snapshot failed", it) }
            .getOrNull()

        if (snapshot != null) {
            Log.i(
                TAG,
                "startup — clock=${snapshot.time.state} (${snapshot.time.message}) | " +
                        "data=${snapshot.data.state} (${snapshot.data.message})"
            )
            Log.i(
                TAG,
                "counters — clockTampers=${antiCheat.time.tamperCount} " +
                        "dataTampers=${antiCheat.data.tamperCount} " +
                        "reboots=${antiCheat.time.rebootCount}"
            )
        }

        runCatching { antiCheat.startMonitoring() }
            .onFailure { Log.e(TAG, "startMonitoring failed", it) }
    }

    private companion object {
        private const val TAG = "AntiCheat"
    }
}