package com.example.goforitGit.core.util.TrackingLifecycle

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Pure helpers for inspecting + jumping to the OS pages that control
 * 24/7 tracking quality. No UI here — UI lives in the Activity that calls
 * into this.
 */
object TrackingPermissions {

    private const val TAG = "TrackingPermissions"

    // -------------------------------------------------------------------------
    // Background location ("Allow all the time")
    // -------------------------------------------------------------------------

    /**
     * Returns true if we hold ACCESS_BACKGROUND_LOCATION. Pre-Android 10
     * (Q), there is no such permission and FINE/COARSE alone is sufficient
     * for background access — so this returns true on older devices.
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true

        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether it makes sense to *ask* the user for background location.
     */
    fun shouldAskForBackgroundLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (hasBackgroundLocationPermission(context)) return false

        val hasForeground =
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

        return hasForeground
    }

    /**
     * On Android 11+ (R), background location can only be granted via the
     * Settings app — runtime requests get auto-denied. On Android 10 it
     * can be requested inline. Returns true for the inline-eligible case.
     */
    fun canRequestBackgroundLocationInline(): Boolean {
        return Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
    }

    /**
     * Open the deepest settings page we can that lets the user grant
     * "Allow all the time".
     *
     * On Android 11+ there is no public API that lands directly on the
     * three-way location picker (Allow all the time / While using the
     * app / Don't allow). The app-details page is the closest we can get
     * with a stable, supported intent. From there the user has to tap:
     *
     *   Permissions  ->  Location  ->  Allow all the time
     *
     * The dialog message wording below should tell the user this exact path.
     *
     * Returns true if we managed to launch *something* the user can act on.
     */
    fun launchBackgroundLocationSettings(activity: Activity): Boolean {
        val candidates = listOf(
            // Best case on stock Android: lands on the app-details page.
            // Some OEMs (including MIUI) honor this and put a "Permissions"
            // entry near the top, which is one tap from the location picker.
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Settings intent failed: ${intent.action}", t)
            }
        }

        return false
    }

    /**
     * Kept as a public alias for any caller that just wants the generic
     * app-details intent without the "best deep link" semantics.
     */
    fun buildOpenAppLocationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // -------------------------------------------------------------------------
    // Battery optimization exemption
    // -------------------------------------------------------------------------

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun launchBatteryOptimizationExemptionRequest(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }

        return try {
            activity.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Direct battery-opt prompt unavailable; falling back to settings list.", t)

            try {
                activity.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                true
            } catch (t2: Throwable) {
                Log.e(TAG, "Battery-opt settings unavailable on this device.", t2)
                false
            }
        }
    }

    // -------------------------------------------------------------------------
    // MIUI / HyperOS Autostart
    // -------------------------------------------------------------------------

    fun isMiuiLikeDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        return manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco")
    }

    fun launchMiuiAutostartSettings(activity: Activity): Boolean {
        val miuiIntent = Intent().apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            activity.startActivity(miuiIntent)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "MIUI autostart screen unavailable; opening app details instead.", t)
        }

        return try {
            activity.startActivity(buildOpenAppLocationSettingsIntent(activity))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot open any settings page.", t)
            false
        }
    }
}