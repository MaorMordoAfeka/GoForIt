package com.example.goforitGit.count_step_module

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.goforitGit.MainActivity
import com.example.goforitGit.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.library.BuildConfig

/**
 * Foreground service for step counting and location tracking.
 *
 * Manages the StepCounterZC instance, GPS updates, and foreground notification
 * to keep step counting active even when the app is in the background.
 */
class StepService : Service() {

    // ============================================================================
    // region COMPANION OBJECT - Constants
    // ============================================================================

    companion object {
        private const val CHANNEL_ID = "step_channel"
        private const val NOTIF_ID_FGS = 42
        private const val NOTIF_ID_TAP_TO_RESUME = 43

        const val ACTION_START_FGS = "com.example.goforitGit.ACTION_START_FGS"
        const val ACTION_PERMS_UPDATED = "com.example.goforitGit.ACTION_PERMS_UPDATED"
    }

    // endregion

    // ============================================================================
    // region CORE DEPENDENCIES
    // ============================================================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var stepper: StepCounterZC
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locCallback: LocationCallback

    // endregion

    // ============================================================================
    // region SERVICE STATE
    // ============================================================================

    private var fgsStarted = false
    private var stepperStarted = false

    // endregion

    // ============================================================================
    // region GPS STATE
    // ============================================================================

    private var locationRunning = false
    private var currentLocPriority: Int? = null
    private var currentLocIntervalMs: Long? = null

    /** Rolling window of accurate fixes for fallback speed calculation */
    private val recentFixes = ArrayDeque<Location>()

    /** Smoothed speed in m/s */
    private var emaSpeedMps: Float? = null

    // endregion

    // ============================================================================
    // region LIFECYCLE
    // ============================================================================

    override fun onCreate() {
        super.onCreate()

        createNotifChannel()
        initializeStepper()
        initializeLocationProvider()
        observeModeChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START_FGS,
            ACTION_PERMS_UPDATED -> {
                startOrResume()
                START_STICKY
            }

            null -> {
                // System restarted the service: do NOT attempt FGS (often forbidden)
                showTapToResumeNotification()
                START_NOT_STICKY
            }

            else -> {
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(locCallback)

        // IMPORTANT: only persist if we actually started step counting
        if (stepperStarted) {
            stepper.stop()
        }

        scope.cancel()
        super.onDestroy()
    }


    override fun onBind(intent: Intent?) = null

    // endregion

    // ============================================================================
    // region INITIALIZATION
    // ============================================================================

    private fun initializeStepper() {
        stepper = StepCounterZC.getInstance(this, BuildConfig.DEBUG)

        scope.launch { stepper.spm.collectLatest { StepBus.spm.value = it } }
        scope.launch { stepper.stepsFlow.collectLatest { StepBus.steps.value = it } }
        scope.launch { stepper.mode.collectLatest { StepBus.mode.value = it } }
    }

    private fun initializeLocationProvider() {
        fused = LocationServices.getFusedLocationProviderClient(this)

        locCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                handleLocationResult(res)
            }
        }
    }

    private fun observeModeChanges() {
        scope.launch {
            StepBus.mode.collectLatest { mode ->
                if (fgsStarted && hasLocPerm()) {
                    restartLocationForMode(mode)
                }
            }
        }
    }

    // endregion

    // ============================================================================
    // region FOREGROUND SERVICE MANAGEMENT
    // ============================================================================

    private fun startOrResume() {
        val ok = promoteToForeground()
        if (!ok) {
            stopSelf()
            return
        }

        fgsStarted = true

        if (!stepperStarted) {
            stepper.start()
            stepperStarted = true
        }

        startLocationUpdates()
        updateNotificationForMode()
    }

    /** Promote to foreground only when allowed (user-initiated actions) */
    private fun promoteToForeground(): Boolean {
        val types = if (hasLocPerm()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }

        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_FGS,
                buildFgsNotif("Counting steps…"),
                types
            )
            true
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            showTapToResumeNotification()
            false
        } catch (e: SecurityException) {
            showTapToResumeNotification()
            false
        }
    }

    private fun showTapToResumeNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_TAP_TO_RESUME, buildTapToResumeNotif("Open app to resume step counter"))
    }

    // endregion

    // ============================================================================
    // region LOCATION HANDLING
    // ============================================================================

    private fun handleLocationResult(res: LocationResult) {
        val loc = res.lastLocation ?: return

        // 1) Provider speed if available (m/s)
        val providerMps: Float? = if (loc.hasSpeed()) loc.speed else null

        // 2) Keep short window of accurate fixes for fallback (≤20 m, ≤10 s, ≤6 points)
        if (loc.hasAccuracy() && loc.accuracy <= 20f) {
            recentFixes.add(loc)
            val now = loc.elapsedRealtimeNanos
            while (recentFixes.size > 6 ||
                (now - recentFixes.first().elapsedRealtimeNanos) > 10_000_000_000L
            ) {
                recentFixes.removeFirst()
            }
        }

        // 3) Windowed fallback: total distance / total time
        val fallbackMps = calculateFallbackSpeed(providerMps)

        // 4) Update EMA speed
        val rawMps = providerMps ?: fallbackMps
        if (rawMps != null) {
            val prev = emaSpeedMps
            val alpha = if (providerMps != null) 0.6f else 0.35f

            emaSpeedMps = when {
                prev == null -> rawMps
                rawMps > prev + 2.5f -> rawMps  // Snap up on sharp accelerations
                else -> (1f - alpha) * prev + alpha * rawMps
            }
        }

        // 5) Publish to UI and stepper
        val uiMps = providerMps ?: fallbackMps ?: emaSpeedMps
        StepBus.speedMps.value = uiMps

        val modeMps = emaSpeedMps ?: providerMps ?: fallbackMps
        stepper.updateSpeedMps(modeMps)

        updateNotificationForMode()
    }

    private fun calculateFallbackSpeed(providerMps: Float?): Float? {
        if (providerMps != null || recentFixes.size < 2) return null

        var dist = 0f
        val first = recentFixes.first()
        var last = first

        for (i in 1 until recentFixes.size) {
            val a = recentFixes[i - 1]
            val b = recentFixes[i]
            val seg = a.distanceTo(b)
            if (seg >= 3f) dist += seg
            last = b
        }

        val dt = (last.elapsedRealtimeNanos - first.elapsedRealtimeNanos) / 1e9
        return if (dt >= 2.0 && dist >= 10f) (dist / dt).toFloat() else null
    }

    // endregion

    // ============================================================================
    // region LOCATION UPDATES MANAGEMENT
    // ============================================================================

    private fun hasLocPerm(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(priority: Int, intervalMs: Long) {
        if (!hasLocPerm()) return

        // If already running with the same config, do nothing
        if (locationRunning &&
            currentLocPriority == priority &&
            currentLocIntervalMs == intervalMs
        ) {
            return
        }

        // Reconfigure the request
        fused.removeLocationUpdates(locCallback)

        val req = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMinUpdateDistanceMeters(0f)
            .build()

        fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper())
        locationRunning = true
        currentLocPriority = priority
        currentLocIntervalMs = intervalMs
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocPerm()) return
        restartLocationForMode(StepBus.mode.value)
    }

    /** Choose GPS profile based on current MotionMode */
    private fun restartLocationForMode(mode: StepCounterZC.MotionMode) {
        when (mode) {
            StepCounterZC.MotionMode.DRIVING,
            StepCounterZC.MotionMode.CYCLING -> {
                // In vehicle → fast + precise
                startLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            }

            StepCounterZC.MotionMode.RUNNING,
            StepCounterZC.MotionMode.WALKING -> {
                // On foot → slower + cheaper
                startLocationUpdates(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
            }

            StepCounterZC.MotionMode.STATIONARY,
            StepCounterZC.MotionMode.STANDING_STILL -> {
                // Low-power "heartbeat" so bursts are detected
                startLocationUpdates(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L)
            }

            else -> {
                // UNKNOWN / startup → default 5s, high accuracy
                startLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            }
        }
    }

    // endregion

    // ============================================================================
    // region NOTIFICATIONS
    // ============================================================================

    private fun createNotifChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Step counting", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildFgsNotif(text: String): Notification {

        val pi = pendingIntentHelper()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Step Counter running")
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pi)
            .build()
    }

    private fun buildTapToResumeNotif(text: String): Notification {

        val pi = pendingIntentHelper()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Step Counter paused")
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    private fun pendingIntentHelper() : PendingIntent {
        val launchIntentMainActivity = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pi = PendingIntent.getActivity(
            this,
            0,
            launchIntentMainActivity,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return pi
    }

    private fun updateNotificationForMode() {
        val notification = buildFgsNotif("Mode: ${StepBus.mode.value} • Steps: ${StepBus.steps.value}")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_FGS, notification)
    }

    // endregion
}