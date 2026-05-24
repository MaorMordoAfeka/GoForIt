package com.example.goforitGit.core.util.TrackingLifecycle

import android.content.Context

/**
 * One-time-prompt flags for first-launch rationale dialogs.
 *
 * Two tiers:
 *
 *  1. Baseline tier — notifications, activity recognition, location, BLE.
 *     Without these the core app does not function. We still show a short
 *     rationale dialog before each system permission box, but only once
 *     per install (Android already gates re-prompts via its own
 *     shouldShowRequestPermissionRationale flow).
 *
 *  2. Advanced tier — background location, battery optimization,
 *     MIUI autostart. Without these the app still works, but resilience
 *     drops to the second tier (no bonus-zone tracking when the app is
 *     closed, slower mid-day watchdog recovery, no autostart on Xiaomi).
 *
 * We intentionally remember the *asked* state, not the *granted* state.
 * Granted state can be re-checked at runtime; asked state cannot.
 */
object OnboardingPrefs {

    private const val PREFS = "tracking_onboarding"

    // Baseline tier
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    private const val KEY_ASKED_ACTIVITY_RECOGNITION = "asked_activity_recognition"
    private const val KEY_ASKED_LOCATION = "asked_location"
    private const val KEY_ASKED_BLE = "asked_ble"

    // Advanced tier
    private const val KEY_ASKED_BACKGROUND_LOCATION = "asked_background_location"
    private const val KEY_ASKED_BATTERY_EXEMPTION = "asked_battery_exemption"
    private const val KEY_ASKED_MIUI_AUTOSTART = "asked_miui_autostart"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Baseline ----

    fun hasAskedNotifications(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_NOTIFICATIONS, false)

    fun markAskedNotifications(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
    }

    fun hasAskedActivityRecognition(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_ACTIVITY_RECOGNITION, false)

    fun markAskedActivityRecognition(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_ACTIVITY_RECOGNITION, true).apply()
    }

    fun hasAskedLocation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_LOCATION, false)

    fun markAskedLocation(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_LOCATION, true).apply()
    }

    fun hasAskedBle(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_BLE, false)

    fun markAskedBle(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_BLE, true).apply()
    }

    // ---- Advanced ----

    fun hasAskedBackgroundLocation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_BACKGROUND_LOCATION, false)

    fun markAskedBackgroundLocation(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_BACKGROUND_LOCATION, true).apply()
    }

    fun hasAskedBatteryExemption(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_BATTERY_EXEMPTION, false)

    fun markAskedBatteryExemption(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_BATTERY_EXEMPTION, true).apply()
    }

    fun hasAskedMiuiAutostart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_MIUI_AUTOSTART, false)

    fun markAskedMiuiAutostart(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_MIUI_AUTOSTART, true).apply()
    }

    /**
     * Reset all "asked" flags. Useful from a debug screen during development.
     */
    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}