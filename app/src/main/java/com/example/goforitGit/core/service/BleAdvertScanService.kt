package com.example.goforitGit.core.service

import android.Manifest
import android.R
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.goforitGit.navigation.MainActivity
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Foreground BLE scanner service, hardened for:
 * - Null manufacturerSpecificData (your previous NPE)
 * - Boot timing: Bluetooth not ready / user locked
 * - Android 12+ runtime permissions
 * - Android 14 / targetSdk 34 foreground service type enforcement (uses CONNECTED_DEVICE type)
 * - Scan "stalling" after reboot (LOW_LATENCY burst + watchdog restart)
 *
 * Notes:
 * - This service expects the Pi to advertise Manufacturer Data under companyId (default 0xFFFF).
 * - "Toasts" are not reliable after reboot/lock; this also updates the foreground notification.
 */
class BleAdvertScanService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val companyId = 0xFFFF // Pi manufacturer data company id
    private val deviceNameHint = "RPi5 Beacon" // optional: used only as a soft check

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // MUST be unique vs StepService notification id.
    private val notifId = 52042
    private val notifChannelId = "ble_scan"
    private val resumeChannelId = "ble_resume"

    private var scanner: BluetoothLeScanner? = null

    private val stopRequested = AtomicBoolean(false)
    @Volatile
    private var lastStartId: Int = 0

    @Volatile
    private var lastResultAtMs: Long = 0L
    @Volatile
    private var lastSeenMsg: String? = null
    @Volatile
    private var lastToastAtMs: Long = 0L

    private var btStateReceiver: BroadcastReceiver? = null
    private var userUnlockReceiver: BroadcastReceiver? = null

    // Scan tuning
    private val burstLowLatencyMs = 12_000L
    private val watchdogPeriodMs = 15_000L
    private val staleNoResultsMs = 20_000L

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopRequested.get()) return

            val now = System.currentTimeMillis()
            val age = now - lastResultAtMs
            if (lastResultAtMs != 0L && age > staleNoResultsMs) {
                Log.i(TAG, "No scan results for ${age}ms -> restarting scan (burst)")
                updateBleNotification("Scanning… (restarting)")
                restartScanBurstThenBalanced()
            }

            mainHandler.postDelayed(this, watchdogPeriodMs)
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runCatching { handleResult(result) }
                .onFailure { t -> Log.w(TAG, "handleResult() failed", t) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { r ->
                runCatching { handleResult(r) }
                    .onFailure { t -> Log.w(TAG, "handleResult() failed", t) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            updateBleNotification("BLE scan failed ($errorCode)")
            stopSelfSafely("onScanFailed($errorCode)")
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        // Must promote immediately; use CONNECTED_DEVICE type to avoid Android 14 location FGS enforcement.
        if (!promoteToForegroundOrStop()) return

        updateBleNotification("Starting BLE scanning…")

        // If user is still locked right after reboot, defer scanning until unlock.
        if (!isUserUnlocked()) {
            updateBleNotification("Waiting for unlock to scan…")
            registerUserUnlockReceiver()
            return
        }

        // Ensure BT is ready; if not, keep FGS alive and wait.
        ensureBluetoothReadyAndScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId

        when (intent?.action) {
            ACTION_START_FGS -> {
                // Re-run startup sequence (useful after boot receiver or when system restarts service)
                if (!stopRequested.get()) {
                    if (!isUserUnlocked()) {
                        updateBleNotification("Waiting for unlock to scan…")
                        registerUserUnlockReceiver()
                    } else {
                        ensureBluetoothReadyAndScan()
                    }
                }
            }

            ACTION_STOP -> {
                stopSelfSafely("Stop requested")
                return START_NOT_STICKY
            }
        }

        // Keeps running until explicitly stopped; system may restart after kill.
        return START_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        mainHandler.removeCallbacks(watchdog)
        unregisterBtStateReceiver()
        unregisterUserUnlockReceiver()
        stopScanBestEffort()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ---------------------------
    // Foreground start / notification
    // ---------------------------

    private fun promoteToForegroundOrStop(): Boolean {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        return try {
            ServiceCompat.startForeground(
                this,
                notifId,
                buildNotification("Scanning for BLE beacons…"),
                types
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start FGS (SecurityException).", e)
            showResumeNotification(this, "Open app to resume BLE scanning (permissions/state)")
            stopSelfSafely("FGS SecurityException")
            false
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "FGS start not allowed (boot/background).", e)
            showResumeNotification(this, "Open app to resume BLE scanning")
            stopSelfSafely("FGS not allowed")
            false
        } catch (t: Throwable) {
            Log.e(TAG, "FGS start failed", t)
            showResumeNotification(this, "Open app to resume BLE scanning")
            stopSelfSafely("FGS start failure")
            false
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_REQUEST_BLE_PERMISSIONS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, notifChannelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BLE Scanner")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateBleNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(notifId, buildNotification(text)) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(NotificationManager::class.java) ?: return

        if (nm.getNotificationChannel(notifChannelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    notifChannelId,
                    "BLE Scanning",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Foreground service for BLE scanning"
                }
            )
        }

        if (nm.getNotificationChannel(resumeChannelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    resumeChannelId,
                    "BLE Resume",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    // ---------------------------
    // Boot/lock readiness helpers
    // ---------------------------

    private fun isUserUnlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = getSystemService(UserManager::class.java) ?: return true
        return um.isUserUnlocked
    }

    private fun registerUserUnlockReceiver() {
        if (userUnlockReceiver != null) return

        userUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                unregisterUserUnlockReceiver()
                if (stopRequested.get()) return

                updateBleNotification("Unlocked. Starting scan…")
                ensureBluetoothReadyAndScan()
            }
        }

        registerReceiver(userUnlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    }

    private fun unregisterUserUnlockReceiver() {
        val r = userUnlockReceiver ?: return
        userUnlockReceiver = null
        runCatching { unregisterReceiver(r) }
    }

    private fun ensureBluetoothReadyAndScan() {
        val btMgr = getSystemService(BluetoothManager::class.java)
        val adapter = btMgr?.adapter

        if (adapter == null) {
            updateBleNotification("Bluetooth unavailable")
            stopSelfSafely("Bluetooth adapter null")
            return
        }

        if (!adapter.isEnabled) {
            updateBleNotification("Bluetooth is off — waiting")
            registerBtStateReceiver()
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            updateBleNotification("BLE scanner unavailable — waiting")
            registerBtStateReceiver()
            return
        }

        unregisterBtStateReceiver()

        // Start scan with a low-latency burst, then fall back to balanced.
        restartScanBurstThenBalanced()

        // Start watchdog to recover from stalls after reboot/OEM throttling.
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, watchdogPeriodMs)
    }

    private fun registerBtStateReceiver() {
        if (btStateReceiver != null) return

        btStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "Bluetooth STATE_ON -> attempting scan start")
                    ensureBluetoothReadyAndScan()
                }
            }
        }

        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun unregisterBtStateReceiver() {
        val r = btStateReceiver ?: return
        btStateReceiver = null
        runCatching { unregisterReceiver(r) }
    }

    // ---------------------------
    // Scanning
    // ---------------------------

    private fun restartScanBurstThenBalanced() {
        if (stopRequested.get()) return

        stopScanBestEffort()
        lastResultAtMs = 0L
        updateBleNotification("Scanning… (burst)")

        // Burst
        if (!startScanIfPermitted(ScanSettings.SCAN_MODE_LOW_LATENCY)) return

        // Then fall back
        mainHandler.postDelayed({
            if (stopRequested.get()) return@postDelayed
            stopScanBestEffort()
            updateBleNotification("Scanning for BLE beacons…")
            startScanIfPermitted(ScanSettings.SCAN_MODE_BALANCED)
        }, burstLowLatencyMs)
    }

    private fun startScanIfPermitted(mode: Int): Boolean {
        // Android 12+ requires BLUETOOTH_SCAN runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                        PackageManager.PERMISSION_GRANTED

            if (!granted) {
                updateBleNotification("Missing BLUETOOTH_SCAN permission")
                showResumeNotification(this, "Open app to grant BLE permissions")
                stopSelfSafely("Missing BLUETOOTH_SCAN")
                return false
            }
        }

        val s = scanner ?: return false

        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setReportDelay(0L)
            .build()

        // Optional filters (keep empty by default)
        val filters = mutableListOf<ScanFilter>()

        return try {
            s.startScan(filters, settings, callback)
            true
        } catch (se: SecurityException) {
            Log.w(TAG, "startScan() rejected by framework", se)
            showResumeNotification(this, "Open app to grant BLE permissions")
            stopSelfSafely("startScan SecurityException")
            false
        } catch (t: Throwable) {
            Log.e(TAG, "startScan() failed", t)
            stopSelfSafely("startScan failure")
            false
        }
    }

    private fun stopScanBestEffort() {
        val s = scanner ?: return

        val hasScanPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED

        try {
            if (hasScanPermission) {
                s.stopScan(callback)
            } else {
                // Permission might have been revoked while running; ignore failures.
                runCatching { s.stopScan(callback) }
                    .onFailure { t -> Log.w(TAG, "stopScan() rejected; ignoring.", t) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stopScan() failed during shutdown. Ignoring.", t)
        }
    }

    // ---------------------------
    // Result parsing
    // ---------------------------

    private fun handleResult(result: ScanResult) {
        if (stopRequested.get()) return

        val record = result.scanRecord ?: return

        // Fix for your previous NPE: manufacturerSpecificData can be null.
        val payload = record.manufacturerSpecificData?.get(companyId) ?: return
        if (payload.isEmpty()) return

        val msg = runCatching { payload.decodeToString() }
            .getOrElse { payload.joinToString(" ") { b -> "%02X".format(b) } }

        // Record liveness for watchdog
        lastResultAtMs = System.currentTimeMillis()

        // Optional: reduce spam (only toast if message changed or 1.5s passed)
        val now = System.currentTimeMillis()
        val shouldToast =
            (lastSeenMsg != msg) || (now - lastToastAtMs > 1500L)

        lastSeenMsg = msg
        if (shouldToast) lastToastAtMs = now

        val nameOk =
            safeDeviceName(record)?.contains(deviceNameHint, ignoreCase = true) ?: true

        val addr = safeDeviceAddress(result)
        var text = ""
        // ble bonus station is recognized
        if (nameOk && msg == "bonus station") {
            text = "Device name: ${record.deviceName} \n" +
                   "Msg: $msg"

            // contact firebase and record the bonus station visit
            serviceScope.launch {
                val r = FirebaseServerApi.recordBonusVisitResult(stationId = msg)
                r.onSuccess { ok ->
                    if (ok)
                        Log.d("BONUS", "Bonus awarded ✅")
                    else
                        Log.d("BONUS", "Bonus already claimed today")
                }.onFailure { e ->
                    Log.e("BONUS", "Bonus failed: ${e.message}", e)
                }
            }
        }
        // other ble station is recognized
        else {
            text = "Station msg: $msg"
        }

        // Notification is reliable after reboot/lock; toast is best-effort.
        updateBleNotification(text)

        if (shouldToast) {
            toast(text)
        }
    }

    private fun hasBtConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun safeDeviceAddress(result: ScanResult): String {
        if (!hasBtConnectPermission()) return "unknown"
        return runCatching { result.device.address }.getOrDefault("unknown")
    }

    private fun safeDeviceName(record: ScanRecord): String? {
        if (!hasBtConnectPermission()) return null
        return runCatching { record.deviceName }.getOrNull()
    }

    private fun toast(text: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSelfSafely(reason: String? = null) {
        if (!stopRequested.compareAndSet(false, true)) return

        val msg = reason ?: "Stopping"
        Log.i(TAG, "Stopping service safely. Reason: $msg")

        // Best-effort: show the reason briefly via notification before removing foreground.
        updateBleNotification("Stopped: $msg")

        stopScanBestEffort()

        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }

        stopSelfResult(max(1, lastStartId))
    }

    // ---------------------------
    // Companion API
    // ---------------------------

    companion object {
        private const val TAG = "BleAdvertScanService"

        const val ACTION_START_FGS = "com.example.goforitGit.ACTION_START_BLE_FGS"
        const val ACTION_STOP = "com.example.goforitGit.ACTION_STOP_BLE_FGS"

        fun start(context: Context) {
            val i = Intent(context, BleAdvertScanService::class.java).apply {
                action = ACTION_START_FGS
            }

            try {
                ContextCompat.startForegroundService(context, i)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "FGS start not allowed (boot/background).", e)
                showResumeNotification(context, "Open app to resume BLE scanning")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "FGS start failed due to app state.", e)
                showResumeNotification(context, "Open app to resume BLE scanning")
            } catch (e: SecurityException) {
                Log.w(TAG, "FGS start failed (SecurityException).", e)
                showResumeNotification(context, "Open app to resume BLE scanning (permissions)")
            }
        }

        fun stop(context: Context) {
            // Triggers onDestroy() where we stopScanBestEffort()
            context.stopService(Intent(context, BleAdvertScanService::class.java))
        }

        private fun showResumeNotification(context: Context, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return

            // Channel creation is safe even if already exists
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel("ble_resume") == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            "ble_resume",
                            "BLE Resume",
                            NotificationManager.IMPORTANCE_HIGH
                        )
                    )
                }
            }

            val pi = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    action = "ACTION_REQUEST_BLE_PERMISSIONS"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val n =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    Notification.Builder(context, "ble_resume")
                else
                    @Suppress("DEPRECATION") Notification.Builder(context)

            nm.notify(
                52043,
                n.setSmallIcon(R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("BLE Scanner")
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
