package com.example.goforit.count_step_module

import java.util.ArrayDeque as JArrayDeque
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Axis kept for compatibility; ZC uses vertical projection (or dominant-axis fallback). */
enum class Axis { X, Y, Z }

interface StepListener {
    fun onSteps(delta: Int, total: Int)
    fun onDebug(d: StepCounterZC.Debug) {}
}

/** Simple EMA filter. */
class Ema(private val alpha: Double) {
    private var init = false
    private var v = 0.0
    fun update(x: Double): Double {
        v = if (!init) {
            init = true; x
        } else (1 - alpha) * v + alpha * x
        return v
    }

    fun value(): Double = v
}

/** tiny, fast fixed-size sliding window (ring buffer)
 * that tracks the running sum of the last N values so
 * you can compute moving averages/energies in O(1) per sample.
 * */
class RollingWindow(private val cap: Int) {
    private val buf = DoubleArray(cap)
    private var head = 0
    private var filled = 0
    var sum: Double = 0.0
        private set

    fun push(x: Double): Double {
        if (cap <= 0) return sum
        if (filled < cap) {
            buf[head] = x
            head = (head + 1) % cap
            filled++
            sum += x
        } else {
            val old = buf[head]
            buf[head] = x
            head = (head + 1) % cap
            sum += x - old
        }
        return sum
    }

    fun size(): Int = filled
    fun clear() {
        head = 0; filled = 0; sum = 0.0
    }
}

data class Sample(
    val timestampNs: Long,
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val wx: Float, val wy: Float, val wz: Float
)

/** Tunables (several auto-tuned in calibrateRest). */
data class StepCounterZCConfig(
    // =========================
    // ZC band & smoothing
    // =========================

    // cBand: Multiplier for the zero-crossing (ZC) hysteresis band.
    // The actual band is: band = cBand * stdRestAxis (m/s^2).
    // Lower → more sensitive (easier to exit the band, more steps).
    // Higher → more conservative (fewer false flips but may miss small swings).
    // Typical: 1.0–1.5. CalibrateRest can auto-tune this.
    val cBand: Double = 1.0,

    // alpha: EMA smoothing factor (0..1) for the 1-D ZC signal.
    // Higher → snappier, follows changes quickly; Lower → smoother, less noise.
    // Derived from a time constant (~0.25 s) during calibration; can be overridden.
    val alpha: Double = 0.5,

    // axis: Legacy axis hint. The detector actually projects onto gravity (“vertical”)
    // or uses the dominant-motion fallback; this field remains for compatibility.
    // Has no effect on the current projection logic.
    // the previous emphasis was on the Y Axis (val axis: Axis = Axis.Y,)
    val axis: Axis = Axis.Y,

    // ==================================
    // SMA walking gate (per-sample)
    // ==================================

    // smaK: How many standard deviations above rest the 1-second average of
    // |lx|+|ly|+|lz| (Signal Magnitude “Area” average) must be to declare “walking”.
    // Lower → higher recall (easier to call walking); Higher → higher precision.
    // Typical: 1.2–1.8 (hand carry tends to prefer 1.2–1.5).
    val smaK: Double = 1.2,

    // smaWindow: Window length in samples for the SMA/energy/RMS windows.
    // ~1 s @ 20 Hz by default; CalibrateRest resizes this from fsHz so it
    // stays ~1 s across devices/rates. Larger window → steadier but slower.
    val smaWindow: Int = 20,          // ~1 s @ 20 Hz (auto-sized in calibrate)

    // ==========
    // Debounce
    // ==========

    // minStepIntervalMs: Minimum time between counted steps to avoid double
    // triggers. Should be below the period at cadenceMaxHz. E.g., 300 ms
    // corresponds to ~3.33 Hz max theoretical rate.
    val minStepIntervalMs: Long = 300,

    // ======================================================
    // Tilt suppression (rotation-dominant + limited translation)
    // ======================================================

    // tiltOmegaThreshRadS: Gyro magnitude threshold (rad/s) required to
    // consider “tilt” (rotation must be at least this fast).
    val tiltOmegaThreshRadS: Double = 2.0,

    // tiltLinAccelThresh: Max allowed linear-accel magnitude (m/s^2) while still
    // calling it a tilt. Higher lets normal arm swing (translation) pass without
    // suppressing steps; too high lets shakes be mis-labeled as tilt.
    val tiltLinAccelThresh: Double = 7.0,     // ↑ allow arm-swing translation

    // tiltOmegaToLinRatio: Required dominance of rotation over translation
    // (omega/lin) to call something a tilt. Increase to make tilts rarer.
    val tiltOmegaToLinRatio: Double = 1.4,    // ↑ rotation must dominate to call it “tilt”

    // tiltHoldMs: Latch/hold time after a tilt trigger during which steps are
    // suppressed. Shorter = less masking of legitimate strides.
    val tiltHoldMs: Long = 280,               // ↓ shorter latch so tilts don’t mask strides

    // tiltOmegaEmaAlpha: EMA smoothing factor for the gyro magnitude used by the tilt gate.
    // Higher = more responsive, lower = smoother.
    val tiltOmegaEmaAlpha: Double = 0.20,

    // tiltLinEmaAlpha: EMA smoothing factor for the linear-accel magnitude used by the tilt gate.
    // Higher = more responsive, lower = smoother.
    val tiltLinEmaAlpha: Double = 0.20,

    // ======================================================
    // Orientation-change path (gravity direction rotation rate)
    // ======================================================

    // tiltUseGDirPath: Enable the gravity-direction rotation-rate tilt path.
    // Useful for catching pure tilts where the phone rotates but doesn’t translate much.
    val tiltUseGDirPath: Boolean = true,

    // tiltGDirRateThreshRadS: Threshold (rad/s) on how fast the gravity direction
    // rotates (angle/second). Raise to avoid latching during normal arm swing.
    val tiltGDirRateThreshRadS: Double = 2.4, // ↑ avoid latching on normal arm swing

    // tiltGDirEmaAlpha: EMA smoothing for the gravity-direction rotation rate.
    // Higher = more responsive, lower = smoother.
    val tiltGDirEmaAlpha: Double = 0.30,

    // =================================================================
    // Optional "shake" path (large translation, little rotation, non-vertical)
    // =================================================================

    // tiltShakeUse: If true, also suppress during high translation + low rotation
    // (anti-cheat/anti-shake). Off by default because hand-carry often has
    // moderate translation that shouldn’t be suppressed.
    val tiltShakeUse: Boolean = false,        // keep disabled for hand-carry

    // tiltShakeOmegaMax: Max gyro magnitude (rad/s) for an event to be considered
    // a “shake” (only used if tiltShakeUse = true).
    val tiltShakeOmegaMax: Double = 0.8,

    // tiltShakeLinMin: Min linear-accel (m/s^2) to qualify as a shake (only if tiltShakeUse).
    val tiltShakeLinMin: Double = 6.5,

    // ============================
    // Walking-likeness gates
    // ============================

    // vertRmsRatioMin: Required vertical dominance ratio for walking:
    // vRms / (hRms + ε) ≥ vertRmsRatioMin. Hand carry is noisier horizontally;
    // 1.2 is a realistic baseline (pocket can use 1.3–1.5).
    val vertRmsRatioMin: Double = 1.1,        // ↓ realistic baseline for hand-carry

    // cadenceMinHz: Lower cadence bound (Hz) for walking (≈ steps per second).
    // 1.0 Hz ≈ 60 spm (slow walk).
    val cadenceMinHz: Double = 1.0,

    // cadenceMaxHz: Upper cadence bound (Hz) for walking.
    // 2.8 Hz ≈ 168 spm (very brisk); helps avoid overlapping into running.
    val cadenceMaxHz: Double = 2.8,           // ↓ avoid overlapping into running

    // cadenceCvMax: Max coefficient of variation of recent inter-ZC intervals.
    // Lower = stricter regularity requirement; Higher = more tolerant.
    // Typical steady-state 0.30–0.40; we ramp in early steps in code.
    val cadenceCvMax: Double = 0.30,          // ↓ a bit stricter steady-state

    // ============================
    // Robust ZC (band crossing)
    // ============================

    // bandRearmMs: Required dwell time (ms) the signal must spend back INSIDE the band
    // before the next zero-crossing is allowed. Prevents “ping-pong” on the edge.
    val bandRearmMs: Long = 60,               // ↑ a touch more dwell

    // outMinConsec: Minimum number of CONSECUTIVE samples that must be OUTSIDE the band
    // before accepting a flip. 1 = very sensitive (risk noise), 2–3 = more robust.
    val outMinConsec: Int = 1,

    // outOvershoot: How far beyond the band the signal must go (as a multiple of band) before accepting.
    // >1.0 avoids edge grazes (e.g., 1.05 = 5% beyond).
    val outOvershoot: Double = 1.05,          // ↓ slightly easier exits (small hand swing)

    // minSlopeAbs: Minimum absolute slope |d(smoothAxis)/dt| at the band exit
    // (units: m/s^2 per s). Higher rejects slow drifts; lower increases sensitivity.
    val minSlopeAbs: Double = 3.5             // ↑ still requires motion, but not too strict
)



/**
 * Advanced ZC step counter with dominant-axis fallback for hand-carry.
 **/
class StepCounterZC(
    private var cfg: StepCounterZCConfig = StepCounterZCConfig(),
    private val listener: StepListener? = null
) {

    // Dominant-axis (unit) with hemisphere lock
    private var dUx = 1.0;
    private var dUy = 0.0;
    private var dUz = 0.0
    private var haveDom = false
    private val domAlphaInBand = 0.05   // update only while in-band (~1 s @20 Hz)

    // DC remover for zcSignal (very slow EMA)
    private var dcEma = Ema(0.02)

    // ---- Calibration (rest stats) ----
    var muRestSMA: Double = 0.0; private set
    var sdRestSMA: Double = 0.0; private set
    var stdRestAxis: Double = 0.0; private set // σ of vertical at rest
    var calibrated: Boolean = false; private set

    // ---- Windows ----
    private var smaWin = RollingWindow(cfg.smaWindow)   // |lin|1
    private var vertEWin = RollingWindow(cfg.smaWindow)   // aVert^2
    private var horizEWin = RollingWindow(cfg.smaWindow)   // horiz^2

    // ---- Filters ----
    private var axisEma = Ema(cfg.alpha)                 // ZC signal (vertical or dominant)
    private var omegaEma = Ema(cfg.tiltOmegaEmaAlpha)
    private var linEma = Ema(cfg.tiltLinEmaAlpha)
    private var gDirEma = Ema(cfg.tiltGDirEmaAlpha)

    // ---- State ----
    private var lastTiltMs: Long = -1
    private var lastNonZeroSide: Int = 0
    private var inBand: Boolean = true
    private var lastCountedMs: Long = -1

    // Prev gravity direction for orientation-change rate
    private var hasPrevU = false
    private var prevUnitGravitationVectorX = 0.0;
    private var prevUnitGravitationVectorY = 0.0;
    private var prevUnitGravitationVectorZ = 0.0
    private var prevUTsNs: Long = 0L

    // Dominant-motion direction (EMA) for fallback when verticalDominant=false
    private var domX = 0.0;
    private var domY = 0.0;
    private var domZ = 0.0
    private val domAlpha = 0.05  // ~1 s memory at ~20 Hz

    // Robust ZC state
    private var inBandSinceMs: Long = -1
    private var outsideSinceMs: Long = -1
    private var outsideConsec: Int = 0
    private var prevSmoothAxis = 0.0
    private var prevSmoothTsMs: Long = -1

    // Recent ZC times for cadence estimation
    private val zcTimesMs: JArrayDeque<Long> = JArrayDeque(8)

    // snappier EMA for small hand swings
    private var axisEmaFast = Ema(0.65)


    var totalSteps: Int = 0; private set

    data class Debug(
        val nowMs: Long,
        val avgSma: Double,
        val threshAvgSma: Double,
        val walkingNow: Boolean,
        val omega: Double,
        val lin: Double,
        val isHandTilted: Boolean,
        val band: Double,
        val smoothAxis: Double,
        val vertRatio: Double,
        val cadenceHz: Double,
        val gDirRate: Double
    )

    fun addSample(sm: Sample) {
        val nowMs = sm.timestampNs / 1_000_000L

        // ---- Linear acceleration (a - g) & magnitudes ----
        val lx = (sm.ax - sm.gx).toDouble()
        val ly = (sm.ay - sm.gy).toDouble()
        val lz = (sm.az - sm.gz).toDouble()
        val linMagRaw = sqrt(lx * lx + ly * ly + lz * lz)
        val omegaMagRaw = sqrt((sm.wx * sm.wx + sm.wy * sm.wy + sm.wz * sm.wz).toDouble())

        // ---- SMA Average (“mean area rate”) calculation on top of the supplied window (L1 norm of linear accel) ----
        val smaPoint = abs(lx) + abs(ly) + abs(lz)
        val smaSum = smaWin.push(smaPoint)
        val filled = smaWin.size()
        val avgSma = if (filled > 0) smaSum / filled else 0.0

        // ---- Gravity unit vector (for projection) ----
        val gxD = sm.gx.toDouble();
        val gyD = sm.gy.toDouble();
        val gzD = sm.gz.toDouble()
        val gNorm = sqrt(gxD * gxD + gyD * gyD + gzD * gzD)
        // the x,y,z components of the gravitation unit vector
        val (unitGravitationVectorX, unitGravitationVectorY, unitGravitationVectorZ) = if (gNorm > 1e-6)
            arrayOf(gxD / gNorm, gyD / gNorm, gzD / gNorm)
        else arrayOf(0.0, 1.0, 0.0)

        // ---- Vertical vs horizontal energy (RMS ratio gate) ----
        // The projection of vector l (Linear Acceleration vector) on vector u (Gravity unit vector)
        // is just “how much of vector l points along vector u” by the Dot product: LinAccelVert = l · u
        // if the dot product is equal to 0 then the vectors are Orthogonal

        // the linear acceleration magnitude
        val linearAccelerationMagnitudeSquared = linMagRaw * linMagRaw

        // accelVertical is the part of motion along gravity (up–down direction)
        val verticalLinearAcceleration = lx * unitGravitationVectorX + ly * unitGravitationVectorY + lz * unitGravitationVectorZ

        // the square of the vertical component, used for RMS/energy over a window
        val verticalAccelerationSquared = verticalLinearAcceleration * verticalLinearAcceleration

        // The squared magnitude of the horizontal (non-vertical) part of motion
        // That line is just splitting the 3-D linear acceleration into horizontal part
        // using the Pythagorean identity of: |linAccel|^2 = |linVertical|^2 + |linHorizontal|^2
        val horizontalAccelerationSquared = max(0.0, linearAccelerationMagnitudeSquared - verticalAccelerationSquared)

        // collecting of vertical and horizontal acceleration samples into our time windows
        vertEWin.push(verticalAccelerationSquared)
        horizEWin.push(horizontalAccelerationSquared)
        // root-mean-square of the vertical linear acceleration over the recent window
        val vRms = sqrt(vertEWin.sum / max(1, vertEWin.size()).toDouble())
        // root-mean-square of the horizontal (non-vertical) linear acceleration over the recent window
        val hRms = sqrt(horizEWin.sum / max(1, horizEWin.size()).toDouble())
        // the ratio between vertical acceleration and horizontal acceleration
        val vertRatio = vRms / (hRms + 1e-9)
        // is the vertical component is dominant in the current linear acceleration
        // it is meant to check if motion is mostly up-down
        val verticalDominant = vertRatio >= cfg.vertRmsRatioMin

        // ---- Tilt gate signals (EMAs) ----
        val omega = omegaEma.update(omegaMagRaw)
        val lin = linEma.update(linMagRaw)

        // Orientation-change rate of the gravity direction (rad/s)
        val dtU = (sm.timestampNs - prevUTsNs) / 1e9
        var gDirRateInst = 0.0
        if (hasPrevU && dtU > 1e-6) {
            val dot = (unitGravitationVectorX * prevUnitGravitationVectorX + unitGravitationVectorY * prevUnitGravitationVectorY + unitGravitationVectorZ * prevUnitGravitationVectorZ).coerceIn(-1.0, 1.0)
            val angle = acos(dot)
            gDirRateInst = angle / dtU
        }
        val gDirRate = gDirEma.update(gDirRateInst)
        prevUnitGravitationVectorX = unitGravitationVectorX; prevUnitGravitationVectorY = unitGravitationVectorY; prevUnitGravitationVectorZ = unitGravitationVectorZ
        prevUTsNs = sm.timestampNs
        hasPrevU = true

        // ----- Tilt logic -----
        val ratioOK = omega / (lin + 1e-9) >= cfg.tiltOmegaToLinRatio

        val tiltRaw1 = (omega >= cfg.tiltOmegaThreshRadS) &&
                (lin <= cfg.tiltLinAccelThresh) &&
                ratioOK

        val tiltRaw2 = cfg.tiltUseGDirPath &&
                (gDirRate >= cfg.tiltGDirRateThreshRadS) &&
                (lin <= 6.0) &&   // prevent latching during arm-swing translation
                !verticalDominant

        val shakeRaw = cfg.tiltShakeUse &&
                (omega <= cfg.tiltShakeOmegaMax) &&
                (lin >= cfg.tiltShakeLinMin) &&
                !verticalDominant

        val tiltRaw = tiltRaw1 || tiltRaw2 || shakeRaw
        if (tiltRaw) lastTiltMs = nowMs
        val isHandTilted = (nowMs - lastTiltMs) <= cfg.tiltHoldMs

        // ---- Walking gate (avg SMA above rest) ----
        val threshAvgSma = muRestSMA + cfg.smaK * sdRestSMA
        val walkingNow = calibrated &&
                sdRestSMA > 0.0 &&
                filled >= cfg.smaWindow &&
                avgSma > threshAvgSma

        // ---- Online rest baseline adaptation when not walking ----
        if (calibrated && !walkingNow && filled >= cfg.smaWindow) {
            val beta = 0.02
            val delta = avgSma - muRestSMA
            muRestSMA += beta * delta
            val varOld = sdRestSMA * sdRestSMA
            val varNew = (1 - beta) * varOld + beta * (delta * delta)
            sdRestSMA = sqrt(varNew)
        }

        // ---- Dominant-axis fallback for ZC signal ----
        // Track candidate direction from the current linear accel
        val lNorm = sqrt(lx * lx + ly * ly + lz * lz)
        if (lNorm > 1e-6 && inBand) {              // <— update direction ONLY while re-armed in band
            var nx = lx / lNorm;
            var ny = ly / lNorm;
            var nz = lz / lNorm

            val dotH = nx * dUx + ny * dUy + nz * dUz
            val s = if (!haveDom || dotH >= 0.0) 1.0 else -1.0
            nx *= s; ny *= s; nz *= s

            // Slow EMA update
            dUx = (1 - domAlphaInBand) * dUx + domAlphaInBand * nx
            dUy = (1 - domAlphaInBand) * dUy + domAlphaInBand * ny
            dUz = (1 - domAlphaInBand) * dUz + domAlphaInBand * nz
            val dn = sqrt(dUx * dUx + dUy * dUy + dUz * dUz)
            if (dn > 1e-6) {
                dUx /= dn; dUy /= dn; dUz /= dn; haveDom = true
            }
        }

        // Choose projection for ZC: vertical by default; dominant-axis if vertical is weak
        val useDominant = !verticalDominant && haveDom
        val zcRaw = if (!useDominant) verticalLinearAcceleration else (lx * dUx + ly * dUy + lz * dUz)

        // commented DC bias so we actually cross zero
        // val zcBias = dcEma.update(zcRaw)
        // val zcSignal = zcRaw - zcBias
        val zcSignal = zcRaw


        // Slightly wider band only in fallback
        val bandScale = if (!useDominant) 1.0 else 1.2

        // ---- ZC signal: EMA of chosen projection ----
        val smoothAxis =
            if (useDominant) axisEmaFast.update(zcSignal)
            else             axisEma.update(zcSignal)

        // cap the band to avoid swallowing small hand-carry swings
        val band = min(1.2, cfg.cBand * stdRestAxis * bandScale)

        // Track slope for "fast enough" exits (m/s^2 per s)
        var dSmoothDtAbs = 0.0
        if (prevSmoothTsMs > 0) {
            val dtSec = (nowMs - prevSmoothTsMs) / 1000.0
            if (dtSec > 1e-6) dSmoothDtAbs = abs((smoothAxis - prevSmoothAxis) / dtSec)
        }
        prevSmoothAxis = smoothAxis
        prevSmoothTsMs = nowMs

        // ---- Robust ZC with re-arm, overshoot, multi-sample confirm ----
        var counted = 0
        val absVal = abs(smoothAxis)
        var cadenceHz = 0.0

        if (absVal <= band) {
            if (!inBand) {
                inBand = true
                inBandSinceMs = nowMs
                outsideConsec = 0
            } else if (inBandSinceMs < 0) {
                inBandSinceMs = nowMs
            }
        } else {
            val side = if (smoothAxis > 0) +1 else -1
            if (inBand) {
                inBand = false
                outsideSinceMs = nowMs
                outsideConsec = 1
            } else {
                outsideConsec += 1
            }

            val overshootOk = absVal >= cfg.outOvershoot * band
            val dwellOk = (inBandSinceMs >= 0) && ((nowMs - inBandSinceMs) >= cfg.bandRearmMs)
            val minSlopeEff = if (useDominant) cfg.minSlopeAbs * 0.7 else cfg.minSlopeAbs
            val slopeOk = dSmoothDtAbs >= minSlopeEff
            val signChanged = (lastNonZeroSide != 0) && (side != lastNonZeroSide)

            val robustFlip =
                dwellOk && overshootOk && (outsideConsec >= cfg.outMinConsec) && slopeOk && signChanged

            if (robustFlip) {

                // ---- cadence tracking ----
                zcTimesMs.addLast(nowMs)
                while (zcTimesMs.size > 6) zcTimesMs.removeFirst()
                val times = zcTimesMs.toList()
                val intervals = if (times.size >= 2) times.zipWithNext().map { (a,b) -> (b - a).toDouble() } else emptyList()

                val have3 = intervals.size >= 3
                val have5 = intervals.size >= 5

                val cadenceOk = if (have3) {
                    val mean = intervals.average()
                    val sd   = sqrt(intervals.map { (it - mean)*(it - mean) }.average())
                    val cv   = sd / (mean + 1e-9)
                    cadenceHz = if (mean > 1.0) 1000.0 / mean else 0.0

                    val cvCap = if (!have5) min(0.45, cfg.cadenceCvMax) else cfg.cadenceCvMax
                    (cadenceHz in cfg.cadenceMinHz..cfg.cadenceMaxHz) && (cv <= cvCap)
                } else {
                    true // warm-start: allow early steps to “boot up”
                }


                // allow near-vertical (slightly easier for hand-carry)
                val nearVertical = vertRatio >= (cfg.vertRmsRatioMin * 0.80)
                val handCarryRelax = cadenceOk && nearVertical

                val okInterval = (lastCountedMs < 0) || (nowMs - lastCountedMs >= cfg.minStepIntervalMs)

                // If we are already using the dominant-axis projection (vertical was weak),
                // treat that as "dominant enough" so we don't block real walking.
                val gateWalking   = walkingNow
                val gateDominance = verticalDominant || handCarryRelax || useDominant
                val gateNotTilted = !isHandTilted
                val gateInterval  = okInterval
                val gateCadence   = cadenceOk


                var culprit: String? = null
                if (!gateWalking) culprit =
                    "walkingNow=false (avgSma=$avgSma, thresh=$threshAvgSma)"
                if (culprit == null && !gateDominance) culprit =
                    "dominance=false (vertRatio=$vertRatio, handCarryRelax=$handCarryRelax)"
                if (culprit == null && !gateNotTilted) culprit = "isHandTilted=true"
                if (culprit == null && !gateInterval) {
                    val since = if (lastCountedMs >= 0) (nowMs - lastCountedMs) else -1
                    culprit = "okInterval=false (Δt=${since}ms < min=${cfg.minStepIntervalMs}ms)"
                }
                if (culprit == null && !gateCadence) culprit =
                    "cadenceOk=false (cadenceHz=$cadenceHz)"

                if (culprit == null) {
                    counted = 1
                    totalSteps += 1
                    lastCountedMs = nowMs
                } else {
                    println(
                        "STEP blocked: $culprit | dwellOk=$dwellOk overshootOk=$overshootOk " +
                                "outsideConsec=$outsideConsec slopeOk=$slopeOk signChanged=$signChanged useDominant=$useDominant"
                    )
                }
            }

            lastNonZeroSide = side
        }

        listener?.onDebug(
            Debug(
                nowMs = nowMs,
                avgSma = avgSma,
                threshAvgSma = threshAvgSma,
                walkingNow = walkingNow,
                omega = omega,
                lin = lin,
                isHandTilted = (nowMs - lastTiltMs) <= cfg.tiltHoldMs,
                band = band,
                smoothAxis = smoothAxis,
                vertRatio = vertRatio,
                cadenceHz = cadenceHz,
                gDirRate = gDirRate
            )
        )
        if (counted != 0) listener?.onSteps(counted, totalSteps)
    }

    /** Calibrate from REST samples (stand still, same carry pose). */
    fun calibrateRest(rest: List<Sample>) {
        if (rest.isEmpty()) return

        // --- Estimate sampling rate ---
        val firstTs = rest.first().timestampNs.toDouble()
        val lastTs = rest.last().timestampNs.toDouble()
        val fsHz = if (rest.size >= 2) {
            val durSec = (lastTs - firstTs) / 1e9
            if (durSec > 0) (rest.size - 1) / durSec else 20.0
        } else 20.0
        val dt = 1.0 / max(1e-6, fsHz)

        // --- Build rest arrays ---
        val smaVals = DoubleArray(rest.size)
        val vertAbs = DoubleArray(rest.size)
        val linMagVals = DoubleArray(rest.size)
        val omegaVals = DoubleArray(rest.size)

        for (i in rest.indices) {
            val r = rest[i]
            val lx = (r.ax - r.gx).toDouble()
            val ly = (r.ay - r.gy).toDouble()
            val lz = (r.az - r.gz).toDouble()
            val linMag = sqrt(lx * lx + ly * ly + lz * lz)
            linMagVals[i] = linMag
            smaVals[i] = abs(lx) + abs(ly) + abs(lz)

            val gxD = r.gx.toDouble();
            val gyD = r.gy.toDouble();
            val gzD = r.gz.toDouble()
            val gNorm = sqrt(gxD * gxD + gyD * gyD + gzD * gzD)
            val ux: Double;
            val uy: Double;
            val uz: Double
            if (gNorm > 1e-6) {
                val inv = 1.0 / gNorm; ux = gxD * inv; uy = gyD * inv; uz = gzD * inv
            } else {
                ux = 0.0; uy = 1.0; uz = 0.0
            }
            val aVert = lx * ux + ly * uy + lz * uz
            vertAbs[i] = abs(aVert)

            val w = sqrt((r.wx * r.wx + r.wy * r.wy + r.wz * r.wz).toDouble())
            omegaVals[i] = w
        }

        // --- Rest baselines ---
        muRestSMA = smaVals.average()
        sdRestSMA = sqrt(smaVals.map { (it - muRestSMA) * (it - muRestSMA) }.average())
        val meanVert = vertAbs.average()
        stdRestAxis = sqrt(vertAbs.map { (it - meanVert) * (it - meanVert) }.average())

        // --- Auto-tune config from rest ---
        val newSmaWindow = min(120, max(10, (fsHz * 1.0).toInt())) // ~1s window

        // EMA smoothing alpha from tau≈0.25 s: alpha = dt/(tau+dt)
        val tau = 0.25
        val newAlpha = (dt / (tau + dt)).coerceIn(0.05, 0.8)

        // ZC band multiplier so band ~covers 99.5% of |aVert| at rest
        fun percentile(p: Double, arr: DoubleArray): Double {
            if (arr.isEmpty()) return 0.0
            val sorted = arr.copyOf().apply { sort() }
            val idx = ((sorted.size - 1) * p).coerceIn(0.0, (sorted.size - 1).toDouble())
            val lo = idx.toInt()
            val hi = min(sorted.size - 1, lo + 1)
            val frac = idx - lo
            return sorted[lo] * (1 - frac) + sorted[hi] * frac
        }

        val q995 = percentile(0.995, vertAbs)
        val newCBand = if (stdRestAxis > 1e-9) max(1.2, q995 / stdRestAxis) else 2.0

        // Tilt thresholds from rest
        val muOmega = omegaVals.average()
        val sdOmega = sqrt(omegaVals.map { (it - muOmega) * (it - muOmega) }.average())
        val muLin = linMagVals.average()
        val sdLin = sqrt(linMagVals.map { (it - muLin) * (it - muLin) }.average())

        val newTiltOmega = (muOmega + 3.0 * sdOmega).coerceAtLeast(0.8)
        val newTiltLin = max(1.0, muLin + 2.5 * sdLin)
        val newTiltRatio = max(1.8, 0.8 * (newTiltOmega / (newTiltLin + 1e-9)))

        cfg = cfg.copy(
            smaWindow = newSmaWindow,
            alpha = newAlpha,
            cBand = newCBand,
            tiltOmegaThreshRadS = newTiltOmega,
            tiltLinAccelThresh = newTiltLin,
            tiltOmegaToLinRatio = newTiltRatio
        )


        // Rebuild internals with new config
        smaWin = RollingWindow(cfg.smaWindow)
        vertEWin = RollingWindow(cfg.smaWindow)
        horizEWin = RollingWindow(cfg.smaWindow)
        axisEma = Ema(cfg.alpha)
        omegaEma = Ema(cfg.tiltOmegaEmaAlpha)
        linEma = Ema(cfg.tiltLinEmaAlpha)
        gDirEma = Ema(cfg.tiltGDirEmaAlpha)

        // Reset runtime state
        calibrated = true
        lastNonZeroSide = 0
        inBand = true
        lastCountedMs = -1
        lastTiltMs = -1
        zcTimesMs.clear()
        hasPrevU = false
        prevUTsNs = 0L
        inBandSinceMs = -1
        outsideSinceMs = -1
        outsideConsec = 0
        prevSmoothAxis = 0.0
        prevSmoothTsMs = -1
        domX = 0.0; domY = 0.0; domZ = 0.0
    }

    fun reset() {
        calibrated = false
        muRestSMA = 0.0; sdRestSMA = 0.0; stdRestAxis = 0.0
        totalSteps = 0
        smaWin.clear(); vertEWin.clear(); horizEWin.clear()
        lastNonZeroSide = 0; inBand = true
        lastCountedMs = -1; lastTiltMs = -1
        zcTimesMs.clear()
        hasPrevU = false
        prevUTsNs = 0L
        inBandSinceMs = -1
        outsideSinceMs = -1
        outsideConsec = 0
        prevSmoothAxis = 0.0
        prevSmoothTsMs = -1
        domX = 0.0; domY = 0.0; domZ = 0.0
    }
}
