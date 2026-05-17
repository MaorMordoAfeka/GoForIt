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
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.navigation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Foreground BLE scanner service.
 *
 * User-facing notification logic:
 * - "Starting BLE scanner…" means the foreground service was created.
 * - "Scanning nearby stations • fast scan" means BLE scanning started in burst mode.
 * - "Scanning nearby stations • normal scan" means BLE scanning started in balanced mode.
 * - "Scan restarted automatically • checking again" means the watchdog detected stale/no results.
 * - "Station detected • ..." means Android delivered a real BLE scan result.
 * - "Bonus station detected • reward checked" means the expected beacon payload was found.
 */
class BleAdvertScanService : Service() {

    @Volatile
    private var consecutiveScanFailures = 0

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val companyId = 0xFFFF
    private val deviceNameHint = "RPi5 Beacon"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

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
    private var lastScanStartedAtMs: Long = 0L

    @Volatile
    private var lastSeenMsg: String? = null

    @Volatile
    private var lastToastAtMs: Long = 0L

    @Volatile
    private var lastNotificationText: String? = null

    @Volatile
    private var lastNotificationAtMs: Long = 0L

    private var btStateReceiver: BroadcastReceiver? = null
    private var userUnlockReceiver: BroadcastReceiver? = null

    private val burstLowLatencyMs = 12_000L
    private val watchdogPeriodMs = 30_000L
    private val staleNoResultsMs = 45_000L

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopRequested.get()) return

            val now = System.currentTimeMillis()

            val referenceTime =
                if (lastResultAtMs > 0L) lastResultAtMs else lastScanStartedAtMs

            val age = if (referenceTime > 0L) now - referenceTime else 0L

            if (referenceTime > 0L && age > staleNoResultsMs) {
                Log.i(TAG, "No BLE scan results for ${age}ms -> restarting scan")

                updateBleNotification(
                    text = "Scan restarted automatically • checking again",
                    force = true
                )

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
            results.forEach { result ->
                runCatching { handleResult(result) }
                    .onFailure { t -> Log.w(TAG, "handleResult() failed", t) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (stopRequested.get()) return

            consecutiveScanFailures += 1
            Log.w(TAG, "BLE scan failed: $errorCode (consecutive=$consecutiveScanFailures)")

            val isPermanent = errorCode == android.bluetooth.le.ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED
            val giveUp = isPermanent || consecutiveScanFailures > MAX_CONSECUTIVE_SCAN_FAILURES

            if (giveUp) {
                updateBleNotification(
                    text = "BLE needs attention • scan failed ($errorCode)",
                    force = true
                )
                showResumeNotification(
                    context = this@BleAdvertScanService,
                    text = "BLE scan failed. Open app to retry."
                )
                stopSelfSafely("onScanFailed($errorCode), tries=$consecutiveScanFailures")
                return
            }

            val backoffIdx = (consecutiveScanFailures - 1)
                .coerceIn(0, SCAN_RETRY_BACKOFF_MS.size - 1)
            val backoff = SCAN_RETRY_BACKOFF_MS[backoffIdx]

            updateBleNotification(
                text = "BLE retry in ${backoff / 1000}s • last error $errorCode",
                force = true
            )

            mainHandler.postDelayed({
                if (stopRequested.get()) return@postDelayed
                restartScanBurstThenBalanced()
            }, backoff)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        if (!promoteToForegroundOrStop()) return

        updateBleNotification(
            text = "Starting BLE scanner…",
            force = true
        )

        if (!isUserUnlocked()) {
            updateBleNotification(
                text = "BLE waiting • unlock phone to scan",
                force = true
            )

            registerUserUnlockReceiver()
            return
        }

        ensureBluetoothReadyAndScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId

        when (intent?.action) {
            ACTION_START_FGS -> {
                if (!stopRequested.get()) {
                    if (!isUserUnlocked()) {
                        updateBleNotification(
                            text = "BLE waiting • unlock phone to scan",
                            force = true
                        )

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

        return START_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)

        mainHandler.removeCallbacks(watchdog)

        unregisterBtStateReceiver()
        unregisterUserUnlockReceiver()
        stopScanBestEffort()

        lastNotificationText = null

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun promoteToForegroundOrStop(): Boolean {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        return try {
            ServiceCompat.startForeground(
                this,
                notifId,
                buildNotification("Starting BLE scanner…"),
                types
            )

            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start BLE foreground service. SecurityException.", e)

            showResumeNotification(
                context = this,
                text = "Open app to resume BLE scanning. Permissions may be missing."
            )

            stopSelfSafely("FGS SecurityException")
            false
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "BLE foreground service start not allowed.", e)

            showResumeNotification(
                context = this,
                text = "Open app to resume BLE scanning."
            )

            stopSelfSafely("FGS not allowed")
            false
        } catch (t: Throwable) {
            Log.e(TAG, "BLE foreground service start failed.", t)

            showResumeNotification(
                context = this,
                text = "Open app to resume BLE scanning."
            )

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
            .setContentTitle("GoForIt is scanning for bonus stations")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateBleNotification(
        text: String,
        force: Boolean = false
    ) {
        if (!SHOW_BLE_NOTIFICATION_UPDATES) return

        val now = System.currentTimeMillis()

        if (!force && lastNotificationText == text) return
        if (!force && now - lastNotificationAtMs < NOTIF_MIN_UPDATE_INTERVAL_MS) return

        lastNotificationText = text
        lastNotificationAtMs = now

        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.notify(notifId, buildNotification(text))
        }.onFailure { t ->
            Log.w(TAG, "Failed to update BLE notification.", t)
        }
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
                    description = "Foreground service for BLE bonus station scanning"
                }
            )
        }

        if (nm.getNotificationChannel(resumeChannelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    resumeChannelId,
                    "BLE Resume",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when BLE scanning needs user action"
                }
            )
        }
    }

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

                updateBleNotification(
                    text = "Phone unlocked • starting BLE scan",
                    force = true
                )

                ensureBluetoothReadyAndScan()
            }
        }

        registerReceiver(userUnlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    }

    private fun unregisterUserUnlockReceiver() {
        val receiver = userUnlockReceiver ?: return

        userUnlockReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun ensureBluetoothReadyAndScan() {
        updateBleNotification(
            text = "Checking Bluetooth state…",
            force = false
        )

        val btMgr = getSystemService(BluetoothManager::class.java)
        val adapter = btMgr?.adapter

        if (adapter == null) {
            updateBleNotification(
                text = "BLE needs attention • Bluetooth unavailable",
                force = true
            )

            stopSelfSafely("Bluetooth adapter null")
            return
        }

        if (!adapter.isEnabled) {
            updateBleNotification(
                text = "BLE waiting • Bluetooth is off",
                force = true
            )

            registerBtStateReceiver()
            return
        }

        scanner = adapter.bluetoothLeScanner

        if (scanner == null) {
            updateBleNotification(
                text = "BLE waiting • scanner unavailable",
                force = true
            )

            registerBtStateReceiver()
            return
        }

        unregisterBtStateReceiver()

        restartScanBurstThenBalanced()

        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, watchdogPeriodMs)
    }

    private fun registerBtStateReceiver() {
        if (btStateReceiver != null) return

        btStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)

                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth STATE_ON -> attempting scan start")

                        updateBleNotification(
                            text = "Bluetooth is on • starting BLE scan",
                            force = true
                        )

                        ensureBluetoothReadyAndScan()
                    }

                    BluetoothAdapter.STATE_OFF -> {
                        updateBleNotification(
                            text = "BLE waiting • Bluetooth is off",
                            force = true
                        )
                    }

                    BluetoothAdapter.STATE_TURNING_ON -> {
                        updateBleNotification(
                            text = "BLE waiting • Bluetooth turning on",
                            force = true
                        )
                    }

                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        updateBleNotification(
                            text = "BLE waiting • Bluetooth turning off",
                            force = true
                        )
                    }
                }
            }
        }

        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun unregisterBtStateReceiver() {
        val receiver = btStateReceiver ?: return

        btStateReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun restartScanBurstThenBalanced() {
        if (stopRequested.get()) return

        stopScanBestEffort()

        lastResultAtMs = 0L

        updateBleNotification(
            text = "Scanning nearby stations • fast scan",
            force = true
        )

        if (!startScanIfPermitted(ScanSettings.SCAN_MODE_LOW_LATENCY)) return

        mainHandler.postDelayed({
            if (stopRequested.get()) return@postDelayed

            stopScanBestEffort()

            updateBleNotification(
                text = "Scanning nearby stations • normal scan",
                force = true
            )

            startScanIfPermitted(ScanSettings.SCAN_MODE_BALANCED)
        }, burstLowLatencyMs)
    }

    private fun buildScanFilters(): List<ScanFilter> {
        return listOf(
            ScanFilter.Builder()
                .setManufacturerData(companyId, null)
                .build()
        )
    }

    private fun startScanIfPermitted(mode: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                        PackageManager.PERMISSION_GRANTED

            if (!granted) {
                updateBleNotification(
                    text = "BLE needs attention • scan permission missing",
                    force = true
                )

                showResumeNotification(
                    context = this,
                    text = "Open app to grant BLE permissions."
                )

                stopSelfSafely("Missing BLUETOOTH_SCAN")
                return false
            }
        }

        val currentScanner = scanner

        if (currentScanner == null) {
            updateBleNotification(
                text = "BLE waiting • scanner unavailable",
                force = true
            )
            return false
        }

        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setReportDelay(0L)
            .build()

        val filters = buildScanFilters()

        return try {
            currentScanner.startScan(filters, settings, callback)

            lastScanStartedAtMs = System.currentTimeMillis()

            val modeText = scanModeLabel(mode)
            updateBleNotification(
                text = "Scanning nearby stations • $modeText",
                force = true
            )

            true
        } catch (se: SecurityException) {
            Log.w(TAG, "startScan() rejected by framework.", se)

            updateBleNotification(
                text = "BLE needs attention • permission rejected",
                force = true
            )

            showResumeNotification(
                context = this,
                text = "Open app to grant BLE permissions."
            )

            stopSelfSafely("startScan SecurityException")
            false
        } catch (t: Throwable) {
            Log.e(TAG, "startScan() failed.", t)

            consecutiveScanFailures += 1
            if (consecutiveScanFailures > MAX_CONSECUTIVE_SCAN_FAILURES) {
                updateBleNotification(
                    text = "BLE needs attention • scan could not start",
                    force = true
                )
                stopSelfSafely("startScan failure (max retries)")
                return false
            }

            val backoffIdx = (consecutiveScanFailures - 1)
                .coerceIn(0, SCAN_RETRY_BACKOFF_MS.size - 1)
            val backoff = SCAN_RETRY_BACKOFF_MS[backoffIdx]

            updateBleNotification(
                text = "BLE retry in ${backoff / 1000}s • startScan exception",
                force = true
            )
            mainHandler.postDelayed({
                if (stopRequested.get()) return@postDelayed
                restartScanBurstThenBalanced()
            }, backoff)

            false
        }
    }

    private fun scanModeLabel(mode: Int): String {
        return when (mode) {
            ScanSettings.SCAN_MODE_LOW_LATENCY -> "fast scan"
            ScanSettings.SCAN_MODE_BALANCED -> "normal scan"
            ScanSettings.SCAN_MODE_LOW_POWER -> "low-power scan"
            else -> "scan active"
        }
    }

    private fun stopScanBestEffort() {
        val currentScanner = scanner ?: return

        val hasScanPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED

        try {
            if (hasScanPermission) {
                currentScanner.stopScan(callback)
            } else {
                runCatching { currentScanner.stopScan(callback) }
                    .onFailure { t ->
                        Log.w(TAG, "stopScan() rejected; ignoring.", t)
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stopScan() failed during shutdown. Ignoring.", t)
        }
    }

    private fun handleResult(result: ScanResult) {
        if (stopRequested.get()) return

        val record = result.scanRecord ?: return
        val payload = record.manufacturerSpecificData?.get(companyId) ?: return

        if (payload.isEmpty()) return

        val msg = runCatching { payload.decodeToString() }
            .getOrElse {
                payload.joinToString(" ") { byte -> "%02X".format(byte) }
            }

        val now = System.currentTimeMillis()
        lastResultAtMs = now

        consecutiveScanFailures = 0

        val shouldToast = (lastSeenMsg != msg) || (now - lastToastAtMs > 1500L)

        lastSeenMsg = msg

        if (shouldToast) {
            lastToastAtMs = now
        }

        val nameOk =
            safeDeviceName(record)?.contains(deviceNameHint, ignoreCase = true) ?: true

        val notificationText =
            if (nameOk && msg == "bonus station") {
                serviceScope.launch {
                    val result = FirebaseServerApi.recordBonusVisitResult(stationId = msg)

                    result.onSuccess { ok ->
                        if (ok) {
                            Log.d("BONUS", "Bonus awarded ✅")

                            updateBleNotification(
                                text = "Bonus station detected • reward awarded",
                                force = true
                            )
                        } else {
                            Log.d("BONUS", "Bonus already claimed today")

                            updateBleNotification(
                                text = "Bonus station detected • already claimed today",
                                force = true
                            )
                        }
                    }.onFailure { e ->
                        Log.e("BONUS", "Bonus failed: ${e.message}", e)

                        updateBleNotification(
                            text = "Bonus station detected • reward check failed",
                            force = true
                        )
                    }
                }

                "Station detected • bonus station"
            } else {
                "Station signal received • $msg"
            }

        updateBleNotification(
            text = notificationText,
            force = nameOk && msg == "bonus station"
        )

        if (shouldToast && SHOW_BLE_TOASTS) {
            toast(notificationText)
        }
    }

    private fun hasBtConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun safeDeviceAddress(result: ScanResult): String {
        if (!hasBtConnectPermission()) return "unknown"

        return runCatching {
            result.device?.address ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun safeDeviceName(record: ScanRecord): String? {
        return runCatching { record.deviceName }.getOrNull()
    }

    private fun toast(text: String) {
        if (!SHOW_BLE_TOASTS) return

        mainHandler.post {
            runCatching {
                Toast.makeText(
                    applicationContext,
                    text,
                    if (text.length > 80) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun stopSelfSafely(reason: String) {
        if (!stopRequested.compareAndSet(false, true)) return

        Log.i(TAG, "Stopping service: $reason")

        mainHandler.removeCallbacks(watchdog)

        stopScanBestEffort()
        unregisterBtStateReceiver()
        unregisterUserUnlockReceiver()

        stopForegroundCompat()

        stopSelfResult(max(lastStartId, 1))
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "BleAdvertScanService"

        private const val SHOW_BLE_NOTIFICATION_UPDATES = true
        private const val SHOW_BLE_RESUME_NOTIFICATIONS = true
        private const val SHOW_BLE_TOASTS = false

        private const val NOTIF_MIN_UPDATE_INTERVAL_MS = 1_500L

        private val SCAN_RETRY_BACKOFF_MS = longArrayOf(
            2_000L, 5_000L, 15_000L, 30_000L, 60_000L
        )

        private const val MAX_CONSECUTIVE_SCAN_FAILURES = 5

        const val ACTION_START_FGS = "com.example.goforitGit.ACTION_START_BLE_FGS"
        const val ACTION_STOP = "com.example.goforitGit.ACTION_STOP_BLE_FGS"

        fun start(context: Context) {
            val intent = Intent(context, BleAdvertScanService::class.java).apply {
                action = ACTION_START_FGS
            }

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "BLE FGS start not allowed from boot/background.", e)

                showResumeNotification(
                    context = context,
                    text = "Open app to resume BLE scanning."
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "BLE FGS start failed due to app state.", e)

                showResumeNotification(
                    context = context,
                    text = "Open app to resume BLE scanning."
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "BLE FGS start failed due to permissions.", e)

                showResumeNotification(
                    context = context,
                    text = "Open app to resume BLE scanning. Permissions may be missing."
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleAdvertScanService::class.java))
        }

        private fun showResumeNotification(
            context: Context,
            text: String
        ) {
            if (!SHOW_BLE_RESUME_NOTIFICATIONS) return

            val nm = context.getSystemService(NotificationManager::class.java) ?: return

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

            val notificationBuilder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, "ble_resume")
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }

            nm.notify(
                52043,
                notificationBuilder
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("BLE scanning needs attention")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}