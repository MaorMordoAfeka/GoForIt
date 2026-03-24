package com.example.goforitGit.core.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.goforitGit.navigation.MainActivity
import com.example.goforitGit.R
import com.example.goforitGit.core.util.FourHourBuckets.FourHourBucketsSinceBoot
import com.example.goforitGit.core.util.FourHourBuckets.FourHourUploadScheduler
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.core.data.StepsData.StepBus
import com.example.goforitGit.core.data.StepsData.StepHistoryStore
import com.example.goforitGit.core.util.StepsUtils.CollegeZoneChecker
import com.example.goforitGit.core.util.StepsUtils.StepCounterZC
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
 * Keeps the original regular-step flow:
 * - step counter updates StepBus
 * - 4-hour local buckets are updated continuously
 * - boundary uploads are performed later by FourHourUploadWorker
 *
 * Adds college-area bonus logic:
 * - newly detected steps are checked against latest fresh GPS fix
 * - if inside polygon, cumulative daily qualified steps are synced to server
 */
class StepService : Service() {

    companion object {
        private const val CHANNEL_ID = "step_channel"
        private const val NOTIF_ID_FGS = 42
        private const val NOTIF_ID_TAP_TO_RESUME = 43

        const val ACTION_START_FGS = "com.example.goforitGit.ACTION_START_FGS"
        const val ACTION_PERMS_UPDATED = "com.example.goforitGit.ACTION_PERMS_UPDATED"

        private const val TAG = "StepService"

        private const val COLLEGE_LOC_MAX_AGE_MS = 20_000L
        private const val COLLEGE_LOC_MAX_ACCURACY_METERS = 35f
        private const val COLLEGE_SYNC_STEP_BATCH = 10
        private const val COLLEGE_SYNC_MAX_DELAY_MS = 15_000L
    }

    @Volatile
    private var lastFgsNotificationText: String? = null

    @Volatile
    private var lastResumeNotificationText: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var stepper: StepCounterZC
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locCallback: LocationCallback

    /** Original regular-step bucket mechanism */
    private val buckets by lazy { FourHourBucketsSinceBoot(applicationContext) }

    /** Existing persistence + new college-bonus persistence */
    private val historyStore by lazy { StepHistoryStore(applicationContext) }

    /** Polygon checker for college bonus */
    private val collegeZoneChecker by lazy { CollegeZoneChecker(applicationContext) }

    private var fgsStarted = false
    private var stepperStarted = false

    private var locationRunning = false
    private var currentLocPriority: Int? = null
    private var currentLocIntervalMs: Long? = null

    /** Rolling window of accurate fixes for fallback speed calculation */
    private val recentFixes = ArrayDeque<Location>()

    /** Smoothed speed in m/s */
    private var emaSpeedMps: Float? = null

    /** Latest location fix used for college-zone qualification */
    private var latestLocationFix: Location? = null

    /** Last observed absolute step count, used to calculate only NEW steps for college bonus */
    private var lastObservedStepCount: Int? = null

    /** Prevent overlapping college sync calls */
    private var collegeSyncInFlight = false

    /** Timestamp of the last attempt to sync college-area qualified steps */
    private var lastCollegeSyncAttemptMs = 0L

    private var currentLocMinDistanceM: Float? = null

    override fun onCreate() {
        super.onCreate()

        createNotifChannel()
        historyStore.ensureCollegeBonusDay(currentDayKey())
        initializeStepper()
        initializeLocationProvider()
        observeModeChanges()

        // Seed the worker chain safely. Existing unique-work policy prevents duplicates.
        FourHourUploadScheduler.scheduleNext(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START_FGS,
            ACTION_PERMS_UPDATED -> {
                startOrResume()
                START_STICKY
            }

            null -> {
                // System restarted the service: do NOT attempt FGS automatically
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

        if (stepperStarted) {
            stepper.stop()
        }

        lastFgsNotificationText = null
        lastResumeNotificationText = null

        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun initializeStepper() {
        stepper = StepCounterZC.getInstance(this, BuildConfig.DEBUG)

        scope.launch {
            stepper.spm.collectLatest { spm ->
                StepBus.spm.value = spm
            }
        }

        scope.launch {
            stepper.stepsFlow.collectLatest { totalSteps ->
                // Keep existing UI flow
                StepBus.steps.value = totalSteps

                // Restore original regular-step bucket accumulation
                buckets.update(totalSteps)

                // Additional college-bonus logic only for NEW steps
                val delta = computeNewStepDelta(totalSteps)
                if (delta > 0) {
                    handleNewDetectedSteps(delta)
                }
            }
        }

        scope.launch {
            stepper.mode.collectLatest { mode ->
                StepBus.mode.value = mode
            }
        }
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

        historyStore.ensureCollegeBonusDay(currentDayKey())

        // Make sure the boundary uploader chain is alive
        FourHourUploadScheduler.scheduleNext(applicationContext)

        startLocationUpdates()
        maybeSyncCollegeAreaSteps(force = false)
        updateNotificationForMode()
    }

    private fun promoteToForeground(): Boolean {
        val types = if (hasLocPerm()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
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
        } catch (e: ForegroundServiceStartNotAllowedException) {
            showTapToResumeNotification()
            false
        } catch (e: SecurityException) {
            showTapToResumeNotification()
            false
        }
    }

    private fun showTapToResumeNotification() {
        val text = "Open app to resume step counter"

        if (lastResumeNotificationText == text) return
        lastResumeNotificationText = text

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID_TAP_TO_RESUME,
            buildTapToResumeNotif(text)
        )
    }

    private fun computeNewStepDelta(totalSteps: Int): Int {
        val previous = lastObservedStepCount
        lastObservedStepCount = totalSteps

        if (previous == null) return 0
        return (totalSteps - previous).coerceAtLeast(0)
    }

    private fun handleNewDetectedSteps(delta: Int) {
        if (delta <= 0) return

        val todayKey = currentDayKey()
        historyStore.ensureCollegeBonusDay(todayKey)

        if (isLatestLocationEligibleForCollegeBonus()) {
            historyStore.addCollegeQualifiedSteps(todayKey, delta)
            maybeSyncCollegeAreaSteps(force = false)
        }
    }

    private fun isLatestLocationEligibleForCollegeBonus(
        nowElapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos()
    ): Boolean {
        val loc = latestLocationFix ?: return false

        if (loc.hasAccuracy() && loc.accuracy > COLLEGE_LOC_MAX_ACCURACY_METERS) {
            return false
        }

        val ageMs =
            ((nowElapsedRealtimeNs - loc.elapsedRealtimeNanos).coerceAtLeast(0L)) / 1_000_000L
        if (ageMs > COLLEGE_LOC_MAX_AGE_MS) {
            return false
        }

        return collegeZoneChecker.contains(loc.latitude, loc.longitude)
    }

    private fun maybeSyncCollegeAreaSteps(force: Boolean) {
        if (collegeSyncInFlight) return

        val progress = historyStore.loadCollegeBonusProgress(currentDayKey())
        val unsynced = progress.qualifiedSteps - progress.lastSyncedQualifiedSteps
        if (unsynced <= 0) return

        val nowMs = System.currentTimeMillis()
        val enoughForBatch = unsynced >= COLLEGE_SYNC_STEP_BATCH
        val enoughTimePassed = nowMs - lastCollegeSyncAttemptMs >= COLLEGE_SYNC_MAX_DELAY_MS

        if (!force && !enoughForBatch && !enoughTimePassed) {
            return
        }

        collegeSyncInFlight = true
        lastCollegeSyncAttemptMs = nowMs

        scope.launch(Dispatchers.IO) {
            val dayKey = currentDayKey()
            val latest = historyStore.loadCollegeBonusProgress(dayKey)

            try {
                if (latest.qualifiedSteps <= latest.lastSyncedQualifiedSteps) {
                    return@launch
                }

                val result = FirebaseServerApi.syncCollegeAreaStepsResult(
                    dayKey = latest.dayKey,
                    qualifiedStepsTotal = latest.qualifiedSteps,
                    observedAtMs = nowMs
                )

                result
                    .onSuccess { ok ->
                        if (ok) {
                            historyStore.markCollegeQualifiedStepsSynced(
                                todayKey = latest.dayKey,
                                syncedQualifiedTotal = latest.qualifiedSteps
                            )
                        }
                    }
                    .onFailure { err ->
                        Log.e(TAG, "syncCollegeAreaSteps failed: ${err.message}", err)
                    }
            } finally {
                collegeSyncInFlight = false

                val after = historyStore.loadCollegeBonusProgress(currentDayKey())
                val remaining = after.qualifiedSteps - after.lastSyncedQualifiedSteps
                if (remaining >= COLLEGE_SYNC_STEP_BATCH) {
                    maybeSyncCollegeAreaSteps(force = true)
                }
            }
        }
    }

    private fun handleLocationResult(res: LocationResult) {
        val loc = res.lastLocation ?: return
        latestLocationFix = loc

        val providerMps: Float? = if (loc.hasSpeed()) loc.speed else null

        if (loc.hasAccuracy() && loc.accuracy <= 20f) {
            recentFixes.add(loc)
            val now = loc.elapsedRealtimeNanos
            while (
                recentFixes.size > 6 ||
                (now - recentFixes.first().elapsedRealtimeNanos) > 10_000_000_000L
            ) {
                recentFixes.removeFirst()
            }
        }

        val fallbackMps = calculateFallbackSpeed(providerMps)

        val rawMps = providerMps ?: fallbackMps
        if (rawMps != null) {
            val prev = emaSpeedMps
            val alpha = if (providerMps != null) 0.6f else 0.35f

            emaSpeedMps = when {
                prev == null -> rawMps
                rawMps > prev + 2.5f -> rawMps
                else -> (1f - alpha) * prev + alpha * rawMps
            }
        }

        val uiMps = providerMps ?: fallbackMps ?: emaSpeedMps
        StepBus.speedMps.value = uiMps

        val modeMps = emaSpeedMps ?: providerMps ?: fallbackMps
        stepper.updateSpeedMps(modeMps)

        maybeSyncCollegeAreaSteps(force = false)
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

    private fun currentDayKey(): String = FirebaseServerApi.currentDayKeyAndInterval().first

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
    private fun startLocationUpdates(
        priority: Int,
        intervalMs: Long,
        minDistanceMeters: Float
    ) {
        if (!hasLocPerm()) return

        if (
            locationRunning &&
            currentLocPriority == priority &&
            currentLocIntervalMs == intervalMs &&
            currentLocMinDistanceM == minDistanceMeters
        ) {
            return
        }

        fused.removeLocationUpdates(locCallback)

        val req = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .build()

        fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper())

        locationRunning = true
        currentLocPriority = priority
        currentLocIntervalMs = intervalMs
        currentLocMinDistanceM = minDistanceMeters
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocPerm()) return
        restartLocationForMode(StepBus.mode.value)
    }

    // TODO these gps settings were before:
    //  high accuracy every 3s for DRIVING / CYCLING
    //  balanced every 10s for RUNNING / WALKING
    //  balanced every 15s for STATIONARY / STANDING_STILL
    //  high accuracy every 5s in the fallback branch.
    //  It also checks college-step eligibility with a max location age of 20 seconds
    //  and max accuracy of 35 meters.
    private fun restartLocationForMode(mode: StepCounterZC.MotionMode) {
        when (mode) {
            StepCounterZC.MotionMode.DRIVING,
            StepCounterZC.MotionMode.CYCLING -> {
                startLocationUpdates(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    6_000L, // was 3_000L
                    minDistanceMeters = 15f
                )
            }

            StepCounterZC.MotionMode.RUNNING,
            StepCounterZC.MotionMode.WALKING -> {
                startLocationUpdates(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    15_000L, // was 10_000L
                    minDistanceMeters = 5f
                )
            }

            StepCounterZC.MotionMode.STATIONARY,
            StepCounterZC.MotionMode.STANDING_STILL -> {
                startLocationUpdates(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    30_000L, // was 15_000L
                    minDistanceMeters = 10f
                )
            }

            else -> {
                startLocationUpdates(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    8_000L, // was 5_000L
                    minDistanceMeters = 8f
                )
            }
        }
    }

    private fun stopLocationUpdates() {
        fused.removeLocationUpdates(locCallback)
        locationRunning = false
        currentLocPriority = null
        currentLocIntervalMs = null
        currentLocMinDistanceM = null
    }

    private fun createNotifChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Step counting",
                    NotificationManager.IMPORTANCE_LOW
                )
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

    private fun pendingIntentHelper(): PendingIntent {
        val launchIntentMainActivity = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        return PendingIntent.getActivity(
            this,
            0,
            launchIntentMainActivity,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotificationForMode() {
        val text = "Mode: ${StepBus.mode.value} • Steps: ${StepBus.steps.value}"

        if (lastFgsNotificationText == text) return
        lastFgsNotificationText = text

        val notification = buildFgsNotif(text)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_FGS, notification)
    }
}