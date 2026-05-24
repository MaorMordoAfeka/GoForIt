package com.example.goforitGit.core.util.TrackingLifecycle

import android.content.Context
import android.os.Build

object TrackingPrefs {

    private const val PREFS_NAME = "tracking_prefs"
    private const val KEY_TRACKING_ENABLED = "tracking_enabled"

    private fun storageContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
    }

    private fun prefs(context: Context) =
        storageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setTrackingEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_TRACKING_ENABLED, enabled)
            .apply()
    }

    fun isTrackingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TRACKING_ENABLED, false)
    }
}