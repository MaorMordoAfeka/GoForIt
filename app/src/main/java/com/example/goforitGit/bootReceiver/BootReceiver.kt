package com.example.goforitGit.bootReceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.goforitGit.bluetooth_bonus_stations_module.BleAdvertScanService
import com.example.goforitGit.count_step_module.StepService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return

        val isBoot =
            a == Intent.ACTION_BOOT_COMPLETED ||
                    a == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                    a == Intent.ACTION_MY_PACKAGE_REPLACED ||
                    a == Intent.ACTION_USER_UNLOCKED

        if (!isBoot) return

        // Start Steps service
        val stepsIntent = Intent(context, StepService::class.java).apply {
            action = StepService.ACTION_START_FGS
        }
        runCatching { ContextCompat.startForegroundService(context, stepsIntent) }

        // Start BLE service using its own safe start() (with exception handling)
        BleAdvertScanService.start(context)
    }
}
