package com.example.goforitGit.count_step_module

import android.util.Log
import java.util.ArrayDeque as JArrayDeque
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Axis kept for compatibility; ZC uses vertical projection (or dominant-axis fallback). */
enum class Axis { X, Y, Z }

interface StepListener {
    fun onSteps(delta: Int, total: Int)
    fun onDebug(d: StepCounterZC.Debug) {}
}

/** Simple EMA filter (supports temporary alpha override). */
class Ema(private var alpha: Double) {
    private var init = false
    private var v = 0.0
    fun update(x: Double): Double {
        v = if (!init) { init = true; x } else (1 - alpha) * v + alpha * x
        return v
    }
    fun updateWithAlpha(x: Double, a: Double): Double {
        v = if (!init) { init = true; x } else (1 - a) * v + a * x
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
    // Band multiplier for zero-crossing: ↑ widens band (fewer, cleaner steps), ↓ narrows (more sensitive, risk doubles).
    val cBand: Double = 1.0,
    // Smoothing for ZC signal EMA: ↑ smoother/laggier, ↓ snappier/more jitter-sensitive.
    val alpha: Double = 0.5,
    // Axis enum (kept for compatibility); ZC actually uses vertical projection or dominant fallback.
    val axis: Axis = Axis.Y,

    // Multiplier above rest SMA to declare “walking”: ↑ needs stronger motion, ↓ allows gentler gait.
    val smaK: Double = 1.2,
    // SMA window length (samples): ↑ steadier gate, ↓ faster but noisier; auto-sized in calibrate.
    val smaWindow: Int = 20,

    // Absolute minimum time between step commits (ms): ↑ blocks fast repeats, ↓ allows higher cadence.
    val minStepIntervalMs: Long = 260,

    // Gyro magnitude (rad/s) that flags rotation-dominant hand tilts: ↑ less tilt suppression, ↓ more.
    val tiltOmegaThreshRadS: Double = 2.0,
    // Linear accel cap (m/s²) for tilt suppression path: ↑ tolerates more translation during tilt, ↓ stricter.
    val tiltLinAccelThresh: Double = 7.0,
    // Rotation/translation ratio needed to call it “tilt”: ↑ requires relatively more rotation, ↓ more permissive.
    val tiltOmegaToLinRatio: Double = 1.4,
    // How long (ms) tilt stays latched once detected: ↑ longer suppression after a tilt, ↓ shorter.
    val tiltHoldMs: Long = 200,
    // Gyro EMA alpha for tilt gate: ↑ faster gyro estimate, ↓ smoother/slower.
    val tiltOmegaEmaAlpha: Double = 0.20,
    // Linear accel EMA alpha for tilt gate: ↑ faster linear estimate, ↓ smoother/slower.
    val tiltLinEmaAlpha: Double = 0.20,

    // Enable alternative tilt path based on gravity direction rotation (helps screen-facing hand carry).
    val tiltUseGDirPath: Boolean = true,
    // Gravity-direction rotation rate (rad/s) to trigger tilt path: ↑ less sensitive to orientation change, ↓ more.
    val tiltGDirRateThreshRadS: Double = 3.4,
    // EMA alpha for gravity-direction rate: ↑ faster response, ↓ steadier reading.
    val tiltGDirEmaAlpha: Double = 0.30,

    // Quiet time after walking starts where tilt cannot suppress (ms): ↑ fewer early misses, ↓ more responsive to tilts.
    val tiltQuietStartMs: Long = 800,

    // Optional “shake” path (false by default) that treats strong translation with low rotation as non-tilt.
    val tiltShakeUse: Boolean = false,
    // Max gyro (rad/s) to consider motion a “shake” not a tilt: ↑ classifies more as shake, ↓ stricter.
    val tiltShakeOmegaMax: Double = 0.8,
    // Min linear accel (m/s²) to consider motion a “shake”: ↑ demands stronger translation, ↓ more permissive.
    val tiltShakeLinMin: Double = 6.5,

    // Vertical/horizontal RMS ratio floor to prefer vertical projection: ↑ prefer vertical more, ↓ switch to dominant sooner.
    val vertRmsRatioMin: Double = 0.77,
    // Gate: minimum cadence (Hz) to accept from ZC cadence estimator: ↑ ignore slower walks, ↓ accept shuffles.
    val cadenceMinHz: Double = 0.25,
    // Gate: maximum cadence (Hz) to accept from ZC cadence estimator: ↑ allow very fast steps, ↓ cap high-cadence.
    val cadenceMaxHz: Double = 3.1,
    // Gate: maximum CV of cadence intervals (dimensionless): ↑ tolerate irregular rhythm, ↓ require steady tempo.
    val cadenceCvMax: Double = 3.4,

    // Time (ms) to require back “in band” before re-arming after a step: ↑ fewer doubles, ↓ faster re-arm.
    val bandRearmMs: Long = 120,
    // Minimum consecutive out-of-band samples to treat as true cross: ↑ harder to trigger, ↓ easier.
    val outMinConsec: Int = 2,
    // Overshoot multiplier beyond band before commit: ↑ stricter peak needed, ↓ more eager commits.
    val outOvershoot: Double = 1.08,
    // Minimum slope magnitude (units/s) around cross: ↑ demands sharper rise/fall, ↓ accepts softer steps.
    val minSlopeAbs: Double = 2.2,

    // Enable dominant-axis fallback when vertical is weak (hand-carry help).
    val dominantFallbackEnabled: Boolean = true,
    // Upper clamp (ms) for in-band dwell re-arm on vertical path: ↑ fewer doubles, ↓ more responsive.
    val rearmDwellMsBase: Long = 150,
    // Upper clamp (ms) for in-band dwell re-arm on dominant path: ↑ fewer doubles in hand-carry, ↓ more responsive.
    val rearmDwellMsBaseDominant: Long = 180,
    // Overshoot factor used in dominant-axis mode (stricter than vertical): ↑ fewer false hand-swings, ↓ count more.
    val outOvershootDominant: Double = 1.32,
    // Extra slope demand multiplier in dominant mode: ↑ require snappier peaks, ↓ accept gentler arm arcs.
    val slopeDominantMult: Double = 1.3,
    // Floor on band half-width in dominant mode: ↑ prevents tiny bands (misses soft steps), ↓ allows tighter target.
    val bandMinDominant: Double = 0.10,

    // Hard cap on vertical band half-width: ↑ allow broader band if rest is noisy, ↓ limit overshoot requirements.
    val bandMaxVertical: Double = 0.18,
    // Hard cap on dominant band half-width: ↑ allow broader band in hand-carry, ↓ limit overshoot requirements.
    val bandMaxDominant: Double = 0.30
)



/**
 * Advanced ZC step counter with dominant-axis fallback for hand-carry.
 *
 * Adds:
 * - OR-based rearm (opp-side OR in-band dwell)
 * - Dual-phase counting (alternate up & down)
 * - Relaxed cross precondition (sign-aware, not strict ≤ −band)
 * - Walk-mode DC recentring (faster EMA while walking)
 * - More responsive dominant tracking
 * - Overshoot/peak latch (commit on overshoot or local peak)
 * - Pre-walk grace, robust calibration, band caps
 * - Cadence-backed walking gate (no dependence on SMA only)
 * - Duplicate-calibration guard
 */
class StepCounterZC(
    private var cfg: StepCounterZCConfig = StepCounterZCConfig(),
    private val listener: StepListener? = null
) {
    // Dominant-axis (unit) with hemisphere lock
    private var dUx = 1.0; private var dUy = 0.0; private var dUz = 0.0
    private var haveDom = false
    // EMA blend for updating the dominant motion direction while “in band”: ↑ tracks arm swings faster, ↓ more stable.
    private val domAlphaInBand = 0.08

    // DC remover for zcSignal (slow at rest, faster when walking)
    private var dcEma = Ema(0.02)

    // ---- Calibration (rest stats) ----
    var muRestSMA: Double = 0.0; private set
    var sdRestSMA: Double = 0.0; private set
    var stdRestAxis: Double = 0.0; private set
    var calibrated: Boolean = false; private set
    private var lastCalibMs: Long = -1

    // ---- Windows ----
    private var smaWin = RollingWindow(cfg.smaWindow)
    private var vertEWin = RollingWindow(cfg.smaWindow)
    private var horizEWin = RollingWindow(cfg.smaWindow)

    // ---- Filters ----
    private var axisEma = Ema(cfg.alpha)                 // ZC signal (vertical or dominant)
    private var axisEmaFast = Ema(0.65)                  // snappier EMA for hand swings
    private var omegaEma = Ema(cfg.tiltOmegaEmaAlpha)
    private var linEma = Ema(cfg.tiltLinEmaAlpha)
    private var gDirEma = Ema(cfg.tiltGDirEmaAlpha)

    // ---- State ----
    private var lastTiltMs: Long = -1
    private var inBand: Boolean = true
    private var inBandSinceMs: Long = -1
    private var lastCountedMs: Long = -1

    // For orientation-change rate
    private var hasPrevU = false
    private var prevUnitGravitationVectorX = 0.0
    private var prevUnitGravitationVectorY = 0.0
    private var prevUnitGravitationVectorZ = 0.0
    private var prevUTsNs: Long = 0L

    // Dominant-motion direction (EMA)
    private var domX = 0.0; private var domY = 0.0; private var domZ = 0.0

    // Robust ZC state
    private var prevSmoothAxis = 0.0
    private var prevSmoothTsMs: Long = -1

    // Rearm/hysteresis state
    private var requireOppSide: Boolean = false
    private var visitedOppSideSinceCount: Boolean = false
    private var bandDwellOkSinceCount: Boolean = false
    private var sumInBandSinceCountMs: Double = 0.0
    private var lastCommitUp: Int = 0 // +1=up, -1=down, 0=none yet

    // **Overshoot/peak latch**
    private var pendingCross: Boolean = false
    private var pendingStartMs: Long = -1
    private var pendingBand: Double = 0.0
    private var pendingOvershootFactor: Double = 1.0
    private var pendingUseDominant: Boolean = false
    private var pendingPrevSlopeSign: Int = 1
    private var pendingForUp: Boolean = true
    private var pendingExt: Double = 0.0 // max for up, min for down

    // ZC times for cadence estimation (count-phase only)
    private val zcTimesMs: JArrayDeque<Long> = JArrayDeque(16)

    // Minimum spacing for logging ZC pulses
    private var lastZcMs: Long = -1
    // Min spacing (ms) between logged ZC pulses for cadence estimation: ↑ fewer cadence points, ↓ denser cadence tracking.
    private val minZcIntervalMs = 200L

    private val countPositivePhase = true

    // Walking transition for tilt quiet start
    private var prevWalkingNow = false
    private var lastWalkingTrueMs: Long = -1

    // ---- Pre-walk grace after stillness ----
    private var stillSinceMs: Long = -1
    private var preWalkGraceUntilMs: Long = -1

    // ---- Dual-phase expectation ----
    private var expectUp: Int = 0 // 0=any, +1=upward, -1=downward

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

    private fun recentZcCount(windowMs: Long): Int {
        val now = prevSmoothTsMs
        var c = 0
        for (t in zcTimesMs) if (now - t <= windowMs) c++
        return c
    }

    fun addSample(sm: Sample) {
        val nowMs = sm.timestampNs / 1_000_000L

        // ---- Linear acceleration (a - g) & magnitudes ----
        val lx = (sm.ax - sm.gx).toDouble()
        val ly = (sm.ay - sm.gy).toDouble()
        val lz = (sm.az - sm.gz).toDouble()
        val linMagRaw = sqrt(lx * lx + ly * ly + lz * lz)
        val omegaMagRaw = sqrt((sm.wx * sm.wx + sm.wy * sm.wy + sm.wz * sm.wz).toDouble())

        // normalized linear direction (for dominant tracking update later)
        val lNorm = linMagRaw
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        if (lNorm > 1e-6) { nx = lx / lNorm; ny = ly / lNorm; nz = lz / lNorm }

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

        // ---- Walking gate (avg SMA above rest), with hand-carry relax ----
        val kEff = if (cfg.dominantFallbackEnabled && !verticalDominant) cfg.smaK * 0.7 else cfg.smaK // was 0.8
        val threshAvgSma = muRestSMA + kEff * sdRestSMA
        val enoughSamples = filled >= min(cfg.smaWindow, 12)
        val walkingNow = calibrated && sdRestSMA > 0.0 && enoughSamples && (avgSma > threshAvgSma)

        // Track stillness to trigger pre-walk grace
        val inStillness = calibrated && enoughSamples && (avgSma <= threshAvgSma)
        if (inStillness) { if (stillSinceMs < 0) stillSinceMs = nowMs } else stillSinceMs = -1
        val preWalkGraceActive = (preWalkGraceUntilMs >= 0) && (nowMs <= preWalkGraceUntilMs)

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

        // ---- Choose projection for ZC: vertical by default; dominant-axis if vertical is weak (and enabled)
        val useDominant = cfg.dominantFallbackEnabled && !verticalDominant && haveDom
        val zcRaw = if (!useDominant) (aVert) else (lx * dUx + ly * dUy + lz * dUz)

        // Walk-mode DC recentring
        val dcAlpha = if (walkingNow) 0.06 else 0.02
        val zcBias = dcEma.updateWithAlpha(zcRaw, dcAlpha)
        val zcSignal = zcRaw - zcBias

        // ---- ZC signal smoothing ----
        val smoothAxis = if (useDominant) axisEmaFast.update(zcSignal) else axisEma.update(zcSignal)

        // Effective band with floors **and caps** (slightly wider band only in fallback)
        val bandScale = if (!useDominant) 1.0 else 1.05
        val bandFloor = if (useDominant) cfg.bandMinDominant else 0.10
        val bandMax = if (useDominant) cfg.bandMaxDominant else cfg.bandMaxVertical
        val bandRaw = cfg.cBand * stdRestAxis * bandScale
        val band = bandRaw.coerceIn(bandFloor, bandMax)

        // Update dominant direction AFTER we have band/smoothAxis; allow near-band updates (≤1.5×band)
        if (lNorm > 1e-6) {
            val absVal = abs(smoothAxis)
            val zcBurstForDom = recentZcCount(2200) // allow update sooner once ZCs start coming
            val canUpdateDom = absVal <= (1.5 * band) || (inBand && inBandSinceMs >= 0 && (nowMs - inBandSinceMs) >= cfg.bandRearmMs) || (zcBurstForDom >= 2)
            if (canUpdateDom) {
                // hemisphere lock w.r.t current dominant
                val dotH = nx * dUx + ny * dUy + nz * dUz
                val s = if (!haveDom || dotH >= 0.0) 1.0 else -1.0
                val nxh = nx * s; val nyh = ny * s; val nzh = nz * s
                dUx = (1 - domAlphaInBand) * dUx + domAlphaInBand * nxh
                dUy = (1 - domAlphaInBand) * dUy + domAlphaInBand * nyh
                dUz = (1 - domAlphaInBand) * dUz + domAlphaInBand * nzh
                val dn = sqrt(dUx * dUx + dUy * dUy + dUz * dUz)
                if (dn > 1e-6) { dUx /= dn; dUy /= dn; dUz /= dn; haveDom = true }
            }
        }

        // Track slope (signed and abs)
        var dSmoothDtAbs = 0.0
        var dSmoothDt = 0.0
        var dtMs = 0.0
        if (prevSmoothTsMs > 0) {
            dtMs = (nowMs - prevSmoothTsMs).toDouble()
            val dtSec = dtMs / 1000.0
            if (dtSec > 1e-6) {
                dSmoothDt = (smoothAxis - prevSmoothAxis) / dtSec
                dSmoothDtAbs = abs(dSmoothDt)
            }
        }

        // Update band/in-band timers and accumulate in-band time since last count
        val absVal = abs(smoothAxis)
        if (absVal <= band) {
            if (!inBand) {
                inBand = true
                inBandSinceMs = nowMs
            } else if (inBandSinceMs < 0) {
                inBandSinceMs = nowMs
            }
            if (lastCountedMs >= 0 && dtMs > 0.0) {
                sumInBandSinceCountMs += dtMs
            }
        } else {
            if (inBand) {
                inBand = false
                inBandSinceMs = -1
            }
        }

        // === Adaptive dwell time based on cadence limit ===
        val periodFromCadenceMaxMs = 1000.0 / cfg.cadenceMaxHz
        val dwellTargetMsBase = if (useDominant) 0.22 else 0.18
        val dwellClampMin = if (useDominant) 60.0 else 40.0
        val dwellClampMax = if (useDominant) min(cfg.rearmDwellMsBaseDominant.toDouble(), 140.0) else min(cfg.rearmDwellMsBase.toDouble(), 120.0)
        val dwellMsEff = (periodFromCadenceMaxMs * dwellTargetMsBase).coerceIn(dwellClampMin, dwellClampMax)

        if (sumInBandSinceCountMs >= dwellMsEff) bandDwellOkSinceCount = true

        // Opposite-side visit since last commit (phase-aware)
        if (requireOppSide) {
            val oppVisitedNow = when (lastCommitUp) {
                +1 -> smoothAxis <= -band
                -1 -> smoothAxis >=  band
                else -> (smoothAxis <= -band || smoothAxis >= band)
            }
            if (oppVisitedNow) visitedOppSideSinceCount = true
        }

        // === Phase detection (dual) ===
        val upCross   = (prevSmoothAxis < 0 && smoothAxis >= band)
        val downCross = (prevSmoothAxis > 0 && smoothAxis <= -band)
        val phaseCross = when (expectUp) {
            +1 -> upCross
            -1 -> downCross
            else -> (upCross || downCross)
        }
        val wantUp = when (expectUp) {
            +1 -> true
            -1 -> false
            else -> if (upCross) true else if (downCross) false else true
        }

        // Effective gates
        val outOvershootEff = if (useDominant) max(cfg.outOvershootDominant, cfg.outOvershoot) else cfg.outOvershoot
        val minSlopeEff = if (useDominant) cfg.minSlopeAbs * cfg.slopeDominantMult else cfg.minSlopeAbs
        val slopeOk = dSmoothDtAbs >= minSlopeEff

        // === Coherent min step interval ===
        val minFromCadence = ceil(1000.0 / (cfg.cadenceMaxHz * 1.25)).toLong() // was 1.10 → allow fast steps ~258ms
        val minStepIntervalEff = max(cfg.minStepIntervalMs, minFromCadence)
        val okInterval = (lastCountedMs < 0) || (nowMs - lastCountedMs >= minStepIntervalEff)

        // ---- cadence estimation: record at either phase cross ----
        var cadenceHz = 0.0
        var lastComputedCv = Double.NaN
        var cadenceWarmup = false
        if (phaseCross && slopeOk && okInterval) {
            if ((lastZcMs < 0) || (nowMs - lastZcMs >= minZcIntervalMs)) {
                lastZcMs = nowMs
                zcTimesMs.addLast(nowMs)
                while (zcTimesMs.size > 16) zcTimesMs.removeFirst()
            }
        }
        val times = zcTimesMs.toList()
        var stepIntervals = if (times.size >= 2) times.takeLast(9).zipWithNext().map { (a,b)-> (b-a).toDouble() } else emptyList()
        stepIntervals = if (stepIntervals.size >= 5) stepIntervals.sorted().drop(1).dropLast(1) else stepIntervals
        val have3 = stepIntervals.size >= 3
        val have5 = stepIntervals.size >= 5
        val cadenceOk = if (!have3) {
            cadenceWarmup = true
            true
        } else {
            val mean = stepIntervals.average()
            val sd = if (stepIntervals.size >= 2) kotlin.math.sqrt(stepIntervals.map { (it - mean) * (it - mean) }.average()) else 0.0
            val cv = if (mean > 1.0) sd / mean else 1.0
            lastComputedCv = cv
            cadenceHz = if (mean > 1.0) 1000.0 / mean else 0.0
            val cadenceMinEff = cfg.cadenceMinHz
            val cadenceMaxEff = if (!have5) cfg.cadenceMaxHz * 1.15 else cfg.cadenceMaxHz
            (cadenceHz in cadenceMinEff..cadenceMaxEff) && (if (have5) cv <= cfg.cadenceCvMax else true)
        }

        val nearVertical = vertRatio >= (cfg.vertRmsRatioMin * 0.75)
        val handCarryRelax = cadenceOk && nearVertical

        // **Pre-walk grace**: allow arming after ≥3 s stillness
        if (!preWalkGraceActive && inStillness && stillSinceMs >= 0 && (nowMs - stillSinceMs) >= 3000 && phaseCross && slopeOk) {
            preWalkGraceUntilMs = nowMs + 1200
            Log.i("DEBUGAPP", "prewalk: grace armed for 1200ms after stillness")
        }
        // cadence-backed walking gate — easier to arm early
        val zcBurst = recentZcCount(2200)            // was 1600ms
        val walkingFromZc = cadenceOk || zcBurst >= 2 // was >=3
        val gateWalkingArm = if (calibrated) (walkingNow || preWalkGraceActive || walkingFromZc) else true

        val gateDominance = verticalDominant || handCarryRelax || useDominant
        val gateNotTilted = !isHandTilted || walkingFromZc

        val dwellOk = (lastCountedMs < 0) || bandDwellOkSinceCount || visitedOppSideSinceCount
        // OR-based rearm: opp-side OR in-band dwell (plus legacy flags)
        val oppOk = (lastCountedMs < 0) || !requireOppSide || visitedOppSideSinceCount || bandDwellOkSinceCount

        // ================= Overshoot/peak latch logic =================
        val overshootThreshold = outOvershootEff * band

        // Arm when everything except overshoot is satisfied at the phase cross
        val canArm = phaseCross && slopeOk && okInterval && gateWalkingArm && oppOk && dwellOk && gateDominance && gateNotTilted
        if (canArm && !pendingCross) {
            pendingCross = true
            pendingStartMs = nowMs
            pendingBand = band
            pendingOvershootFactor = outOvershootEff
            pendingUseDominant = useDominant
            pendingForUp = wantUp
            pendingPrevSlopeSign = if (dSmoothDt >= 0.0) 1 else -1
            pendingExt = smoothAxis
            Log.i(
                "DEBUGAPP",
                "STEP pending: waiting overshoot (need >= ${"%.2f".format(overshootThreshold)}; band=${"%.2f".format(band)} factor=${"%.2f".format(outOvershootEff)})"
            )
        }

        // If armed, track extremum and commit on overshoot OR local-peak, with a cadence-aware timeout
        if (pendingCross) {
            // Dynamic window from cadence (dominant gets a bit longer)
            val estPeriodMs = when {
                stepIntervals.isNotEmpty() -> stepIntervals.average()
                cadenceHz > 0.0 -> 1000.0 / cadenceHz
                else -> 1000.0 / cfg.cadenceMaxHz
            }
            val windowMs = if (pendingUseDominant)
                (0.38 * estPeriodMs).coerceIn(130.0, 220.0)
            else
                (0.30 * estPeriodMs).coerceIn(110.0, 180.0)

            val elapsed = (nowMs - pendingStartMs).toDouble()

            // Track running extremum while slope is in the same direction; detect local max/min when slope flips
            val slopeSign = if (dSmoothDt >= 0.0) 1 else -1
            if (pendingForUp) {
                if (smoothAxis > pendingExt) pendingExt = smoothAxis
            } else {
                if (smoothAxis < pendingExt) pendingExt = smoothAxis
            }
            val peakFactor = if (pendingUseDominant) 1.15 else 1.05 // was 1.22 on dominant
            val reachedOvershoot = if (pendingForUp) (smoothAxis >= (pendingOvershootFactor * pendingBand))
            else             (smoothAxis <= -(pendingOvershootFactor * pendingBand))
            val reachedPeak = if (pendingForUp)
                (pendingPrevSlopeSign > 0 && slopeSign < 0 && pendingExt >=  (peakFactor * pendingBand))
            else
                (pendingPrevSlopeSign < 0 && slopeSign > 0 && pendingExt <= -(peakFactor * pendingBand))

            val timedOut = elapsed > windowMs
            val reversed = if (pendingForUp) (smoothAxis <= -pendingBand) else (smoothAxis >= pendingBand)

            if (reachedOvershoot || reachedPeak) {
                val okIntervalNow = (lastCountedMs < 0) || (nowMs - lastCountedMs >= minStepIntervalEff)
                if (okIntervalNow && gateWalkingArm && gateDominance && gateNotTilted) {
                    totalSteps += 1
                    lastCountedMs = nowMs

                    requireOppSide = true
                    visitedOppSideSinceCount = false
                    bandDwellOkSinceCount = false
                    sumInBandSinceCountMs = 0.0

                    // End pre-walk grace and start tilt quiet period
                    preWalkGraceUntilMs = -1
                    lastWalkingTrueMs = nowMs

                    // Remember last commit phase and alternate expectation
                    lastCommitUp = if (pendingForUp) +1 else -1
                    expectUp = if (expectUp == 0) -lastCommitUp else -expectUp

                    Log.i(
                        "DEBUGAPP",
                        "STEP commit: method=${if (reachedOvershoot) "overshoot" else "peak"} peak=${"%.2f".format(pendingExt)} band=${"%.2f".format(pendingBand)} window=${"%.0f".format(windowMs)}ms"
                    )

                    pendingCross = false
                    listener?.onSteps(0, totalSteps)
                } else {
                    if (timedOut || reversed) pendingCross = false
                }
            } else if (timedOut || reversed) {
                pendingCross = false
            }
            pendingPrevSlopeSign = slopeSign
        }

        // Debug logging when we *block* a potential step at the moment of the cross (not already pending)
        if (phaseCross && !pendingCross) {
            var culprit: String? = null
            val overshootOkNow = if (wantUp) (smoothAxis >= overshootThreshold) else (smoothAxis <= -overshootThreshold)
            if (!canArm && !overshootOkNow) {
                if (!gateWalkingArm) {
                    val phaseTxt = if (calibrated) "calibrated" else "precal"
                    val zcBurst = recentZcCount(2200)
                    culprit = "walkingNow=false (phase=$phaseTxt, avgSma=$avgSma, thresh=$threshAvgSma, zcBurst=$zcBurst, cadenceOk=$cadenceOk)"
                }
                if (culprit == null && !gateDominance) culprit = "dominance=false (vertRatio=$vertRatio, handCarryRelax=$handCarryRelax)"
                if (culprit == null && !gateNotTilted) culprit = "isHandTilted=true"
                if (culprit == null && !dwellOk) culprit = "rearm_dwell=false (inBand=${"%.0f".format(sumInBandSinceCountMs)}ms)"
                if (culprit == null && !oppOk) culprit = "rearm_opp=false"
                if (culprit == null && !slopeOk) culprit = "slopeOk=false"
                if (culprit == null && !okInterval) {
                    val since = if (lastCountedMs >= 0) (nowMs - lastCountedMs) else -1
                    culprit = "okInterval=false (Δt=${since}ms < min=${minStepIntervalEff}ms)"
                }
                if (culprit == null && !cadenceOk) {
                    val cvTxt = if (lastComputedCv.isNaN()) "n/a" else "%.2f".format(lastComputedCv)
                    val phaseTxt = if (cadenceWarmup) "warmup" else "steady"
                    culprit = "cadenceOk=false (cadenceHz=%.3f, cv=$cvTxt, phase=$phaseTxt)".format(cadenceHz)
                }
                if (culprit != null) {
                    Log.i(
                        "DEBUGAPP",
                        "STEP blocked: $culprit | dwellOk=$dwellOk overshootOk=${overshootOkNow} " +
                                "slopeOk=$slopeOk useDominant=$useDominant visitedOpp=$visitedOppSideSinceCount bandDwellOk=$bandDwellOkSinceCount"
                    )
                }
            }
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

        // update previous states
        prevSmoothAxis = smoothAxis
        prevSmoothTsMs = nowMs
        prevWalkingNow = walkingNow
    }

    /** Calibrate from REST samples (stand still, same carry pose). */
    fun calibrateRest(rest: List<Sample>) {
        if (rest.isEmpty()) return

        // Use the last timestamp as the calibration time reference
        val calMs = rest.last().timestampNs / 1_000_000L
        if (lastCalibMs > 0 && (calMs - lastCalibMs) < 5000) {
            Log.i("STEP", "Calibration skipped: too soon since last (Δt=${calMs - lastCalibMs}ms)")
            return
        }

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

        // --- Quietness check ---
        val quiet = (0 until rest.size).count { linMagVals[it] < 0.8 && omegaVals[it] < 0.8 }
        val quietFrac = if (rest.isNotEmpty()) (quiet.toDouble() / rest.size) else 0.0
        val proceed = quietFrac >= 0.75 || !calibrated
        if (!proceed) {
            Log.i("STEP", "Calibration rejected: rest too noisy (quiet=%.0f%%)".format(quietFrac * 100))
            return
        }

        // --- Robust (trimmed) baselines ---
        fun trimmedMeanSd(arr: DoubleArray, trim: Double = 0.10): Pair<Double, Double> {
            if (arr.isEmpty()) return 0.0 to 0.0
            val idx = arr.indices.sortedBy { arr[it] }
            val n = idx.size
            val a = (n * trim).toInt().coerceAtMost(n - 1)
            val b = (n - a).coerceAtLeast(a + 1)
            var sum = 0.0
            var sum2 = 0.0
            var k = 0
            for (i in a until b) {
                val v = arr[idx[i]]
                sum += v; sum2 += v * v; k++
            }
            val mean = if (k > 0) sum / k else 0.0
            val varv = if (k > 1) (sum2 / k - mean * mean) else 0.0
            return mean to sqrt(max(0.0, varv))
        }

        val (muSma, sdSma) = trimmedMeanSd(smaVals, 0.10)
        muRestSMA = muSma
        sdRestSMA = sdSma

        val (_, sdVertAbs) = trimmedMeanSd(vertAbs, 0.10)
        stdRestAxis = sdVertAbs // use SD of |aVert| at rest

        // --- Auto-tune band multiplier from robust percentile ---
        fun percentile(p: Double, arr: DoubleArray): Double {
            if (arr.isEmpty()) return 0.0
            val sorted = arr.copyOf().apply { sort() }
            val idxd = ((sorted.size - 1) * p).coerceIn(0.0, (sorted.size - 1).toDouble())
            val lo = idxd.toInt()
            val hi = min(sorted.size - 1, lo + 1)
            val frac = idxd - lo
            return sorted[lo] * (1 - frac) + sorted[hi] * frac
        }
        val q990 = percentile(0.990, vertAbs)
        val newCBand = if (stdRestAxis > 1e-9) max(1.2, q990 / stdRestAxis) else 2.0

        // Tilt thresholds from rest
        val muOmega = omegaVals.average()
        val sdOmega = sqrt(omegaVals.map { (it - muOmega) * (it - muOmega) }.average())
        val muLin = linMagVals.average()
        val sdLin = sqrt(linMagVals.map { (it - muLin) * (it - muLin) }.average())

        val newTiltOmega = (muOmega + 3.0 * sdOmega).coerceAtLeast(0.8)
        val newTiltLin = max(1.0, muLin + 2.5 * sdLin)
        val newTiltRatio = max(1.8, 0.8 * (newTiltOmega / (newTiltLin + 1e-9)))

        cfg = cfg.copy(
            smaWindow = min(120, max(10, (fsHz * 1.0).toInt())),
            alpha = (dt / (0.25 + dt)).coerceIn(0.05, 0.8),
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
        axisEmaFast = Ema(0.65)
        omegaEma = Ema(cfg.tiltOmegaEmaAlpha)
        linEma = Ema(cfg.tiltLinEmaAlpha)
        gDirEma = Ema(cfg.tiltGDirEmaAlpha)

        // Reset runtime state
        calibrated = true
        inBand = true
        inBandSinceMs = -1
        lastCountedMs = -1
        lastTiltMs = -1
        zcTimesMs.clear()
        hasPrevU = false
        prevUTsNs = 0L
        prevSmoothAxis = 0.0
        prevSmoothTsMs = -1
        domX = 0.0; domY = 0.0; domZ = 0.0
        prevWalkingNow = false
        lastWalkingTrueMs = -1

        // Reset rearm/hysteresis & latch states
        requireOppSide = false
        visitedOppSideSinceCount = false
        bandDwellOkSinceCount = false
        sumInBandSinceCountMs = 0.0
        pendingCross = false
        pendingStartMs = -1
        pendingExt = 0.0
        pendingPrevSlopeSign = 1
        lastCommitUp = 0
        expectUp = 0

        // Clear pre-walk grace and stillness trackers
        stillSinceMs = -1
        preWalkGraceUntilMs = -1
        lastCalibMs = calMs

        Log.i("STEP", "Calibrated: mu=${muRestSMA} sd=${sdRestSMA} stdAxis=${stdRestAxis}")
    }

    fun reset() {
        calibrated = false
        muRestSMA = 0.0; sdRestSMA = 0.0; stdRestAxis = 0.0
        totalSteps = 0
        smaWin.clear(); vertEWin.clear(); horizEWin.clear()
        inBand = true
        inBandSinceMs = -1
        lastCountedMs = -1; lastTiltMs = -1
        zcTimesMs.clear()
        hasPrevU = false
        prevUTsNs = 0L
        prevSmoothAxis = 0.0
        prevSmoothTsMs = -1
        domX = 0.0; domY = 0.0; domZ = 0.0
        prevWalkingNow = false
        lastWalkingTrueMs = -1
        requireOppSide = false
        visitedOppSideSinceCount = false
        bandDwellOkSinceCount = false
        sumInBandSinceCountMs = 0.0
        pendingCross = false
        pendingStartMs = -1
        pendingExt = 0.0
        pendingPrevSlopeSign = 1
        stillSinceMs = -1
        preWalkGraceUntilMs = -1
        lastCommitUp = 0
        expectUp = 0
        lastCalibMs = -1
    }
}
