import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * StepCounterZC
 *
 * Implements the IMCOM-2015 pipeline:
 *  1) 5-point moving average smoothing
 *  2) Walking detection via SMA threshold
 *  3) ADZC: count zero-crossings that originate outside ±c·STD_rest
 *  4) Linear regression mapping (#ZC) -> step count (steps ≈ a·zc + b)
 *
 * Feed samples (ms, ax, ay, az) at ~20 Hz (recommended). Call [addSample].
 * Provide a callback to receive emitted steps. Use [startRestCalibration]/[finishRestCalibration]
 * to determine STD_rest; optionally call [fitRegression] after a guided walk to refine (a, b).
 */
class StepCounterZC(
    private val cfg: Config = Config(),
    private val onStep: (Long) -> Unit
) {

    // ----- Public state (read-only from outside via getters) -----
    val isCalibrated: Boolean get() = sdRest > 0.0
    val zeroCrossings: Long get() = zcCount
    val stepsEstimated: Long get() = stepAcc.toLong()
    val dominantAxis: Int get() = domAxis   // 0=X, 1=Y, 2=Z
    val regressionA: Double get() = regA
    val regressionB: Double get() = regB
    val stdRest: Double get() = sdRest

    // ----- Configuration -----
    data class Config(
        val gravityAlpha: Double = 0.90,     // LPF for gravity estimate
        val bufferWindowMs: Int = 2500,      // general rolling window for stats (>= largest window below)
        val smaWindowMs: Int = 1000,         // SMA gate window (~1s)
        val smaK: Double = 1.0,              // walking gate: SMA >= smaK * STD_rest
        val adzcC: Double = 2.0,             // ADZC band multiplier: ±c * STD_rest
        val zcMinIntervalMs: Long = 120,     // min interval between valid ZCs
        val domAxisMode: DomAxisMode = DomAxisMode.AUTO,
        val domAxisWindowMs: Int = 1500,     // variance window to pick dominant axis when AUTO
        val detrendWindowMs: Int = 1000,     // mean-centering window for selected axis
        val eps: Double = 1e-6,
        val debug: Boolean = false,
        val logger: ((String) -> Unit)? = null
    )

    enum class DomAxisMode { AUTO, X, Y, Z }

    // ----- Input sample -----
    data class Sample(
        val tMillis: Long,
        val ax: Double,
        val ay: Double,
        val az: Double
    )

    // ----- Gravity (LPF) -----
    private var gX = 0.0
    private var gY = 0.0
    private var gZ = 0.0

    // ----- 5-point moving average buffers -----
    private val smoothN = 5
    private val sX = ArrayDeque<Double>(smoothN)
    private val sY = ArrayDeque<Double>(smoothN)
    private val sZ = ArrayDeque<Double>(smoothN)

    // ----- Rolling buffers (linear accel & timestamps) -----
    private val qT = ArrayDeque<Long>()
    private val qLX = ArrayDeque<Double>()
    private val qLY = ArrayDeque<Double>()
    private val qLZ = ArrayDeque<Double>()

    // ----- Calibration: STD_rest (computed from rest) -----
    private var collectingRest = false
    private val restMag = ArrayList<Double>() // |lin|
    private var sdRest = 0.0                  // STD_rest

    // ----- Dominant axis -----
    private var domAxis = 2 // default Z

    // ----- ADZC state machine -----
    private var lastSign = 0          // sign of detrended sample at t-1
    private var armed = false         // becomes true only when |s| >= band; next sign change counts one ZC
    private var lastBeyondBand = false
    private var lastZcTime = 0L

    // ----- Regression and step emission -----
    private var zcCount = 0L
    private var stepAcc = 0.0         // accumulated emitted integer steps
    private var regA = 0.50           // default slope; refine via fitRegression()
    private var regB = 0.00           // default intercept

    // ---------------------------------------------------------------------------------------------

    fun reset() {
        gX = 0.0; gY = 0.0; gZ = 0.0
        sX.clear(); sY.clear(); sZ.clear()
        qT.clear(); qLX.clear(); qLY.clear(); qLZ.clear()
        collectingRest = false
        restMag.clear()
        sdRest = 0.0
        domAxis = when (cfg.domAxisMode) {
            DomAxisMode.X -> 0
            DomAxisMode.Y -> 1
            DomAxisMode.Z, DomAxisMode.AUTO -> 2
        }
        lastSign = 0
        armed = false
        lastBeyondBand = false
        lastZcTime = 0L
        zcCount = 0L
        stepAcc = 0.0
        regA = 0.50
        regB = 0.00
    }

    // ----- REST calibration -----
    fun startRestCalibration() {
        collectingRest = true
        restMag.clear()
        log("Rest calibration started.")
    }

    fun finishRestCalibration(): Double {
        collectingRest = false
        sdRest = std(restMag)
        if (sdRest <= 0.0) {
            // provide a small nonzero fallback to avoid division by zero in early runs
            sdRest = 0.05
        }
        log("Rest calibration finished. STD_rest=$sdRest")
        return sdRest
    }

    // ----- Regression calibration -----
    fun fitRegression(knownSteps: Int) {
        if (zcCount > 0) {
            regA = knownSteps.toDouble() / zcCount.toDouble()
            regB = 0.0
            log("Regression fitted: a=$regA, b=$regB using zc=$zcCount and steps=$knownSteps")
        } else {
            log("Regression fit skipped: zcCount=0")
        }
    }

    // ----- Persist/restore minimal state -----
    data class Persisted(val sdRest: Double, val domAxis: Int, val a: Double, val b: Double)

    fun snapshot(): Persisted = Persisted(sdRest, domAxis, regA, regB)

    fun restore(p: Persisted) {
        sdRest = max(0.0, p.sdRest)
        domAxis = p.domAxis.coerceIn(0, 2)
        regA = p.a
        regB = p.b
        log("State restored: STD_rest=$sdRest, dom=$domAxis, a=$regA, b=$regB")
    }

    // ----- Main entry point -----
    fun addSample(s: Sample) {
        val (axS, ayS, azS) = smooth5(s.ax, s.ay, s.az)

        // Gravity LPF
        val a = cfg.gravityAlpha
        gX = a * gX + (1.0 - a) * axS
        gY = a * gY + (1.0 - a) * ayS
        gZ = a * gZ + (1.0 - a) * azS

        // Linear acceleration
        val lx = axS - gX
        val ly = ayS - gY
        val lz = azS - gZ

        // Keep rolling buffers
        qT.add(s.tMillis)
        qLX.add(lx)
        qLY.add(ly)
        qLZ.add(lz)
        trimBuffers(s.tMillis)

        // Rest calibration collection (|lin| magnitude)
        if (collectingRest) {
            restMag.add(abs(lx) + abs(ly) + abs(lz))
            return
        }

        // Require STD_rest for gates/band
        val band = cfg.adzcC * (if (sdRest > 0.0) sdRest else 0.05)

        // Walking detection via SMA threshold
        val sma = currentSMA()
        val walkingNow = sma >= (cfg.smaK * (if (sdRest > 0.0) sdRest else 0.05))
        if (!walkingNow) {
            // Disarm ADZC until movement is significant again
            armed = false
            lastBeyondBand = false
            lastSign = 0
            return
        }

        // Dominant axis selection
        domAxis = when (cfg.domAxisMode) {
            DomAxisMode.X -> 0
            DomAxisMode.Y -> 1
            DomAxisMode.Z -> 2
            DomAxisMode.AUTO -> pickDominantAxis()
        }

        // Detrend (mean-center) selected axis
        val sVal = detrended(domAxis)

        // ADZC logic
        val signNow = sgn(sVal, cfg.eps)
        val beyond = abs(sVal) >= band

        // "Armed" when we first exceed the band on any side
        if (beyond) {
            armed = true
            lastBeyondBand = true
        }

        // Count a ZC only if: (1) sign changed, (2) we were armed (i.e., originated beyond band),
        // (3) enough time since previous ZC
        if (armed && signNow != 0 && lastSign != 0 && signNow != lastSign) {
            val dt = s.tMillis - lastZcTime
            if (dt >= cfg.zcMinIntervalMs) {
                zcCount += 1
                lastZcTime = s.tMillis
                armed = false         // must exceed band on the new side to re-arm
                lastBeyondBand = false
                emitStepsFromRegression(s.tMillis)
            }
        }

        if (signNow != 0) lastSign = signNow
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun smooth5(ax: Double, ay: Double, az: Double): Triple<Double, Double, Double> {
        fun push(q: ArrayDeque<Double>, v: Double) { q.add(v); if (q.size > smoothN) q.removeFirst() }
        push(sX, ax); push(sY, ay); push(sZ, az)
        val n = sX.size.coerceAtLeast(1)
        val mx = sX.sum() / n
        val my = sY.sum() / n
        val mz = sZ.sum() / n
        return Triple(mx, my, mz)
    }

    private fun trimBuffers(tNow: Long) {
        val horizon = cfg.bufferWindowMs.toLong()
        while (qT.isNotEmpty() && (tNow - qT.first()) > horizon) {
            qT.removeFirst()
            qLX.removeFirst()
            qLY.removeFirst()
            qLZ.removeFirst()
        }
    }

    private fun currentSMA(): Double {
        if (qT.isEmpty()) return 0.0
        val tNow = qT.last()
        val w = cfg.smaWindowMs.toLong()
        var sum = 0.0
        var n = 0
        for (i in qT.indices.reversed()) {
            val ti = qT.elementAt(i)
            if (tNow - ti > w) break
            val ax = qLX.elementAt(i)
            val ay = qLY.elementAt(i)
            val az = qLZ.elementAt(i)
            sum += abs(ax) + abs(ay) + abs(az)
            n++
        }
        if (n == 0) return 0.0
        return sum / n
    }

    private fun pickDominantAxis(): Int {
        val tNow = qT.lastOrNull() ?: return domAxis
        val w = cfg.domAxisWindowMs.toLong()
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val zs = ArrayList<Double>()
        for (i in qT.indices.reversed()) {
            val ti = qT.elementAt(i)
            if (tNow - ti > w) break
            xs.add(qLX.elementAt(i))
            ys.add(qLY.elementAt(i))
            zs.add(qLZ.elementAt(i))
        }
        if (xs.size < 3) return domAxis
        val vx = variance(xs)
        val vy = variance(ys)
        val vz = variance(zs)
        return when {
            vx >= vy && vx >= vz -> 0
            vy >= vx && vy >= vz -> 1
            else -> 2
        }
    }

    private fun detrended(axis: Int): Double {
        if (qT.isEmpty()) return 0.0
        val tNow = qT.last()
        val w = cfg.detrendWindowMs.toLong()
        var sum = 0.0
        var n = 0
        fun at(i: Int) = when (axis) {
            0 -> qLX.elementAt(i)
            1 -> qLY.elementAt(i)
            else -> qLZ.elementAt(i)
        }
        for (i in qT.indices.reversed()) {
            val ti = qT.elementAt(i)
            if (tNow - ti > w) break
            sum += at(i)
            n++
        }
        val mean = if (n > 0) sum / n else 0.0
        val latest = at(qT.size - 1)
        return latest - mean
    }

    private fun emitStepsFromRegression(tMillis: Long) {
        val est = regA * zcCount + regB
        val emit = floor(est - stepAcc).toInt()
        if (emit <= 0) return
        repeat(emit) { onStep(tMillis) }
        stepAcc += emit
    }

    private fun sgn(v: Double, eps: Double): Int {
        return when {
            v > eps -> +1
            v < -eps -> -1
            else -> 0
        }
    }

    private fun variance(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val m = xs.sum() / xs.size
        var s = 0.0
        for (x in xs) s += (x - m) * (x - m)
        return s / xs.size
    }

    private fun std(xs: List<Double>): Double = sqrt(variance(xs))

    private fun log(msg: String) {
        if (cfg.debug) cfg.logger?.invoke("[StepCounterZC] $msg")
    }
}
