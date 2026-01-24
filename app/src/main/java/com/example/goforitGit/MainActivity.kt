package com.example.goforitGit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.goforitGit.bluetooth_bonus_stations_module.BleAdvertScanService
import com.example.goforitGit.count_step_module.StepService
import com.example.goforitGit.count_step_module.StepViewModel
import com.example.goforitGit.map_routes_module.MapAndRoutesActivity
import kotlin.math.sqrt


fun f3(value: Float): String =
    String.format(java.util.Locale.US, "%.3f", value)

class MainActivity : AppCompatActivity() {
    private val vm: StepViewModel by viewModels()
    private val repo by lazy {
        com.example.goforitGit.count_step_module.StepRepository.get(
            application
        )
    }

    // ------------------ ACTIVITY RECOGNITION (existing) ------------------
    private fun hasActivityPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 29)
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        else true

    // 1) Activity Recognition callback — start FGS here
    private val requestActivityRecognition =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || hasActivityPermission()) {
                // Tell the service that permissions changed (regular start)
                startService(
                    Intent(this, StepService::class.java)
                        .setAction(StepService.ACTION_PERMS_UPDATED)
                )
                // Now promote to Foreground (allowed because we're in a foreground Activity)
                startCountingServiceSafely()

                // continue the permission chain
                ensureLocationPermission()
            } else {
                Toast.makeText(this, "Physical Activity permission denied", Toast.LENGTH_SHORT)
                    .show()
                ensureLocationPermission()
            }
        }

    // 2) If already granted, also start FGS before continuing
    private fun ensureActivityPermission() {
        if (Build.VERSION.SDK_INT >= 29 && !hasActivityPermission()) {
            requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            // Permission already OK → start FGS now
            startCountingServiceSafely()
            // and continue to location/ble
            ensureLocationPermission()
        }
    }

    // ------------------ LOCATION (existing) ------------------
    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // After user answers notifications → ask Physical Activity next
            ensureActivityPermission()
        }

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    hasLocationPermission()
            if (granted) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(
                        this,
                        StepService::class.java
                    ).setAction(StepService.ACTION_PERMS_UPDATED)
                )
                // If we're on pre-12 devices, location covers BLE scan permission: try BLE now
                ensureBlePermission()
            }
        }

    private fun ensureLocationPermission() {
        if (!hasLocationPermission()) {
            requestLocation.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            // Location already granted → continue with BLE permission
            ensureBlePermission()
        }
    }

    /** Start step service with explicit user action so FGS promotion is allowed. */
    private fun startCountingServiceSafely() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, StepService::class.java).setAction(StepService.ACTION_START_FGS)
        )
    }

    private fun startBleServiceSafely() {
        BleAdvertScanService.start(this)
    }

    // ------------------ NEW: BLUETOOTH SCAN PERMISSIONS ------------------
    /** On Android 12+ we need BLUETOOTH_SCAN (and often BLUETOOTH_CONNECT if you read names). */
    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // On Android 10–11 scanning relies on location permission (already handled above)
            hasLocationPermission()
        }
    }

    private val requestBlePerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                grants[Manifest.permission.BLUETOOTH_SCAN] == true || hasBleScanPermission()
            } else {
                hasLocationPermission()
            }
            if (granted) {
                startBleServiceSafely()
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Bluetooth scan permission denied",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

    /** Ensures we have the right permission for BLE scanning, then starts the service. */
    private fun ensureBlePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (hasBleScanPermission()) {
                startBleServiceSafely()
            } else {
                // Request both; CONNECT is useful to read device name/address safely on 12+
                requestBlePerms.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            }
        } else {
            // On older devices, location is enough; if already granted, go.
            if (hasLocationPermission()) startBleServiceSafely()
        }
    }

    // ------------------ Activity lifecycle ------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // POST_NOTIFICATIONS → ACTIVITY_RECOGNITION → LOCATION → BLE (12+)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureActivityPermission()
        }

        // Also handle intent action if Activity was launched from the service notification
        if (intent?.action == "ACTION_REQUEST_BLE_PERMISSIONS") {
            ensureBlePermission()
        }

        // ----- Existing UI bindings -----
        vm.spm.observe(this) { spm ->
            findViewById<TextView>(R.id.spmText).text = "SPM: " + spm.toInt().toString()
        }

        vm.kmhLD.observe(this) { speed ->
            findViewById<TextView>(R.id.KmH).text =
                "Current Km/H: ${if (speed != null) f3(speed * 3.6f) else "0.000"}"
        }
        vm.steps.observe(this) { steps ->
            findViewById<TextView>(R.id.count).text = "Steps: $steps"
        }
        vm.mode.observe(this) { mode ->
            findViewById<TextView>(R.id.modeText).text = "current mode: $mode"
        }
        vm.stepsToday.observe(this) { n ->
            findViewById<TextView>(R.id.todayStepsVal).text = n.toString()
        }
        vm.sensorsData.observe(this) {
            findViewById<TextView>(R.id.hzText).text = "emaHz: ${f3(it.emaHz.toFloat())}"
        }

        findViewById<android.widget.Button>(R.id.queryDurationBtn).setOnClickListener {
            val etDurationMinutesInput = findViewById<android.widget.EditText>(R.id.durMinutesInput)
            val tvLastDurStepsVal = findViewById<TextView>(R.id.lastDurStepsVal)
            val tvAvgCadenceVal = findViewById<TextView>(R.id.avgCadenceVal)
            val minutes = etDurationMinutesInput.text.toString().trim().toIntOrNull()

            if (minutes == null || minutes <= 0) {
                tvLastDurStepsVal.text = "0"
                tvAvgCadenceVal.text = "0"
                android.widget.Toast.makeText(
                    this,
                    "Enter minutes > 0",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val numOfStepsInLastMinutes = repo.stepsInLastMinutes(minutes)
            tvLastDurStepsVal.text = numOfStepsInLastMinutes.toString()

            val avgStepsPerDuration = repo.computeAvgStepsPerDuration(minutes.toLong())
            tvAvgCadenceVal.text = avgStepsPerDuration.toString()
        }

        findViewById<android.widget.Button>(R.id.mapAndRoutesBtn).setOnClickListener {
            val intent = Intent(this, MapAndRoutesActivity::class.java)
            startActivity(intent)
        }

    }
}
