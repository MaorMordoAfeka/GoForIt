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
import com.example.goforitGit.R
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.core.data.StepsData.StepBus
import com.example.goforitGit.core.data.StepsData.StepHistoryStore
import com.example.goforitGit.core.util.FourHourBuckets.FourHourBucketsSinceBoot
import com.example.goforitGit.core.util.FourHourBuckets.FourHourUploadScheduler
import com.example.goforitGit.core.util.StepsUtils.CollegeZoneChecker
import com.example.goforitGit.core.util.StepsUtils.StepCounterZC
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingHeartbeat
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingPrefs
import com.example.goforitGit.navigation.MainActivity
import com.example.goforitGit.tracking_lifecycle.TrackingRestartWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.library.BuildConfig
import java.util.Locale

/**
 * Foreground service for step counting and location tracking.
 *
 * Boot-resilience policy (Android 14+ / targetSDK 36):
 *
 *  - Android refuses to promote an FGS with type LOCATION from a background
 *    context unless the user has granted ACCESS_BACKGROUND_LOCATION. At boot
 *    the app has no visible Activity, so even though we have FINE_LOCATION
 *    granted, type=LOCATION is rejected with SecurityException.
 *  - To survive that, we try the desired type (HEALTH|LOCATION) first, and
 *    on SecurityException we fall back to HEALTH only. HEALTH has no
 *    "foreground-only permission" gate, so it always succeeds.
 *  - Step counting is the core feature -> it must keep working in HEALTH-only
 *    mode. FourHour buckets still fill, the user-facing step notification
 *    still updates, BLE bonus stations still work in their own service.
 *  - When MainActivity later sends ACTION_PERMS_UPDATED (because the user
 *    opened the app, so the process is in a foreground-eligible state),
 *    we re-call startForeground with HEALTH|LOCATION. That upgrade is now
 *    allowed, and only then do we begin requesting location updates.
 *
 * FourHour upload logic is fully preserved:
 *  - buckets.update(totalSteps) runs on every step event.
 *  - FourHourUploadScheduler.scheduleNext is still called from onCreate
 *    and startOrResume, unchanged.
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

        private const val STEP_CONFIRMATION_WINDOW_MS = 15_000L
        private const val NOTIF_MIN_UPDATE_INTERVAL_MS = 2_500L

        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }

    @Volatile
    private var lastFgsNotificationText: String? = null

    @Volatile
    private var lastResumeNotificationText: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var stepper: StepCounterZC
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locCallback: LocationCallback

    private var lastConfirmedStepTotal: Int? = null
    private var lastStepDetectedAtMs: Long = 0L
    private var lastStepNotificationAtMs: Long = 0L

    /** Original regular-step bucket mechanism (unchanged). */
    private val buckets by lazy { FourHourBucketsSinceBoot(applicationContext) }

    /** Existing persistence + college-bonus persistence. */
    private val historyStore by lazy { StepHistoryStore(applicationContext) }

    /** Polygon checker for college bonus. */
    private val collegeZoneChecker by lazy { CollegeZoneChecker(applicationContext) }

    private var fgsStarted = false
    private var stepperStarted = false

    /**
     * Whether the current FGS promotion includes FOREGROUND_SERVICE_TYPE_LOCATION.
     * False when we had to fall back to HEALTH-only because Android refused
     * LOCATION (background restriction). Flipped to true when we successfully
     * re-promote with HEALTH|LOCATION later.
     */
    @Volatile
    private var hasLocationFgsType = false

    private var locationRunning = false
    private var currentLocPriority: Int? = null
    private var currentLocIntervalMs: Long? = null
    private var currentLocMinDistanceM: Float? = null

    private val recentFixes = ArrayDeque<Location>()
    private var emaSpeedMps: Float? = null
    private var latestLocationFix: Location? = null
    private var lastObservedStepCount: Int? = null
    private var collegeSyncInFlight = false
    private var lastCollegeSyncAttemptMs = 0L

    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        createNotifChannel()
        cancelTapToResumeNotification()

        historyStore.ensureCollegeBonusDay(currentDayKey())
        initializeStepper()
        initializeLocationProvider()
        observeModeChanges()

        FourHourUploadScheduler.scheduleNext(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        return when (intent?.action) {
            ACTION_START_FGS,
            ACTION_PERMS_UPDATED -> {
                startOrResume()
                START_STICKY
            }

            null -> {
                if (TrackingPrefs.isTrackingEnabled(this)) {
                    startOrResume()
                    START_STICKY
                } else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }

            else -> {
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        heartbeatJob?.cancel()
        heartbeatJob = null

        if (::fused.isInitialized && ::locCallback.isInitialized) {
            fused.removeLocationUpdates(locCallback)
        }

        if (stepperStarted && ::stepper.isInitialized) {
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
                StepBus.steps.value = totalSteps

                // FourHour bucket attribution — unchanged.
                buckets.update(totalSteps)

                updateNotificationForStepEvent(totalSteps)

                val delta = computeNewStepDelta(totalSteps)
                if (delta > 0) {
                    handleNewDetectedSteps(delta)
                }
            }
        }

        scope.launch {
            stepper.mode.collectLatest { mode ->
                StepBus.mode.value = mode
                updateTrackingNotification(force = false)
            }
        }
    }

    private fun updateNotificationForStepEvent(totalSteps: Int) {
        if (!fgsStarted) return

        val previous = lastConfirmedStepTotal
        val stepWasDetected = previous != null && totalSteps > previous

        lastConfirmedStepTotal = totalSteps

        if (stepWasDetected) {
            val detectedAtMs = System.currentTimeMillis()
            lastStepDetectedAtMs = detectedAtMs
            scheduleStepConfirmationExpiry(detectedAtMs)
        }

        updateTrackingNotification(force = stepWasDetected || previous == null)
    }

    private fun scheduleStepConfirmationExpiry(detectedAtMs: Long) {
        scope.launch {
            delay(STEP_CONFIRMATION_WINDOW_MS + 500L)
            if (lastStepDetectedAtMs == detectedAtMs) {
                updateTrackingNotification(force = true)
            }
        }
    }

    private fun updateTrackingNotification(force: Boolean = false) {
        if (!fgsStarted) return

        val now = System.currentTimeMillis()

        val steps = lastConfirmedStepTotal ?: StepBus.steps.value
        val mode = modeLabel(StepBus.mode.value)

        val recentlyCountedStep =
            lastStepDetectedAtMs > 0L &&
                    now - lastStepDetectedAtMs <= STEP_CONFIRMATION_WINDOW_MS

        val statusText = when {
            lastConfirmedStepTotal == null -> "Starting step counter"
            recentlyCountedStep -> "Step counted now"
            else -> "Tracking active"
        }

        // Marker shown only while we haven't been able to promote with
        // LOCATION yet. Disappears once the user opens the app and we upgrade.
        val locSuffix = if (!hasLocationFgsType) " • (open app for full tracking)" else ""

        val text = "$statusText • $mode • ${formatSteps(steps)} steps$locSuffix"

        if (!force && text == lastFgsNotificationText) return
        if (!force && now - lastStepNotificationAtMs < NOTIF_MIN_UPDATE_INTERVAL_MS) return

        lastStepNotificationAtMs = now
        lastFgsNotificationText = text

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_FGS, buildFgsNotif(text))
    }

    private fun modeLabel(mode: StepCounterZC.MotionMode): String {
        return when (mode) {
            StepCounterZC.MotionMode.WALKING -> "Walking"
            StepCounterZC.MotionMode.RUNNING -> "Running"
            StepCounterZC.MotionMode.CYCLING -> "Cycling"
            StepCounterZC.MotionMode.DRIVING -> "Driving"
            StepCounterZC.MotionMode.STATIONARY -> "Stationary"
            StepCounterZC.MotionMode.STANDING_STILL -> "Standing still"
            else -> "Detecting mode"
        }
    }

    private fun formatSteps(steps: Int): String {
        return String.format(Locale.US, "%,d", steps)
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
                if (fgsStarted && hasLocationFgsType && hasLocPerm()) {
                    restartLocationForMode(mode)
                }
            }
        }
    }

    private fun cancelTapToResumeNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_TAP_TO_RESUME)
    }

    private fun startOrResume() {
        cancelTapToResumeNotification()

        if (!fgsStarted) {
            val ok = promoteToForeground()
            if (!ok) {
                Log.w(TAG, "promoteToForeground failed; delegating to TrackingRestartWorker.")
                TrackingRestartWorker.enqueue(applicationContext)
                stopSelf()
                return
            }
            fgsStarted = true
            startHeartbeat()
        } else if (!hasLocationFgsType && hasLocPerm()) {
            // Already foregrounded (HEALTH only). The fact that startOrResume
            // was called again — typically via ACTION_PERMS_UPDATED from the
            // MainActivity foreground — means we may now be in a state where
            // LOCATION promotion is allowed. Try to upgrade.
            tryUpgradeToLocationFgs()
        }

        if (!stepperStarted) {
            stepper.start()
            stepperStarted = true
        }

        historyStore.ensureCollegeBonusDay(currentDayKey())

        FourHourUploadScheduler.scheduleNext(applicationContext)

        // Only start location updates if our FGS promotion includes LOCATION
        // type. Requesting fused location updates from a HEALTH-only FGS would
        // throw on Android 14+. They get started later from inside
        // tryUpgradeToLocationFgs once we successfully upgrade.
        if (hasLocationFgsType) {
            startLocationUpdates()
            maybeSyncCollegeAreaSteps(force = false)
        } else {
            Log.i(TAG, "Skipping location updates — running in HEALTH-only mode.")
        }

        updateTrackingNotification(force = true)
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch {
            TrackingHeartbeat.markStepServiceAlive(applicationContext)
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                TrackingHeartbeat.markStepServiceAlive(applicationContext)
            }
        }
    }

    /**
     * Attempt initial promotion to foreground.
     *
     *  1. If location runtime permission is held, try HEALTH|LOCATION first.
     *  2. On SecurityException ("app must be in eligible state..."), fall back
     *     to HEALTH only. HEALTH has no foreground-only permission and is
     *     always allowed.
     *  3. Returns true if the service is now foregrounded with either type set.
     */
    private fun promoteToForeground(): Boolean {
        val canTryLocation = hasLocPerm()

        if (canTryLocation) {
            val combined = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID_FGS,
                    buildFgsNotif(initialNotificationText()),
                    combined
                )
                hasLocationFgsType = true
                Log.i(TAG, "Promoted with HEALTH|LOCATION.")
                return true
            } catch (e: SecurityException) {
                // The boot-time case: foreground-only permission can't be
                // used because the app isn't in an eligible state.
                Log.w(
                    TAG,
                    "Cannot promote with LOCATION at this time " +
                            "(likely background restriction). Falling back to HEALTH only.",
                    e
                )
                // fall through to HEALTH-only attempt
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "FGS start blocked entirely (HEALTH|LOCATION).", e)
                return false
            }
        }

        // HEALTH-only path. No foreground-only permission, no eligible-state
        // requirement -> works from boot.
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_FGS,
                buildFgsNotif(initialNotificationText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
            hasLocationFgsType = false
            Log.i(TAG, "Promoted with HEALTH only.")
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Health-only FGS start was blocked by Android.", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Health-only FGS start failed (FOREGROUND_SERVICE_HEALTH missing?)", e)
            false
        }
    }

    /**
     * Re-promote with HEALTH|LOCATION on top of an existing HEALTH-only FGS.
     * Called from startOrResume when ACTION_PERMS_UPDATED arrives from a
     * foreground MainActivity, which is when LOCATION promotion is allowed.
     *
     * No-op if already promoted with LOCATION, not foregrounded, or no
     * location permission. On success, kicks off location updates immediately.
     */
    private fun tryUpgradeToLocationFgs(): Boolean {
        if (!fgsStarted) return false
        if (hasLocationFgsType) return false
        if (!hasLocPerm()) return false

        val combined = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_FGS,
                buildFgsNotif(currentNotificationText()),
                combined
            )
            hasLocationFgsType = true
            Log.i(TAG, "Upgraded FGS type to HEALTH|LOCATION.")
            startLocationUpdates()
            maybeSyncCollegeAreaSteps(force = false)
            updateTrackingNotification(force = true)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "FGS upgrade to LOCATION still not allowed; staying HEALTH-only.", e)
            false
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "FGS upgrade to LOCATION blocked.", e)
            false
        }
    }

    private fun initialNotificationText(): String {
        val steps = lastConfirmedStepTotal ?: StepBus.steps.value
        val mode = modeLabel(StepBus.mode.value)
        return "Starting step counter • $mode • ${formatSteps(steps)} steps"
    }

    private fun currentNotificationText(): String {
        return lastFgsNotificationText ?: initialNotificationText()
    }

    private fun showTapToResumeNotification() {
        val text = "Open app to resume step counter"
        if (lastResumeNotificationText == text) return
        lastResumeNotificationText = text

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_TAP_TO_RESUME, buildTapToResumeNotif(text))
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

        // College-zone detection requires a fresh location fix, which we only
        // have if we've been promoted with LOCATION FGS type and are receiving
        // updates. In HEALTH-only mode, latestLocationFix is null and
        // isLatestLocationEligibleForCollegeBonus returns false fast.
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

        if (!force && !enoughForBatch && !enoughTimePassed) return

        collegeSyncInFlight = true
        lastCollegeSyncAttemptMs = nowMs

        scope.launch(Dispatchers.IO) {
            val latest = historyStore.loadCollegeBonusProgress(currentDayKey())

            try {
                if (latest.qualifiedSteps <= latest.lastSyncedQualifiedSteps) return@launch

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
        if (!hasLocationFgsType) {
            // Defensive: requesting location updates without LOCATION FGS
            // type on Android 14+ throws. Skip.
            Log.w(TAG, "Refusing to start location updates: no LOCATION FGS type.")
            return
        }

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
        if (!hasLocationFgsType) return
        restartLocationForMode(StepBus.mode.value)
    }

    private fun restartLocationForMode(mode: StepCounterZC.MotionMode) {
        when (mode) {
            StepCounterZC.MotionMode.DRIVING,
            StepCounterZC.MotionMode.CYCLING -> {
                startLocationUpdates(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    6_000L,
                    minDistanceMeters = 15f
                )
            }

            StepCounterZC.MotionMode.RUNNING,
            StepCounterZC.MotionMode.WALKING -> {
                startLocationUpdates(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    15_000L,
                    minDistanceMeters = 5f
                )
            }

            StepCounterZC.MotionMode.STATIONARY,
            StepCounterZC.MotionMode.STANDING_STILL -> {
                startLocationUpdates(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    30_000L,
                    minDistanceMeters = 10f
                )
            }

            else -> {
                startLocationUpdates(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    8_000L,
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
            .setContentTitle("GoForIt is tracking your steps")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
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
        updateTrackingNotification(force = false)
    }
}