package com.example.goforitGit.core.util.DeviceSecurity

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Generates and persists a random per-install device identifier, used to
 * tell this device apart from others the same account signs in on (see
 * FirebaseServerApi's DEVICE TRUST section). Not tied to any hardware ID —
 * a fresh install gets a fresh identity, which matches how a "trusted
 * device" reset is expected to behave after a reinstall.
 */
object DeviceIdentity {

    private const val PREFS = "device_identity"
    private const val KEY_DEVICE_ID = "device_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreateDeviceId(context: Context): String {
        val existing = prefs(context).getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        val generated = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    /**
     * Human-readable label for the verification push/UI, e.g. "Google Pixel 8".
     */
    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            model.isEmpty() -> "Unknown device"
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }
}
