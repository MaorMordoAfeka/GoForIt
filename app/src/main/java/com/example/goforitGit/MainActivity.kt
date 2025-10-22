package com.example.goforitGit

import StepCounterZC
import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sm: SensorManager
    private var acc: Sensor? = null

    // UI helpers (optional: resolves by id name if it exists)
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
    private var debugTv: TextView? = null
    private var aMagTv: TextView? = null
    private var calibrateBtn: Button? = null

    private var stepsTotal = 0
    private var countingEnabled = false
    private var promptedToCalibrate = false

    // Hz estimation (EMA) from accelerometer timestamps
    private var prevTsNs: Long? = null
    private var emaHz = 0.0

    // Current accel
    private var ax = 0.0f; private var ay = 0.0f; private var az = 0.0f

    // Stepper matches your current StepCounterZC.kt (callback-based)
    private val stepper: StepCounterZC by lazy {
        StepCounterZC(
            onStep = { /* tMillis */ _ ->
                stepsTotal += 1
                stepsTv?.text = "Steps: $stepsTotal"
            }
        )
    }

    private fun Float.f3() = String.format("%.3f", this)
    private fun Double.f3() = String.format("%.3f", this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind common ids if present (all are optional)
        stepsTv      = tv("count") ?: tv("stepsText") ?: tv("steps")
        hzTv         = tv("hzText") ?: tv("hz") ?: tv("freqText")
        aMagTv       = tv("amag") ?: tv("absA") ?: tv("tvAMag")
        debugTv      = tv("debug") ?: tv("tvDebug") ?: tv("logText")
        calibrateBtn = btn("calibrateBtn") ?: btn("btnCalibrate") ?: btn("calibrate")

        sm  = getSystemService(Activity.SENSOR_SERVICE) as SensorManager
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Gate step counting behind the Calibrate button.
        // When pressed we reset the stepper and start feeding it samples; it self-calibrates from the first ~2s.
        calibrateBtn?.setOnClickListener {
            stepsTotal = 0
            stepsTv?.text = "Steps: 0"
            stepper.reset()
            countingEnabled = true
            promptedToCalibrate = true // suppress future prompts
            Toast.makeText(this, "calibration started: stand still for 2 secs", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val desiredHz = 40.0
        val samplingUs = (1_000_000.0 / desiredHz).toInt()
        acc?.let { sm.registerListener(this, it, samplingUs, 0) }
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        ax = e.values[0]; ay = e.values[1]; az = e.values[2]

        // Show |a| for sanity (optional)
        val aMag = sqrt((ax*ax + ay*ay + az*az).toDouble())
        aMagTv?.text = "|a|: ${aMag.f3()}"

        // Hz estimate from sensor timestamps
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

        // Don’t feed samples until the user presses Calibrate
        if (!countingEnabled) {
            if (!promptedToCalibrate) {
                Toast.makeText(this, "Press Calibrate to enable step counting", Toast.LENGTH_SHORT).show()
                promptedToCalibrate = true
            }
            return
        }

        // Feed the stepper; StepCounterZC uses ms timestamps
        val tMillis = e.timestamp / 1_000_000L
        stepper.addSample(
            StepCounterZC.Sample(
                tMillis = tMillis,
                ax = ax.toDouble(),
                ay = ay.toDouble(),
                az = az.toDouble()
            )
        )

        // Optional: let the user know when self-calibration finished
        if (stepper.isCalibrated) {
            debugTv?.let {
                if (it.text?.contains("Calibrated:") != true) {
                    // The stepper itself logs a "Calibrated:" line via debug(); this just nudges the UI.
                    it.append("\nCalibrated ✓")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
