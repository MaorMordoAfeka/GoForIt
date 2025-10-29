package com.example.goforitGit

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.goforitGit.count_step_module.StepService
import com.example.goforitGit.count_step_module.StepViewModel
import kotlin.math.sqrt


class MainActivity : AppCompatActivity() {
    private val vm: StepViewModel by viewModels()

    fun f3(value: Float): String =
        String.format(java.util.Locale.US, "%.3f", value)

    private fun startServiceSafely() {
        // Start FGS regardless; service runs without location permission
        ContextCompat.startForegroundService(this, Intent(this, StepService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // start service exactly as you already do (no binding needed)
        // it continues to push to StepBus under the hood.
        startServiceSafely()

        // Observe LiveData (lifecycle-aware, no leaks)
        vm.steps.observe(this) { steps -> findViewById<TextView>(R.id.count).text = "Steps: $steps" }
        vm.mode.observe(this) { mode -> findViewById<TextView>(R.id.modeText).text = "current mode: $mode" }
        vm.cadenceSpm.observe(this) { spm -> findViewById<TextView>(R.id.avgCadenceVal).text = "SPM: $spm" }
        vm.stepsToday.observe(this) { n -> findViewById<TextView>(R.id.todayStepsVal).text = n.toString() }
        vm.sensorsData.observe(this) {
            findViewById<TextView>(R.id.ax).text = "ax: ${f3(it.ax)}"
            findViewById<TextView>(R.id.ay).text = "ay: ${f3(it.ay)}"
            findViewById<TextView>(R.id.az).text = "az: ${f3(it.az)}"
            findViewById<TextView>(R.id.amag).text = "|a|: ${f3(sqrt(it.ax*it.ax + it.ay*it.ay + it.az*it.az))}"

            findViewById<TextView>(R.id.wx).text = "wx: ${f3(it.wx)}"
            findViewById<TextView>(R.id.wy).text = "wy: ${f3(it.wy)}"
            findViewById<TextView>(R.id.wz).text = "wz: ${f3(it.wz)}"
            findViewById<TextView>(R.id.wmag).text = "|ω|: ${f3(sqrt(it.wx*it.wx + it.wy*it.wy + it.wz*it.wz))} rad/s"

            findViewById<TextView>(R.id.lx).text = "lx: ${f3(it.ax-it.gx)}"
            findViewById<TextView>(R.id.ly).text = "ly: ${f3(it.ay-it.gy)}"
            findViewById<TextView>(R.id.lz).text = "lz: ${f3(it.az-it.gz)}"
            findViewById<TextView>(R.id.lmag).text = "|lin|: ${f3(sqrt((it.ax-it.gx)*(it.ax-it.gx) + (it.ay-it.gy)*(it.ay-it.gy) + (it.az-it.gz)*(it.az-it.gz)))}"

            findViewById<TextView>(R.id.gx).text = "gx: ${f3(it.gx)}"
            findViewById<TextView>(R.id.gy).text = "gy: ${f3(it.gy)}"
            findViewById<TextView>(R.id.gz).text = "gz: ${f3(it.gz)}"
            findViewById<TextView>(R.id.gmag).text = "|g|: ${f3(sqrt(it.gx*it.gx + it.gy*it.gy + it.gz*it.gz))}"

            findViewById<TextView>(R.id.hzText).text = "emaHz: ${f3(it.emaHz.toFloat())}"
        }
    }
}