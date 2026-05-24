package com.example.goforitGit.core.util.TrackingLifecycle

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.goforitGit.core.service.BleAdvertScanService
import com.example.goforitGit.core.service.StepService
import com.example.goforitGit.tracking_lifecycle.TrackingHealthWorker

object TrackingServiceManager {

    private const val TAG = "TrackingServiceManager"

    fun startTracking(context: Context) {
        val appContext = context.applicationContext

        TrackingPrefs.setTrackingEnabled(appContext, true)
        TrackingHealthWorker.schedule(appContext)

        ensureTrackingRunning(appContext)
    }

    fun stopTracking(context: Context) {
        val appContext = context.applicationContext

        TrackingPrefs.setTrackingEnabled(appContext, false)
        TrackingHealthWorker.cancel(appContext)

        runCatching {
            appContext.stopService(Intent(appContext, StepService::class.java))
        }.onFailure { error ->
            Log.w(TAG, "Failed to stop StepService.", error)
        }

        runCatching {
            BleAdvertScanService.stop(appContext)
        }.onFailure { error ->
            Log.w(TAG, "Failed to stop BleAdvertScanService.", error)
        }
    }

    fun ensureTrackingRunning(context: Context) {
        val appContext = context.applicationContext

        if (!TrackingPrefs.isTrackingEnabled(appContext)) {
            Log.d(TAG, "Tracking is disabled. Not starting services.")
            return
        }

        startStepServiceBestEffort(
            context = appContext,
            action = StepService.ACTION_START_FGS
        )

        startBleServiceBestEffort(appContext)
    }

    fun notifyPermissionsUpdated(context: Context) {
        val appContext = context.applicationContext

        TrackingPrefs.setTrackingEnabled(appContext, true)
        TrackingHealthWorker.schedule(appContext)

        startStepServiceBestEffort(
            context = appContext,
            action = StepService.ACTION_PERMS_UPDATED
        )

        startBleServiceBestEffort(appContext)
    }

    private fun startStepServiceBestEffort(
        context: Context,
        action: String
    ) {
        val intent = Intent(context, StepService::class.java).apply {
            this.action = action
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "StepService FGS start not allowed from current app state.", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "StepService start failed due to app state.", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "StepService start failed because of missing permission.", e)
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected StepService start failure.", t)
        }
    }

    private fun startBleServiceBestEffort(context: Context) {
        try {
            BleAdvertScanService.start(context)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "BLE FGS start not allowed from current app state.", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "BLE service start failed due to app state.", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE service start failed because of missing permission.", e)
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected BLE service start failure.", t)
        }
    }
}