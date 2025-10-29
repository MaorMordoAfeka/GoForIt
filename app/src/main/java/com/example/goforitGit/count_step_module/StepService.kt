package com.example.goforitGit.count_step_module

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import android.hardware.*
import androidx.core.app.ServiceCompat
import com.example.goforitGit.R




class StepService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var stepper: StepCounterZC

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locCallback: LocationCallback

    private lateinit var sm: SensorManager
    private var acc: Sensor? = null
    private var grav: Sensor? = null
    private var gyro: Sensor? = null

    private var emaHz = 0.0
    private var lastTsNs: Long? = null

    // --- Binder to expose service instance to the activity if necessary ---
    inner class LocalBinder : android.os.Binder() {
        fun getService(): StepService = this@StepService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()

        createNotifChannel()

        // Choose FGS types based on current runtime permission
        val types = if (hasLocPerm()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

        ServiceCompat.startForeground(this, 42, buildNotif("Counting steps…"), types)

        // Stepper
        stepper = StepCounterZC(this)
        scope.launch {
            stepper.stepsFlow.collectLatest { StepBus.steps.value = it }
        }
        scope.launch {
            stepper.mode.collectLatest { StepBus.mode.value = it }
        }
        stepper.start()

        // Sensors (for live snapshot; stepper has its own listener internally)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rateUs = (1_000_000 / 60)
        acc?.let { sm.registerListener(this, it, rateUs, 0) }
        grav?.let { sm.registerListener(this, it, rateUs, 0) }
        gyro?.let { sm.registerListener(this, it, rateUs, 0) }

        // GPS -> feeds speed to stepper for DRIVING/CYCLING gating
        fused = LocationServices.getFusedLocationProviderClient(this)
        locCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val loc = res.lastLocation ?: return
                val speed = if (loc.hasSpeed()) loc.speed else null
                StepBus.speedMps.value = speed
                stepper.updateSpeedMps(speed)
                updateNotificationForMode()
            }
        }
        startLocationUpdates()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        updateNotificationForMode()
        return START_STICKY
    }

    override fun onDestroy() {
        sm.unregisterListener(this)
        fused.removeLocationUpdates(locCallback)
        stepper.stop()
        scope.cancel()
        super.onDestroy()
    }

    // --- SensorEventListener (snapshot for UI) ---
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {

            Sensor.TYPE_GRAVITY -> { gx = e.values[0]; gy = e.values[1]; gz = e.values[2] }

            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE -> {
                val ax = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) e.values[0] else StepBus.sensors.value.ax
                val ay = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) e.values[1] else StepBus.sensors.value.ay
                val az = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) e.values[2] else StepBus.sensors.value.az

                val wx = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) e.values[0] else StepBus.sensors.value.wx
                val wy = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) e.values[1] else StepBus.sensors.value.wy
                val wz = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) e.values[2] else StepBus.sensors.value.wz

                lastTsNs?.let { p ->
                    val dt = (e.timestamp - p) / 1e9
                    if (dt > 0) {
                        val inst = 1.0 / dt
                        val a = 0.10
                        emaHz = if (emaHz == 0.0) inst else (1 - a) * emaHz + a * inst
                    }
                }
                lastTsNs = e.timestamp

                // Data classes in Kotlin provide an auto-generated copy(...) function that returns
                // a new instance with any fields you specify changed and all others kept the same.
                StepBus.sensors.value = StepBus.sensors.value.copy(
                    ax = ax, ay = ay, az = az,
                    gx = gx, gy = gy, gz = gz,
                    wx = wx, wy = wy, wz = wz,
                    emaHz = emaHz
                )
            }
        }
    }

    // NO OP
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // NO OP
    }

    // --- Location helpers ---
    private fun hasLocPerm() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocPerm()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(3f)
            .build()
        fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper())
    }

    // --- Foreground notification ---
    private fun createNotifChannel() {
        val id = "step_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(id, "Step counting", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotif(text: String): Notification =
        NotificationCompat.Builder(this, "step_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Step Counter running")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotificationForMode() {
        val n = buildNotif("Mode: ${StepBus.mode.value} • Steps: ${StepBus.steps.value}")
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(42, n)
    }

    override fun onBind(intent: Intent?) = null
}
