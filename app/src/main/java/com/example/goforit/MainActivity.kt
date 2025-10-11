package com.example.goforit

import android.app.Activity
import android.hardware.*
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.goforit.count_step_module.*
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, StepListener {

    private lateinit var sm: SensorManager
    private var acc: Sensor? = null
    private var grav: Sensor? = null
    private var gyro: Sensor? = null

    // latest sensor values
    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var wx = 0f; private var wy = 0f; private var wz = 0f

    // Low-pass gravity fallback (if TYPE_GRAVITY is unavailable)
    private var gLPx = 0f; private var gLPy = 0f; private var gLPz = 0f
    private val useLPGravity get() = (grav == null)

    // Stepper (uses your current StepCounterZC defaults)
    private val stepper = StepCounterZC(StepCounterZCConfig(), this)

    // ---------- UI bindings (optional; null if ID not found) ----------
    private fun tv(name: String): TextView? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }
    private fun btn(name: String): Button? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    private var stepsTv: TextView? = null
    private var hzTv: TextView? = null
    private var calibrateBtn: Button? = null

    private var axTv: TextView? = null; private var ayTv: TextView? = null; private var azTv: TextView? = null
    private var gxTv: TextView? = null; private var gyTv: TextView? = null; private var gzTv: TextView? = null
    private var wxTv: TextView? = null; private var wyTv: TextView? = null; private var wzTv: TextView? = null

    private var avgSmaTv: TextView? = null; private var threshSmaTv: TextView? = null
    private var walkingTv: TextView? = null; private var tiltTv: TextView? = null
    private var bandTv: TextView? = null; private var axisValTv: TextView? = null

    // Magnitudes
    private var omegaTv: TextView? = null
    private var aMagTv: TextView? = null
    private var gMagTv: TextView? = null

    // NEW: Linear acceleration (a - g)
    private var lxTv: TextView? = null
    private var lyTv: TextView? = null
    private var lzTv: TextView? = null
    private var lmagTv: TextView? = null

    // Hz estimation (EMA)
    private var prevTsNs: Long? = null
    private var emaHz = 0.0

    // Prevent double-tap during calibration
    private var isCalibrating = false

    private fun Float.f3() = String.format("%.3f", this)
    private fun Double.f3() = String.format("%.3f", this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views (tries multiple common IDs)
        stepsTv      = tv("count") ?: tv("stepsText") ?: tv("steps")
        hzTv         = tv("hzText") ?: tv("hz") ?: tv("freqText")
        calibrateBtn = btn("calibrateBtn") ?: btn("btnCalibrate") ?: btn("calibrate")

        axTv = tv("ax") ?: tv("tvAx"); ayTv = tv("ay") ?: tv("tvAy"); azTv = tv("az") ?: tv("tvAz")
        gxTv = tv("gx") ?: tv("tvGx"); gyTv = tv("gy") ?: tv("tvGy"); gzTv = tv("gz") ?: tv("tvGz")
        wxTv = tv("wx") ?: tv("tvWx"); wyTv = tv("wy") ?: tv("tvWy"); wzTv = tv("wz") ?: tv("tvWz")

        avgSmaTv   = tv("avgSma") ?: tv("tvAvgSma")
        threshSmaTv= tv("threshSma") ?: tv("tvThreshSma")
        walkingTv  = tv("walking") ?: tv("tvWalking")
        tiltTv     = tv("tilt") ?: tv("tvTilt")
        bandTv     = tv("band") ?: tv("tvBand")
        axisValTv  = tv("axisVal") ?: tv("tvAxisVal")

        omegaTv = tv("wmag") ?: tv("tvOmega") ?: tv("omegaRad")
        aMagTv  = tv("amag")  ?: tv("tvAMag")  ?: tv("absA")
        gMagTv  = tv("gmag")  ?: tv("tvGMag")  ?: tv("absG")

        // NEW: linear acceleration
        lmagTv = tv("lmag") ?: tv("linMag") ?: tv("tvLinMag")
        lxTv   = tv("lx")   ?: tv("linX")  ?: tv("tvLx")
        lyTv   = tv("ly")   ?: tv("linY")  ?: tv("tvLy")
        lzTv   = tv("lz")   ?: tv("linZ")  ?: tv("tvLz")

        // Sensors
        sm = getSystemService(Activity.SENSOR_SERVICE) as SensorManager
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) // leave null if unavailable
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Calibrate on tap (5 s; stand still, in your normal carry pose)
        calibrateBtn?.setOnClickListener {
            if (isCalibrating) return@setOnClickListener
            isCalibrating = true
            calibrateBtn?.isEnabled = false

            Toast.makeText(this, "Calibration started — stand still for ~5s", Toast.LENGTH_SHORT).show()

            val rest = ArrayList<Sample>(200)
            Thread {
                val start = System.nanoTime()
                while ((System.nanoTime() - start) / 1e9 < 5.0) {
                    val ts = System.nanoTime()
                    rest.add(
                        Sample(
                            ts,
                            ax, ay, az,
                            if (useLPGravity) gLPx else gx,
                            if (useLPGravity) gLPy else gy,
                            if (useLPGravity) gLPz else gz,
                            wx, wy, wz
                        )
                    )
                    Thread.sleep(15)
                }
                runOnUiThread {
                    stepper.calibrateRest(rest)
                    Toast.makeText(
                        this,
                        "Calibration complete ✓  μ=${stepper.muRestSMA.f3()}, σ=${stepper.sdRestSMA.f3()}",
                        Toast.LENGTH_SHORT
                    ).show()
                    calibrateBtn?.isEnabled = true
                    isCalibrating = false
                    Log.i("STEP", "Calibrated: mu=${stepper.muRestSMA} sd=${stepper.sdRestSMA} stdAxis=${stepper.stdRestAxis}")
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        val desiredHz = 40.0
        val samplingUs = (1_000_000.0 / desiredHz).toInt()
        sm.registerListener(this, acc, samplingUs, 0)
        grav?.let { sm.registerListener(this, it, samplingUs, 0) }
        sm.registerListener(this, gyro, samplingUs, 0)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    // ---------- Sensor events ----------
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = e.values[0]; ay = e.values[1]; az = e.values[2]
                axTv?.text = "ax: ${ax.f3()}"; ayTv?.text = "ay: ${ay.f3()}"; azTv?.text = "az: ${az.f3()}"

                // |a| magnitude (raw accel)
                val aMag = sqrt((ax*ax + ay*ay + az*az).toDouble())
                aMagTv?.text = "|a|: ${aMag.f3()}"

                // Low-pass gravity if needed
                if (useLPGravity) {
                    val alpha = 0.10f
                    gLPx = (1 - alpha) * gLPx + alpha * ax
                    gLPy = (1 - alpha) * gLPy + alpha * ay
                    gLPz = (1 - alpha) * gLPz + alpha * az
                    val gMag = sqrt((gLPx*gLPx + gLPy*gLPy + gLPz*gLPz).toDouble())
                    gMagTv?.text = "|g|: ${gMag.f3()}"
                }

                // ---------- Linear acceleration = a - g ----------
                val gxUse = if (useLPGravity) gLPx else gx
                val gyUse = if (useLPGravity) gLPy else gy
                val gzUse = if (useLPGravity) gLPz else gz

                val lx = ax - gxUse
                val ly = ay - gyUse
                val lz = az - gzUse
                val lMag = sqrt((lx*lx + ly*ly + lz*lz).toDouble())

                lxTv?.text = "lx: ${lx.f3()}"
                lyTv?.text = "ly: ${ly.f3()}"
                lzTv?.text = "lz: ${lz.f3()}"
                lmagTv?.text = "|lin|: ${lMag.f3()}"

                // Hz (from accelerometer timing)
                prevTsNs?.let { p ->
                    val dt = (e.timestamp - p) / 1e9
                    if (dt > 0) {
                        val instHz = 1.0 / dt
                        val alpha = 0.10
                        emaHz = if (emaHz == 0.0) instHz else (1 - alpha) * emaHz + alpha * instHz
                        hzTv?.text = "Hz≈${"%.1f".format(emaHz)}"
                    }
                }
                prevTsNs = e.timestamp

                // Feed stepper (use LP gravity if needed)
                stepper.addSample(
                    Sample(
                        timestampNs = e.timestamp,
                        ax = ax, ay = ay, az = az,
                        gx = if (useLPGravity) gLPx else gx,
                        gy = if (useLPGravity) gLPy else gy,
                        gz = if (useLPGravity) gLPz else gz,
                        wx = wx, wy = wy, wz = wz
                    )
                )
            }
            Sensor.TYPE_GRAVITY -> {
                gx = e.values[0]; gy = e.values[1]; gz = e.values[2]
                gxTv?.text = "gx: ${gx.f3()}"; gyTv?.text = "gy: ${gy.f3()}"; gzTv?.text = "gz: ${gz.f3()}"
                val gMag = sqrt((gx*gx + gy*gy + gz*gz).toDouble())
                gMagTv?.text = "|g|: ${gMag.f3()}"
            }
            Sensor.TYPE_GYROSCOPE -> {
                wx = e.values[0]; wy = e.values[1]; wz = e.values[2]
                wxTv?.text = "wx: ${wx.f3()}"; wyTv?.text = "wy: ${wy.f3()}"; wzTv?.text = "wz: ${wz.f3()}"
                val omega = sqrt((wx*wx + wy*wy + wz*wz).toDouble())
                omegaTv?.text = "|ω|: ${omega.f3()}"
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    // ---------- StepListener ----------
    override fun onSteps(delta: Int, total: Int) {
        stepsTv?.text = "Steps: $total"
    }

    override fun onDebug(d: StepCounterZC.Debug) {
        runOnUiThread {
            avgSmaTv?.text    = d.avgSma.f3()
            threshSmaTv?.text = d.threshAvgSma.f3()
            walkingTv?.text   = if (d.walkingNow) "true" else "false"
            tiltTv?.text      = if (d.isHandTilted) "true" else "false"
            bandTv?.text      = d.band.f3()
            axisValTv?.text   = d.smoothAxis.f3()
        }
    }
}
