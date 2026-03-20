package com.example.goforitGit.core.util.StepsUtils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.example.goforitGit.core.data.StepsData.StepHistoryStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Zero-crossing based step counter with motion mode classification.
 * -----------------------------------------------------------------
 * Uses accelerometer data to detect steps via vertical acceleration analysis,
 * with optional hardware step counter support in battery saver mode.
 */
class StepCounterZC private constructor(
    context: Context,
    private var debugLogs: Boolean = true
) : SensorEventListener {

    // ============================================================================
    // region COMPANION OBJECT - Singleton & Tunables
    // ============================================================================

    companion object {

        // --- Singleton Instance ---

        /**
         * Volatile modifier ensures:
         * 1) Reads and writes to the instance are not reordered by the compiler or processor.
         * 2) Changes made by one thread are visible to other threads immediately.
         */
        @Volatile
        private var instance: StepCounterZC? = null

        fun getInstance(context: Context, debugLogs: Boolean = true): StepCounterZC {
            val appCtx = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: StepCounterZC(appCtx, debugLogs).also { created ->
                    // Load persisted total immediately (even if start() is never called)
                    if (!created.historyLoaded) created.loadHistory()
                    instance = created
                }
            }
        }

        // --- Sensor Configuration ---

        private const val ACC_RATE_US = 20_000    // ≈50 Hz (good for step detection)
        private const val GYRO_RATE_US = 50_000   // ≈20 Hz (gyro is power-hungry)
        private const val BATCH_US = 100_000      // up to 100 ms batching to save wakeups (the number of times the CPU and the app thread have to be woken up to deliver sensor events)

        // --- Gravity Filter ---

        /** IIR LPF time constant (seconds) for gravity estimation
         *  It is a filter to smooth a signal by letting slow changes pass and suppressing fast changes/noise.
         *  RC stands for Resistor–Capacitor in analogy to an RC electrical low-pass circuit, which smooths out rapid voltage changes.
         * */
        private const val RC_GRAV = 0.80f

        // --- Step Detection Thresholds ---

        /** Minimum vertical-dominance ratio to consider a "step-like" movement*/
        private const val VDOM_MIN = 0.35f

        /** Base floor for dynamic thresholds */
        private const val THR_FLOOR = 0.12f

        /** Scale factor on σ for dynamic thresholds */
        private const val K_SIGMA = 2.2f

        /** Lower threshold as a fraction of high (hysteresis) */
        private const val HYST_FRAC = 0.55f

        // --- Cadence / Step Timing ---

        /** Plausible step period bounds (~20 to 200 steps/min) */
        private const val W_MIN = 0.30f
        private const val W_MAX = 3.00f

        /** Allowed deviation from wEst when validating a step */
        private const val DELTA_MIN = 0.26f
        private const val DELTA_FRAC = 0.36f

        /** EMA weight for updating cadence estimate */
        private const val ALPHA_W = 0.22f

        // --- Logging Tags ---

        private const val TAG_DIAG = "STEPDIAG"
        private const val TAG_STEP = "STEP"
    }

    // endregion

    // ============================================================================
    // region ENUMS & DATA CLASSES
    // ============================================================================

    /** Motion classification states */
    enum class MotionMode {
        UNKNOWN,
        STATIONARY,
        STANDING_STILL,
        WALKING,
        RUNNING,
        CYCLING,
        DRIVING
    }

    /** Step detection state machine phases */
    private enum class Phase {
        IDLE,
        ARMED_UP
    }

    /** Rolling window sample for activity classification */
    private data class WinSample(
        val tMs: Long,
        val vRatio: Float,
        val linRms: Float,
        val gyro: Float
    )

    /** Per-step feature data for analytics and persistence */
    private data class StepFeature(
        val timeMs: Long,
        val periodS: Float,
        val vRatio: Float,
        val amp: Float
    )

    /** Raw/derived sensor sample for graphs or debugging */
    data class Sample(
        val tNanos: Long,
        val ax: Float,
        val ay: Float,
        val az: Float,
        val gx: Float,
        val gy: Float,
        val gz: Float,
        val v: Float,
        val linMag: Float,
        val vRatio: Float
    )

    // endregion

    // ============================================================================
    // region PUBLIC OBSERVABLES
    // ============================================================================

    private val _stepCount = MutableStateFlow(0)
    val stepsFlow: StateFlow<Int> = _stepCount.asStateFlow()

    private val _spm = MutableStateFlow(0f)
    val spm: StateFlow<Float> = _spm.asStateFlow()

    private val _mode = MutableStateFlow(MotionMode.UNKNOWN)
    val mode: StateFlow<MotionMode> = _mode.asStateFlow()

    /** Hot stream of recent samples for graphs/diagnostics */
    private val _samples = MutableSharedFlow<Sample>(replay = 0, extraBufferCapacity = 256)
    val samples: SharedFlow<Sample> = _samples

    // endregion

    // ============================================================================
    // region ANDROID SENSORS
    // ============================================================================

    private val sm: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val stepSensor: Sensor? by lazy {
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    private val accel: Sensor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    } else {
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private val gyro: Sensor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    } else {
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    }

    // endregion

    // ============================================================================
    // region POWER MANAGEMENT
    // ============================================================================

    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private fun isPowerSaveMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }
    }

    private fun useHardwareOnlyForCounting(): Boolean {
        return isPowerSaveMode() && stepSensor != null
    }

    // endregion

    // ============================================================================
    // region HARDWARE STEP COUNTER STATE
    // ============================================================================

    private val hwPrefs = context.getSharedPreferences("hw_steps", Context.MODE_PRIVATE)
    private val KEY_HW_COUNT = "last_hw_count"

    @Volatile
    private var hwStepActive = false
    private var hwIgnoreUntilMs = 0L
    private var lastHwCount: Int? = null
    private var lastHwEventElapsedNs: Long? = null
    private var lastStoredStepMs: Long = 0L
    private var lastHwWallMs = 0L
    private val HW_IDLE_TIMEOUT_MS = 10_000L

    // endregion

    // ============================================================================
    // region GRAVITY & FILTER STATE
    // ============================================================================

    private var gX = 0f
    private var gY = 0f
    private var gZ = 0f

    private var prevUx = 0f
    private var prevUy = 0f
    private var prevUz = 0f
    private var prevGUnitMs = 0L
    private var havePrevGUnit = false

    private var lastTsNs: Long = 0L
    private var tSeconds: Float = 0f

    // endregion

    // ============================================================================
    // region RUNNING STATISTICS
    // ============================================================================

    private var vMean = 0f
    private var vM2 = 0f
    private var vCount = 0L

    private var thrHigh = THR_FLOOR
    private var thrLow = HYST_FRAC * THR_FLOOR

    // endregion

    // ============================================================================
    // region STEP DETECTION STATE MACHINE
    // ============================================================================

    private var phase = Phase.IDLE
    private var lastDownS: Float = -1f
    private var wEst: Float = 0f
    private var posPeak = 0f
    private var negPeak = 0f

    // endregion

    // ============================================================================
    // region ANTI-TILT / SPIN BLOCKING
    // ============================================================================

    private var spinBlockUntilMs = 0L
    private var tiltBlockUntilMs = 0L

    // endregion

    // ============================================================================
    // region MOTION MODE CLASSIFICATION STATE
    // ============================================================================

    private var pendingMode: MotionMode = MotionMode.UNKNOWN
    private var pendingSinceMs: Long = 0L

    @Volatile
    private var latestSpeedMps: Float? = null

    @Volatile
    private var accOnFootLikelyNow = false

    private var lastGyroMag = 0f
    private var lastGyroMs = 0L

    /** Rolling window for activity classification (~4s) */
    private val win = ArrayDeque<WinSample>()
    private val WIN_MS = 4_000L

    // endregion

    // ============================================================================
    // region STEP HISTORY & PERSISTENCE
    // ============================================================================

    private val stepTimesMs = ArrayDeque<Long>()
    private val recentSteps = ArrayDeque<StepFeature>()

    private val SHORT_WIN_MS = 10_000L
    private val stepTimesWinMs = ArrayDeque<Long>()

    private val MAX_RECENT_STEPS = 64
    private val MAX_TIMESTAMPS = 10_000

    private val store = StepHistoryStore(context.applicationContext)

    private var lastTsPersistWallMs: Long = 0L
    private var lastTsPersistCount: Int = 0
    private val TS_PERSIST_PERIOD_MS = 15_000L
    private val TS_PERSIST_EVERY_STEPS = 25

    private var historyLoaded = false

    // endregion

    // ============================================================================
    // region PUBLIC API - Speed Updates
    // ============================================================================

    /** Feed GPS speed from outside (FusedLocation, etc.) */
    fun updateSpeedMps(speedMps: Float?) {
        latestSpeedMps = speedMps
    }

    // endregion

    // ============================================================================
    // region PUBLIC API - Lifecycle
    // ============================================================================

    /** Clears counters, phases, running stats, FIFOs, and persists the empty state */
    fun reset() {
        _stepCount.value = 0
        phase = Phase.IDLE
        lastDownS = -1f
        wEst = 0f
        posPeak = 0f
        negPeak = 0f
        vMean = 0f
        vM2 = 0f
        vCount = 0
        stepTimesMs.clear()
        recentSteps.clear()
        persistHistory(forceTimestamps = true)
    }

    /** Lazy-loads history once, then registers the sensors */
    fun start() {
        if (!historyLoaded) loadHistory()

        stepSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            ?: Log.w(TAG_STEP, "step sensor not available")

        accel?.let { sm.registerListener(this, it, ACC_RATE_US, BATCH_US) }
            ?: Log.w(TAG_STEP, "Accelerometer not available")

        gyro?.let { sm.registerListener(this, it, GYRO_RATE_US, BATCH_US) }
            ?: Log.w(TAG_STEP, "Gyroscope not available")
    }

    /** Unregisters sensors and persists history */
    fun stop() {
        sm.unregisterListener(this)
        persistHistory(forceTimestamps = true)
    }

    // endregion

    // ============================================================================
    // region PUBLIC API - Step Queries
    // ============================================================================

    fun countStepsSince(sinceMs: Long): Int {
        if (stepTimesMs.isEmpty()) return 0

        val nowMs = System.currentTimeMillis()
        pruneShortWindow(nowMs)

        // Fast path: query falls within our short-window buffer
        if (sinceMs >= nowMs - SHORT_WIN_MS && stepTimesWinMs.isNotEmpty()) {
            var c = 0
            for (t in stepTimesWinMs) if (t >= sinceMs) c++
            return c
        }

        // Fallback for older queries
        var c = 0
        for (t in stepTimesMs) if (t >= sinceMs) c++
        return c
    }

    // endregion

    // ============================================================================
    // region SENSOR CALLBACKS
    // ============================================================================

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    /**
     * Main sensor event handler.
     *
     * Uses signed vertical acceleration with two-sided hysteresis to filter out single
     * random spikes and insists on a full movement pair (up then down) typical of gait.
     *
     * Cadence gating plus an EMA of stride period makes the detector follow the user's
     * pace while resisting isolated outliers.
     *
     * Amplitude and vertical-dominance requirements suppress hand-waving and pocket
     * jostling that lack strong, gravity-aligned movements.
     */
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> handleGyroscopeEvent(event)
            Sensor.TYPE_STEP_COUNTER -> handleHardwareStepCounterEvent(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometerEvent(event)
        }
    }

    // endregion

    // ============================================================================
    // region GYROSCOPE HANDLING
    // ============================================================================

    private fun handleGyroscopeEvent(event: SensorEvent) {
        val wx = event.values[0]
        val wy = event.values[1]
        val wz = event.values[2]
        val wMag = sqrt((wx * wx + wy * wy + wz * wz).toDouble())

        val now = System.currentTimeMillis()

        // Fast rotation (~rad/s) triggers spin blocking
        val blockMs = when {
            wMag > 4.0 -> 900L
            wMag > 3.0 -> 600L
            wMag > 2.0 -> 450L
            else -> 0L
        }
        if (blockMs > 0) {
            spinBlockUntilMs = max(spinBlockUntilMs, now + blockMs)
        }

        // Store data for activity classification
        lastGyroMag = wMag.toFloat()
        lastGyroMs = System.currentTimeMillis()
    }

    // endregion

    // ============================================================================
    // region HARDWARE STEP COUNTER HANDLING
    // ============================================================================

    private fun handleHardwareStepCounterEvent(event: SensorEvent) {
        // Use hardware step counting ONLY when device is in Battery Saver mode
        if (!isPowerSaveMode()) {
            hwStepActive = false
            lastHwCount = null
            lastHwEventElapsedNs = null
            return
        }

        hwStepActive = true

        val c = event.values[0].toInt()
        val tNs = event.timestamp

        val prevC = lastHwCount
        val prevNs = lastHwEventElapsedNs

        lastHwCount = c
        lastHwEventElapsedNs = tNs

        val savedC = hwPrefs.getInt(KEY_HW_COUNT, -1)

        // First event after process start: try to catch up using persisted last value
        if (prevC == null || prevNs == null) {
            if (savedC in 0..c) {
                val deltaCatchUp = c - savedC
                if (deltaCatchUp > 0) {
                    _stepCount.value += deltaCatchUp
                    persistHistory()
                }
            }
            hwPrefs.edit().putInt(KEY_HW_COUNT, c).apply()
            return
        }

        // Hardware counter can reset (e.g., sensor service restart / reboot)
        if (c < prevC) return

        val delta = c - prevC
        if (delta <= 0) return

        // --- Sanity filter: ignore suspicious HW bursts while "not on foot" ---
        val nowMs = System.currentTimeMillis()

        // If we are in a temporary "ignore HW" cooldown, keep baselines but don't count
        if (nowMs < hwIgnoreUntilMs) {
            hwStepActive = false
            lastHwWallMs = nowMs
            return
        }

        val currentMode = _mode.value
        val onFootContext =
                (currentMode == MotionMode.WALKING) ||
                (currentMode == MotionMode.RUNNING) ||
                (currentMode == MotionMode.UNKNOWN && accOnFootLikelyNow)

        val speedKmh: Float? = latestSpeedMps?.times(3.6f)
        val lowSpeed = speedKmh != null && speedKmh < 1.0f
        val antiTiltActive = nowMs < max(spinBlockUntilMs, tiltBlockUntilMs)

        // Check for flip-like activity (tilting phone while stationary)
        val flipLikely = !onFootContext && (speedKmh == null || lowSpeed) && antiTiltActive && delta >= 1
        if (flipLikely) {
            if (debugLogs) {
                Log.w(TAG_STEP, "[HW] ignored flip-like delta=$delta mode=$currentMode antiTilt=true")
            }
            hwIgnoreUntilMs = nowMs + 2_000L
            hwStepActive = false
            lastHwWallMs = nowMs
            return
        }

        // Only drop BIG bursts when we are NOT on-foot and GPS indicates basically not moving
        val suspiciousBurst = !onFootContext && (speedKmh == null || lowSpeed) && delta >= 5
        if (suspiciousBurst) {
            if (debugLogs) {
                Log.w(TAG_STEP, "[HW] ignored burst delta=$delta mode=$currentMode speedKmh=${speedKmh ?: -1f}")
            }
            hwIgnoreUntilMs = nowMs + 10_000L
            hwStepActive = false
            lastHwWallMs = nowMs
            return
        }

        // Mapping of sensor's elapsed time to wall-clock time (ms)
        val nowWallMs = nowMs
        val nowElapsedNs = SystemClock.elapsedRealtimeNanos()
        val tWallMs = nowWallMs - ((nowElapsedNs - tNs) / 1_000_000L)
        val prevWallMs = nowWallMs - ((nowElapsedNs - prevNs) / 1_000_000L)

        // Evenly distribute 'delta' steps across [prevWallMs .. tWallMs]
        var spanMs = tWallMs - prevWallMs
        if (spanMs < delta) spanMs = delta.toLong()

        var lastTs = lastStoredStepMs
        for (j in 1..delta) {
            var ts = prevWallMs + (j * spanMs) / delta
            if (ts <= lastTs) ts = lastTs + 1
            stepTimesMs.add(ts)
            stepTimesWinMs.add(ts)
            lastTs = ts
        }
        lastStoredStepMs = lastTs

        while (stepTimesMs.size > MAX_TIMESTAMPS) stepTimesMs.removeFirst()
        pruneShortWindow(nowMs)

        _stepCount.value += delta
        lastHwWallMs = nowMs

        persistHistory()
    }

    // endregion

    // ============================================================================
    // region ACCELEROMETER HANDLING
    // ============================================================================

    private fun handleAccelerometerEvent(event: SensorEvent) {
        // Determine if we should allow accelerometer-based step counting
        val nowMsWall = System.currentTimeMillis()
        val hwSilent = hwStepActive && (nowMsWall - lastHwWallMs) > HW_IDLE_TIMEOUT_MS
        if (hwSilent) hwStepActive = false

        val hwExclusive = useHardwareOnlyForCounting()
        val allowAccSteps = !hwExclusive && (!hwStepActive || hwSilent)

        // NOTE: Do NOT return early here! We still need to process for activity classification
        // even when in power-save mode. Only step detection should be skipped.

        // Initialize on first sample
        val ts = event.timestamp
        if (lastTsNs == 0L) {
            lastTsNs = ts
            gX = event.values[0]
            gY = event.values[1]
            gZ = event.values[2]
            return
        }

        // Compute delta time
        val dt = ((ts - lastTsNs).coerceAtLeast(1_000_000L)) / 1e9f
        lastTsNs = ts
        tSeconds += dt

        // Get raw acceleration values
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // Update gravity estimate via low-pass filter
        val alpha = dt / (RC_GRAV + dt)
        gX += alpha * (ax - gX)
        gY += alpha * (ay - gY)
        gZ += alpha * (az - gZ)

        // Compute linear acceleration
        val lx = ax - gX
        val ly = ay - gY
        val lz = az - gZ
        val linMag = sqrt(lx * lx + ly * ly + lz * lz)

        // Compute vertical projection (component along gravity direction)
        val gMag = sqrt(gX * gX + gY * gY + gZ * gZ)
        if (gMag < 1e-4f) return

        val ux = gX / gMag
        val uy = gY / gMag
        val uz = gZ / gMag
        val v = lx * ux + ly * uy + lz * uz

        // Vertical-dominance metric: 1.0 if motion is along gravity, 0.0 if fully horizontal
        val vRatio = abs(v) / (linMag + 1e-6f)

        // --- Activity Classification (ALWAYS runs, even in power save mode) ---
        val nowMs = System.currentTimeMillis()
        updateTiltBlocking(nowMs, ux, uy, uz)
        updateActivityWindow(nowMs, vRatio, linMag)
        classifyActivity(nowMs)

        // --- Running Statistics (Welford's algorithm) ---
        vCount += 1
        val dv = v - vMean
        vMean += dv / vCount
        vM2 += dv * (v - vMean)
        val sigmaV = if (vCount > 1) sqrt((vM2 / (vCount - 1)).toDouble()).toFloat() else 0f

        // Update dynamic thresholds
        val dynamicThr = max(THR_FLOOR, K_SIGMA * sigmaV)
        thrHigh = min(dynamicThr, 1.6f)
        thrLow = HYST_FRAC * thrHigh

        // --- Step Detection State Machine (ONLY when not in power-save/HW-exclusive mode) ---
        if (allowAccSteps) {
            processStepDetection(v, vRatio, nowMs)
        }

        // Emit sample for UI/debugging
        _samples.tryEmit(
            Sample(
                tNanos = ts,
                ax = ax, ay = ay, az = az,
                gx = gX, gy = gY, gz = gZ,
                v = v,
                linMag = linMag,
                vRatio = vRatio
            )
        )
    }


    private fun updateTiltBlocking(nowMs: Long, ux: Float, uy: Float, uz: Float) {
        if (havePrevGUnit) {
            val dtMs = nowMs - prevGUnitMs
            if (dtMs in 1..250) {
                val dot = (ux * prevUx + uy * prevUy + uz * prevUz).coerceIn(-1f, 1f)
                val angleRad = acos(dot.toDouble())
                val angleDeg = Math.toDegrees(angleRad)

                if (angleDeg >= 35.0) {
                    tiltBlockUntilMs = max(tiltBlockUntilMs, nowMs + 700L)
                }
            }
        }

        prevUx = ux
        prevUy = uy
        prevUz = uz
        prevGUnitMs = nowMs
        havePrevGUnit = true
    }

    private fun updateActivityWindow(nowMs: Long, vRatio: Float, linMag: Float) {
        win.add(WinSample(nowMs, vRatio = vRatio, linRms = linMag, gyro = lastGyroMag))
        while (win.isNotEmpty() && (nowMs - win.first().tMs) > WIN_MS) {
            win.removeFirst()
        }
    }

    private fun processStepDetection(v: Float, vRatio: Float, nowMs: Long) {
        when (phase) {
            Phase.IDLE -> {
                if (v > thrHigh && vRatio >= VDOM_MIN) {
                    phase = Phase.ARMED_UP
                    posPeak = v
                    negPeak = 0f
                }
            }

            Phase.ARMED_UP -> {
                if (v > posPeak) posPeak = v
                if (v < negPeak) negPeak = v

                if (v < -thrLow) {
                    val tNow = tSeconds
                    var accepted = false
                    var reason: String

                    if (lastDownS > 0f) {
                        val W = tNow - lastDownS
                        if (W in W_MIN..W_MAX) {
                            wEst = if (wEst <= 0f) W else (1f - ALPHA_W) * wEst + ALPHA_W * W
                            val tol = max(DELTA_MIN, DELTA_FRAC * wEst)

                            if (abs(W - wEst) <= tol) {
                                val amp = posPeak - negPeak
                                val okAmp = amp >= (2.0f * thrHigh)
                                val okRatio = vRatio >= VDOM_MIN

                                if (okAmp && okRatio) {
                                    accepted = true
                                    reason = "ok"
                                } else {
                                    reason = if (!okAmp) "amp" else "ratio"
                                }
                            } else {
                                reason = "cadence"
                            }
                        } else {
                            reason = "W"
                        }
                    } else {
                        wEst = 0f
                        reason = "prime"
                    }

                    val onFoot = when (_mode.value) {
                        MotionMode.WALKING, MotionMode.RUNNING -> true
                        MotionMode.UNKNOWN -> accOnFootLikelyNow
                        else -> false
                    }

                    val antiTiltActive = nowMs < max(spinBlockUntilMs, tiltBlockUntilMs)
                    if (antiTiltActive) {
                        accepted = false
                        reason = "tilt"
                    }

                    if (accepted && onFoot) {
                        val Wcur = if (lastDownS > 0f) (tNow - lastDownS) else wEst
                        lastDownS = tNow
                        commitStep(Wcur, posPeak, negPeak, vRatio)
                    } else {
                        if (debugLogs) {
                            Log.w(
                                TAG_DIAG,
                                "[P2.V] reject: $reason pos=%.3f neg=%.3f W=%.3f wEst=%.3f thr=%.3f ratio=%.2f"
                                    .format(
                                        posPeak, negPeak,
                                        if (lastDownS > 0) tNow - lastDownS else -1f,
                                        wEst, thrHigh, vRatio
                                    )
                            )
                        }
                        lastDownS = tNow
                    }

                    phase = Phase.IDLE
                    posPeak = 0f
                    negPeak = 0f
                }
            }
        }
    }

    // endregion

    // ============================================================================
    // region STEP COMMIT
    // ============================================================================

    private fun commitStep(W: Float, p: Float, n: Float, ratio: Float) {
        _stepCount.value += 1

        val nowMs = System.currentTimeMillis()

        // Update timestamp history
        stepTimesMs.add(nowMs)
        stepTimesWinMs.add(nowMs)
        pruneShortWindow(nowMs)

        while (stepTimesMs.size > MAX_TIMESTAMPS) stepTimesMs.removeFirst()

        val cutoff = nowMs - 3L * 24L * 60L * 60L * 1000L
        while (stepTimesMs.isNotEmpty() && stepTimesMs.first() < cutoff) {
            stepTimesMs.removeFirst()
        }

        // Update recent features
        recentSteps.add(
            StepFeature(
                timeMs = nowMs,
                periodS = if (W > 0f) W else wEst,
                vRatio = ratio,
                amp = (p - n)
            )
        )

        while (recentSteps.size > MAX_RECENT_STEPS) recentSteps.removeFirst()

        persistHistory()
    }

    // endregion

    // ============================================================================
    // region ACTIVITY CLASSIFICATION
    // ============================================================================

    private fun classifyActivity(nowMs: Long) {
        if (win.size < 20) {
            accOnFootLikelyNow = false
            return
        }

        // Calculate window statistics
        val n = win.size
        var sumVR = 0f
        var sumL2 = 0f
        var sumG2 = 0f

        for (s in win) {
            sumVR += s.vRatio
            sumL2 += s.linRms * s.linRms
            sumG2 += s.gyro * s.gyro
        }

        val vAvg = sumVR / n
        val linRms = sqrt(sumL2 / n)
        val gyroRms = sqrt(sumG2 / n)
        val speed = latestSpeedMps

        // Calculate steps per second over the window
        val stepRate = countStepsSince(nowMs - WIN_MS).toFloat() / (WIN_MS / 1000f)

        // Update SPM
        val spm = stepRate * 60f
        _spm.value = spm

        // Step evidence calculation
        val steps4s = countStepsSince(nowMs - 4000L)
        val lastStepFresh = stepTimesMs.isNotEmpty() && (nowMs - stepTimesMs.last() <= 1500L)
        val hwRecent = hwStepActive && (nowMs - lastHwWallMs) <= 2_000L
        val hasStepEvidence = (steps4s >= 3) || lastStepFresh || hwRecent

        val accOnFootLikely = (vAvg >= 0.45f && linRms >= 0.80f && gyroRms <= 3.0f)
        accOnFootLikelyNow = accOnFootLikely
        val hasStepOrCadence = hasStepEvidence || accOnFootLikely

        // Speed in km/h
        val speedKmh = (speed ?: -1f) * 3.6f

        // Dynamic running floor based on GPS speed
        val runningFloorSpm = when {
            speedKmh >= 14f -> 145f
            speedKmh >= 12f -> 140f
            speedKmh >= 8f -> 125f
            else -> 135f
        }

        // --- Activity Detection Flags ---

        val runningBySteps = hasStepEvidence && (
                spm >= runningFloorSpm ||
                        (speedKmh >= 8f && spm >= 115f) ||
                        (spm >= 130f && wEst > 0f && wEst <= 0.55f)
                )

        val walkingBySteps = hasStepEvidence && !runningBySteps && spm in 40f..140f

        val drivingByGps = (speed ?: -1f) >= 6.94f && !hasStepEvidence
        val drivingBySensors = (vAvg < 0.25f && stepRate < 0.2f && gyroRms < 0.35f && linRms in 0.10f..2.50f) && !hasStepEvidence

        val cyclingByGps = (speed ?: -1f) in 3.0f..6.8f && !hasStepOrCadence
        val cyclingBySensors = (vAvg in 0.28f..0.55f && gyroRms in 0.60f..2.50f && stepRate < 0.25f) && !hasStepOrCadence

        val gyroAvailable = (gyro != null)
        val freshGyro = !gyroAvailable || lastGyroMs == 0L || (nowMs - lastGyroMs) <= 5_000L

        val stillBySensors = stepRate < 0.02f &&
                linRms < 0.20f &&
                (!gyroAvailable || gyroRms < 0.15f) &&
                freshGyro

        val standingBySensors = !hasStepEvidence &&
                stepRate < 0.20f &&
                linRms < 1f &&
                (!gyroAvailable || gyroRms <= 1.00f) &&
                freshGyro

        // --- Compose raw guess with "steps get priority" rule ---
        val rawGuess = when {
            hasStepEvidence && runningBySteps -> MotionMode.RUNNING
            hasStepEvidence && walkingBySteps -> MotionMode.WALKING
            drivingByGps || drivingBySensors -> MotionMode.DRIVING
            cyclingByGps || cyclingBySensors -> MotionMode.CYCLING
            stillBySensors -> MotionMode.STATIONARY          // ← More specific, check FIRST
            standingBySensors -> MotionMode.STANDING_STILL   // ← Less specific, check SECOND
            else -> MotionMode.UNKNOWN
        }

        // Apply hysteresis
        applyModeWithHysteresis(
            nowMs,
            rawGuess,
            hasStepEvidence,
            (drivingByGps || drivingBySensors || cyclingByGps || cyclingBySensors)
        )

        if (debugLogs) {
            Log.i(
                TAG_DIAG,
                "[MODE?] raw=$rawGuess vAvg=%.2f linRms=%.2f gyroRms=%.2f stepRate=%.2f speed=%s"
                    .format(vAvg, linRms, gyroRms, stepRate, speed?.toString() ?: "n/a")
            )
        }
    }

    // endregion

    // ============================================================================
    // region MODE HYSTERESIS
    // ============================================================================

    private fun minDwellToEnter(m: MotionMode): Long = when (m) {
        MotionMode.RUNNING -> 200L
        MotionMode.WALKING -> 200L
        MotionMode.CYCLING -> 400L
        MotionMode.DRIVING -> 400L
        MotionMode.STANDING_STILL -> 200L
        MotionMode.STATIONARY -> 300L
        else -> 200L
    }

    private fun dwellForTransition(
        current: MotionMode,
        target: MotionMode,
        hasStepEvidence: Boolean,
        vehicleLikely: Boolean
    ): Long {
        val speedKmh = (latestSpeedMps ?: -1f) * 3.6f

        // Immediate on-foot if we have step evidence
        if ((target == MotionMode.WALKING || target == MotionMode.RUNNING) && hasStepEvidence) {
            return 0L
        }

        // Immediate vehicle if clearly fast and no steps now
        if ((target == MotionMode.DRIVING || target == MotionMode.CYCLING) &&
            !hasStepEvidence && (speedKmh >= 25f || (vehicleLikely && speedKmh >= 15f))
        ) {
            return 0L
        }

        // Very quick between WALKING <-> RUNNING
        if ((current == MotionMode.WALKING && target == MotionMode.RUNNING) ||
            (current == MotionMode.RUNNING && target == MotionMode.WALKING)
        ) {
            return 200L
        }

        // Quick from STATIONARY <-> WALKING
        if ((current == MotionMode.STATIONARY && target == MotionMode.WALKING) ||
            (current == MotionMode.WALKING && target == MotionMode.STATIONARY)
        ) {
            return 300L
        }

        // Quick around standing
        if ((current == MotionMode.STATIONARY && target == MotionMode.STANDING_STILL) ||
            (current == MotionMode.STANDING_STILL && target == MotionMode.STATIONARY)
        ) {
            return 200L
        }

        if ((current == MotionMode.STANDING_STILL && target == MotionMode.WALKING) ||
            (current == MotionMode.WALKING && target == MotionMode.STANDING_STILL)
        ) {
            return 250L
        }

        return -1L // signal: use fallback minDwellToEnter(target)
    }

    private fun applyModeWithHysteresis(
        nowMs: Long,
        rawGuess: MotionMode,
        hasStepEvidence: Boolean,
        vehicleLikely: Boolean
    ) {
        val current = _mode.value
        val speedKmh = (latestSpeedMps ?: 0f) * 3.6f

        // If currently in a vehicle mode, be conservative about leaving it
        if ((current == MotionMode.DRIVING || current == MotionMode.CYCLING) &&
            (rawGuess == MotionMode.WALKING ||
                    rawGuess == MotionMode.RUNNING ||
                    rawGuess == MotionMode.STANDING_STILL ||
                    rawGuess == MotionMode.STATIONARY ||
                    rawGuess == MotionMode.UNKNOWN)
        ) {
            val vehicleBandMinKmh = 11f
            if (speedKmh >= vehicleBandMinKmh) return
        }

        // While currently on-foot, don't drop to vehicle/unknown if steps are still coming
        if ((current == MotionMode.WALKING || current == MotionMode.RUNNING) &&
            (rawGuess == MotionMode.CYCLING || rawGuess == MotionMode.DRIVING ||
                    rawGuess == MotionMode.UNKNOWN || rawGuess == MotionMode.STATIONARY)
        ) {
            if (hasStepEvidence && speedKmh < 25f) return
        }

        // To enter a vehicle mode, require either: no step evidence or clear GPS speed
        if ((rawGuess == MotionMode.DRIVING || rawGuess == MotionMode.CYCLING) &&
            hasStepEvidence && speedKmh < 25f
        ) {
            return
        }

        // Compute transition-aware dwell
        val fast = dwellForTransition(current, rawGuess, hasStepEvidence, vehicleLikely)
        val dwell = if (fast >= 0) fast else minDwellToEnter(rawGuess)

        // Handle new candidate mode
        if (rawGuess != pendingMode) {
            pendingMode = rawGuess
            pendingSinceMs = nowMs

            if (dwell == 0L && rawGuess != current) {
                _mode.value = rawGuess
                if (debugLogs) Log.i(TAG_DIAG, "[MODE] $rawGuess (instant)")
            }
            return
        }

        // Same candidate as last tick — check dwell time
        if (rawGuess != current && (nowMs - pendingSinceMs) >= dwell) {
            _mode.value = rawGuess
        }
    }

    // endregion

    // ============================================================================
    // region PERSISTENCE
    // ============================================================================

    private fun pruneShortWindow(nowMs: Long) {
        val cutoff = nowMs - SHORT_WIN_MS
        while (stepTimesWinMs.isNotEmpty() && stepTimesWinMs.first() < cutoff) {
            stepTimesWinMs.removeFirst()
        }
    }

    private fun persistHistory(forceTimestamps: Boolean = false) {
        val nowMs = System.currentTimeMillis()

        val recent = recentSteps.map {
            StepHistoryStore.RecentStep(it.timeMs, it.periodS, it.vRatio, it.amp)
        }

        val stepsNow = _stepCount.value
        val shouldPersistTs = forceTimestamps ||
                stepTimesMs.isEmpty() ||
                lastTsPersistWallMs == 0L ||
                (nowMs - lastTsPersistWallMs) >= TS_PERSIST_PERIOD_MS ||
                (stepsNow - lastTsPersistCount) >= TS_PERSIST_EVERY_STEPS

        if (shouldPersistTs) {
            store.saveSnapshot(totalSteps = stepsNow, recent = recent, timestamps = stepTimesMs, nowMs = nowMs)
            lastTsPersistWallMs = nowMs
            lastTsPersistCount = stepsNow
        } else {
            store.saveSnapshot(totalSteps = stepsNow, recent = recent, timestamps = null, nowMs = nowMs)
        }
    }

    private fun loadHistory() {
        historyLoaded = true
        val now = System.currentTimeMillis()

        // Load legacy timestamp history
        for (t in store.loadStepTimestamps()) stepTimesMs.add(t)

        // Prune anything older than 3 days
        val cutoff = now - 3L * 24L * 60L * 60L * 1000L
        while (stepTimesMs.isNotEmpty() && stepTimesMs.first() < cutoff) stepTimesMs.removeFirst()
        while (stepTimesMs.size > MAX_TIMESTAMPS) stepTimesMs.removeFirst()

        // Load recent features
        for (r in store.loadRecentStepFeatures()) {
            recentSteps.add(StepFeature(r.timeMs, r.periodS, r.vRatio, r.amp))
        }
        while (recentSteps.size > MAX_RECENT_STEPS) recentSteps.removeFirst()

        val persistedTotal = store.loadTotalSteps() ?: -1

        stepTimesWinMs.clear()
        val cutoffWin = now - SHORT_WIN_MS
        for (t in stepTimesMs) {
            if (t >= cutoffWin) stepTimesWinMs.add(t)
        }

        _stepCount.value = if (persistedTotal >= 0) persistedTotal else stepTimesMs.size
    }

    // endregion
}