package com.example.goforitGit.count_step_module

import android.util.Log
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
        v = if (!init) { init = true; x } else (1 - alpha) * v + alpha * x
        return v
    }
    fun value(): Double = v
}

/** Small O(1) moving-sum ring buffer. */
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
    fun clear() { head = 0; filled = 0; sum = 0.0 }
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
    val cBand: Double = 1.0,
    val alpha: Double = 0.5,
    val axis: Axis = Axis.Y,

    // ==================================
    // SMA walking gate (per-sample)
    // ==================================
    val smaK: Double = 1.2,
    val smaWindow: Int = 20,          // ~1 s @ 20 Hz (auto-sized in calibrate)

    // ========== Debounce ==========
    val minStepIntervalMs: Long = 260,

    // ======================================================
    // Tilt suppression (rotation-dominant + limited translation)
    // ======================================================
    val tiltOmegaThreshRadS: Double = 2.0,
    val tiltLinAccelThresh: Double = 7.0,
    val tiltOmegaToLinRatio: Double = 1.4,
    val tiltHoldMs: Long = 200,              // ↓ shorter hold
    val tiltOmegaEmaAlpha: Double = 0.20,
    val tiltLinEmaAlpha: Double = 0.20,

    // ======================================================
    // Orientation-change path (gravity direction rotation rate)
    // ======================================================
    val tiltUseGDirPath: Boolean = true,
    val tiltGDirRateThreshRadS: Double = 3.4, // ↑ avoid latching on arm swing
    val tiltGDirEmaAlpha: Double = 0.30,

    // Quiet period after walking starts before tilt can suppress
    val tiltQuietStartMs: Long = 800,        // new

    // =================================================================
    // Optional "shake" path
    // =================================================================
    val tiltShakeUse: Boolean = false,
    val tiltShakeOmegaMax: Double = 0.8,
    val tiltShakeLinMin: Double = 6.5,

    // ============================ Walking-likeness gates ============================
    val vertRmsRatioMin: Double = 1.0,       // ↓ more tolerant for hand-carry
    val cadenceMinHz: Double = 0.25,
    val cadenceMaxHz: Double = 3.1,
    val cadenceCvMax: Double = 3.4,         // ↑ a bit looser

    // ============================ Robust ZC (band crossing) ============================
    val bandRearmMs: Long = 120,
    val outMinConsec: Int = 2,               // ↓ confirm sooner
    val outOvershoot: Double = 1.08,         // ↓ easier exits
    val minSlopeAbs: Double = 2.2            // ↓ easier exits; scaled when dominant-axis
)

/**
 * Advanced ZC step counter with dominant-axis fallback for hand-carry.
 **/
class StepCounterZC(
    private var cfg: StepCounterZCConfig = StepCounterZCConfig(),
    private val listener: StepListener? = null
) {
    // Dominant-axis (unit) with hemisphere lock
    private var dUx = 1.0; private var dUy = 0.0; private var dUz = 0.0
    private var haveDom = false
    private val domAlphaInBand = 0.05   // update only while in-band (~1 s @20 Hz)

    // DC remover for zcSignal (very slow EMA)
    private var dcEma = Ema(0.02)

    // ---- Calibration (rest stats) ----
    var muRestSMA: Double = 0.0; private set
    var sdRestSMA: Double = 0.0; private set
    var stdRestAxis: Double = 0.0; private set
    var calibrated: Boolean = false; private set

    // ---- Windows ----
    private var smaWin = RollingWindow(cfg.smaWindow)
    private var vertEWin = RollingWindow(cfg.smaWindow)
    private var horizEWin = RollingWindow(cfg.smaWindow)

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
    private var prevUnitGravitationVectorX = 0.0
    private var prevUnitGravitationVectorY = 0.0
    private var prevUnitGravitationVectorZ = 0.0
    private var prevUTsNs: Long = 0L

    // Dominant-motion direction (EMA) for fallback when verticalDominant=false
    private var domX = 0.0; private var domY = 0.0; private var domZ = 0.0
    private val domAlpha = 0.05  // ~1 s memory at ~20 Hz

    // Robust ZC state
    private var inBandSinceMs: Long = -1
    private var outsideSinceMs: Long = -1
    private var outsideConsec: Int = 0
    private var prevSmoothAxis = 0.0
    private var prevSmoothTsMs: Long = -1

    // Recent ZC times for cadence estimation
    private val zcTimesMs: JArrayDeque<Long> = JArrayDeque(16)

    // snappier EMA for small hand swings
    private var axisEmaFast = Ema(0.65)

    // min ZC spacing so two flips won't be recorded a few ms apart
    private var lastZcMs: Long = -1
    private val minZcIntervalMs = 240L   // ~ >2.1 Hz half-cycle guard (safer)

    // Only count on one oscillation phase
    private val countPositivePhase = true

    // Track walking transition for tilt quiet start
    private var prevWalkingNow = false
    private var lastWalkingTrueMs: Long = -1

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

        // ---- SMA Average (L1 of linear accel) ----
        val smaPoint = abs(lx) + abs(ly) + abs(lz)
        val smaSum = smaWin.push(smaPoint)
        val filled = smaWin.size()
        val avgSma = if (filled > 0) smaSum / filled else 0.0

        // ---- Gravity unit vector (for projection) ----
        val gxD = sm.gx.toDouble(); val gyD = sm.gy.toDouble(); val gzD = sm.gz.toDouble()
        val gNorm = sqrt(gxD * gxD + gyD * gyD + gzD * gzD)
        val (ux, uy, uz) = if (gNorm > 1e-6) arrayOf(gxD / gNorm, gyD / gNorm, gzD / gNorm) else arrayOf(0.0, 1.0, 0.0)

        // ---- Vertical vs horizontal energy (RMS ratio gate) ----
        val linMag2 = linMagRaw * linMagRaw
        val aVert = lx * ux + ly * uy + lz * uz
        val aVert2 = aVert * aVert
        val aHoriz2 = max(0.0, linMag2 - aVert2)

        vertEWin.push(aVert2)
        horizEWin.push(aHoriz2)

        val vRms = sqrt(vertEWin.sum / max(1, vertEWin.size()).toDouble())
        val hRms = sqrt(horizEWin.sum / max(1, horizEWin.size()).toDouble())
        val vertRatio = vRms / (hRms + 1e-9)
        val verticalDominant = vertRatio >= cfg.vertRmsRatioMin
        val nearVerticalLocal = vertRatio >= (cfg.vertRmsRatioMin * 0.75) // ↓ looser warmup
        var nearVertical = nearVerticalLocal

        // ---- Tilt gate signals (EMAs) ----
        val omega = omegaEma.update(omegaMagRaw)
        val lin = linEma.update(linMagRaw)

        // Orientation-change rate of gravity direction (rad/s)
        val dtU = (sm.timestampNs - prevUTsNs) / 1e9
        var gDirRateInst = 0.0
        if (hasPrevU && dtU > 1e-6) {
            val dot = (ux * prevUnitGravitationVectorX + uy * prevUnitGravitationVectorY + uz * prevUnitGravitationVectorZ).coerceIn(-1.0, 1.0)
            val angle = acos(dot)
            gDirRateInst = angle / dtU
        }
        val gDirRate = gDirEma.update(gDirRateInst)
        prevUnitGravitationVectorX = ux; prevUnitGravitationVectorY = uy; prevUnitGravitationVectorZ = uz
        prevUTsNs = sm.timestampNs
        hasPrevU = true

        // ----- Tilt logic -----
        val ratioOK = omega / (lin + 1e-9) >= cfg.tiltOmegaToLinRatio

        val tiltRaw1 = (omega >= cfg.tiltOmegaThreshRadS) && (lin <= cfg.tiltLinAccelThresh) && ratioOK
        val tiltRaw2 = cfg.tiltUseGDirPath && (gDirRate >= cfg.tiltGDirRateThreshRadS) && (lin <= 6.0) && !verticalDominant
        val shakeRaw = cfg.tiltShakeUse && (omega <= cfg.tiltShakeOmegaMax) && (lin >= cfg.tiltShakeLinMin) && !verticalDominant

        val tiltRaw = tiltRaw1 || tiltRaw2 || shakeRaw
        if (tiltRaw) lastTiltMs = nowMs
        val isHandTiltedLatched = (nowMs - lastTiltMs) <= cfg.tiltHoldMs

        // ---- Walking gate (avg SMA above rest) ----
        val threshAvgSma = muRestSMA + cfg.smaK * sdRestSMA
        val enoughSamples = filled >= min(cfg.smaWindow, 12) // ≈ 0.3–0.6 s
        val walkingNow = calibrated && sdRestSMA > 0.0 && enoughSamples && (avgSma > threshAvgSma)

        // Walking transition (for tilt quiet start)
        val justBecameWalking = walkingNow && !prevWalkingNow
        if (justBecameWalking) lastWalkingTrueMs = nowMs
        val inTiltQuiet = (lastWalkingTrueMs >= 0) && ((nowMs - lastWalkingTrueMs) <= cfg.tiltQuietStartMs)
        val isHandTilted = isHandTiltedLatched && !inTiltQuiet

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
        val lNorm = sqrt(lx * lx + ly * ly + lz * lz)
        if (lNorm > 1e-6 && inBand && inBandSinceMs >= 0 && (nowMs - inBandSinceMs) >= cfg.bandRearmMs) {
            // hemisphere-locked EMA update of dUx/dUy/dUz
            var nx = lx / lNorm; var ny = ly / lNorm; var nz = lz / lNorm
            val dotH = nx * dUx + ny * dUy + nz * dUz
            val s = if (!haveDom || dotH >= 0.0) 1.0 else -1.0
            nx *= s; ny *= s; nz *= s

            dUx = (1 - domAlphaInBand) * dUx + domAlphaInBand * nx
            dUy = (1 - domAlphaInBand) * dUy + domAlphaInBand * ny
            dUz = (1 - domAlphaInBand) * dUz + domAlphaInBand * nz
            val dn = sqrt(dUx * dUx + dUy * dUy + dUz * dUz)
            if (dn > 1e-6) { dUx /= dn; dUy /= dn; dUz /= dn; haveDom = true }
        }

        // Choose projection for ZC: vertical by default; dominant-axis if vertical is weak
        val useDominant = !verticalDominant && haveDom
        val zcRaw = if (!useDominant) aVert else (lx * dUx + ly * dUy + lz * dUz)

        // Remove slow DC so we actually cross zero
        val zcBias = dcEma.update(zcRaw)
        val zcSignal = zcRaw - zcBias

        // Slightly wider band only in fallback
        val bandScale = if (!useDominant) 1.0 else 1.2

        // ---- ZC signal smoothing ----
        val smoothAxis = if (useDominant) axisEmaFast.update(zcSignal) else axisEma.update(zcSignal)

        // Cap the band: ↓ min to avoid swallowing small hand swings
        val band = (cfg.cBand * stdRestAxis * bandScale).coerceIn(0.10, 1.0)

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
        var lastComputedCv = Double.NaN
        var cadenceWarmup = false

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
            val minSlopeEff = if (useDominant) cfg.minSlopeAbs * 0.6 else cfg.minSlopeAbs
            val slopeOk = dSmoothDtAbs >= minSlopeEff
            val signChanged = (lastNonZeroSide != 0) && (side != lastNonZeroSide)

            val robustFlip = dwellOk && overshootOk && (outsideConsec >= cfg.outMinConsec) && slopeOk && signChanged

            if (robustFlip) {
                // Determine which side we flipped into after smoothing
                val flipSide = if (smoothAxis > 0) +1 else -1
                val isCountPhase = if (countPositivePhase) (flipSide > 0) else (flipSide < 0)

                // ---- cadence tracking ----
                if (isCountPhase) {
                    val acceptZc = isCountPhase &&
                            ((lastZcMs < 0) || (nowMs - lastZcMs >= minZcIntervalMs)) &&
                            ((lastCountedMs < 0) || (nowMs - lastCountedMs >= cfg.minStepIntervalMs))
                    if (acceptZc) {
                        lastZcMs = nowMs
                        zcTimesMs.addLast(nowMs)
                        while (zcTimesMs.size > 16) zcTimesMs.removeFirst()
                    }
                }

                // Intervals between consecutive count-phase flips are **step** intervals
                val times = zcTimesMs.toList()
                var stepIntervals = if (times.size >= 2) times.takeLast(9).zipWithNext().map { (a,b)-> (b-a).toDouble() } else emptyList()
                // Optionally drop max/min before mean/SD:
                stepIntervals = if (stepIntervals.size >= 5) stepIntervals.sorted().drop(1).dropLast(1) else stepIntervals

                val have3 = stepIntervals.size >= 3
                val have5 = stepIntervals.size >= 5

                val cadenceOk =
                    if (!have3) {
                        cadenceWarmup = true
                        true // let cadence boot up
                    } else {
                        val mean = stepIntervals.average()
                        val sd = if (stepIntervals.size >= 2)
                            kotlin.math.sqrt(stepIntervals.map { (it - mean) * (it - mean) }.average())
                        else 0.0
                        val cv = if (mean > 1.0) sd / mean else 1.0
                        lastComputedCv = cv
                        cadenceHz = if (mean > 1.0) 1000.0 / mean else 0.0

                        val cadenceMinEff = if (!have5) 0.34 else cfg.cadenceMinHz
                        val cadenceMaxEff = if (!have5) cfg.cadenceMaxHz * 1.15 else cfg.cadenceMaxHz

                        if (!have5) {
                            cadenceWarmup = true
                            (cadenceHz in cadenceMinEff..cadenceMaxEff)
                        } else {
                            val cvCap = cfg.cadenceCvMax
                            (cadenceHz in cadenceMinEff..cadenceMaxEff) && (cv <= cvCap)
                        }
                    }

                // allow near-vertical (slightly easier for hand-carry)
                nearVertical = vertRatio >= (cfg.vertRmsRatioMin * 0.75)
                val handCarryRelax = cadenceOk && nearVertical

                val okInterval = (lastCountedMs < 0) || (nowMs - lastCountedMs >= cfg.minStepIntervalMs)

                // If we are already using the dominant-axis projection, treat that as "dominant enough".
                val gateWalking = if (calibrated) walkingNow else true
                val gateDominance = verticalDominant || handCarryRelax || useDominant
                val gateNotTilted = !isHandTilted || cadenceOk
                val gateInterval = okInterval
                val gateCadence = cadenceOk

                var culprit: String? = null
                if (!gateWalking) {
                    val phase = if (calibrated) "calibrated" else "precal"
                    culprit = "walkingNow=false (phase=$phase, avgSma=$avgSma, thresh=$threshAvgSma)"
                }
                if (culprit == null && !gateDominance) culprit =
                    "dominance=false (vertRatio=$vertRatio, handCarryRelax=$handCarryRelax)"
                if (culprit == null && !gateNotTilted) culprit = "isHandTilted=true"
                if (culprit == null && !gateInterval) {
                    val since = if (lastCountedMs >= 0) (nowMs - lastCountedMs) else -1
                    culprit = "okInterval=false (Δt=${since}ms < min=${cfg.minStepIntervalMs}ms)"
                }
                if (culprit == null && !gateCadence) {
                    val cvTxt = if (lastComputedCv.isNaN()) "n/a" else "%.2f".format(lastComputedCv)
                    val phase = if (cadenceWarmup) "warmup" else "steady"
                    culprit = "cadenceOk=false (cadenceHz=%.3f, cv=$cvTxt, phase=$phase)".format(cadenceHz)
                }

                if (culprit == null && isCountPhase) {
                    counted = 1
                    totalSteps += 1
                    lastCountedMs = nowMs
                } else if (culprit != null) {
                    Log.i(
                        "DEBUGAPP",
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
                isHandTilted = isHandTilted,
                band = band,
                smoothAxis = smoothAxis,
                vertRatio = vertRatio,
                cadenceHz = cadenceHz,
                gDirRate = gDirRate
            )
        )
        if (counted != 0) listener?.onSteps(counted, totalSteps)

        // update walking transition state
        prevWalkingNow = walkingNow
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

            val gxD = r.gx.toDouble(); val gyD = r.gy.toDouble(); val gzD = r.gz.toDouble()
            val gNorm = sqrt(gxD * gxD + gyD * gyD + gzD * gzD)
            val ux: Double; val uy: Double; val uz: Double
            if (gNorm > 1e-6) {
                val inv = 1.0 / gNorm; ux = gxD * inv; uy = gyD * inv; uz = gzD * inv
            } else { ux = 0.0; uy = 1.0; uz = 0.0 }
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
        prevWalkingNow = false
        lastWalkingTrueMs = -1
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
        prevWalkingNow = false
        lastWalkingTrueMs = -1
    }
}
