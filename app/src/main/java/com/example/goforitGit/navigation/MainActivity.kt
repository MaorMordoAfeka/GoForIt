package com.example.goforitGit.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.goforitGit.R
import com.example.goforitGit.core.service.BleAdvertScanService
import com.example.goforitGit.core.service.StepService
import com.example.goforitGit.core.util.FourHourBuckets.FourHourUploadScheduler
import com.example.goforitGit.feature.auth.ui.LoginActivity
import com.google.android.material.navigation.NavigationView
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.NavHostFragment


class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration

    // ---- Permissions (unchanged — stays in Activity) ----

    private fun hasActivityPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 29)
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        else true

    private val requestActivityRecognition =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || hasActivityPermission()) {
                startService(
                    Intent(this, StepService::class.java)
                        .setAction(StepService.ACTION_PERMS_UPDATED)
                )
                startCountingServiceSafely()
                ensureLocationPermission()
            } else {
                Toast.makeText(this, "Physical Activity permission denied", Toast.LENGTH_SHORT).show()
                ensureLocationPermission()
            }
        }

    private fun ensureActivityPermission() {
        if (Build.VERSION.SDK_INT >= 29 && !hasActivityPermission()) {
            requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            startCountingServiceSafely()
            ensureLocationPermission()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
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
                    Intent(this, StepService::class.java).setAction(StepService.ACTION_PERMS_UPDATED)
                )
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
            ensureBlePermission()
        }
    }

    private fun startCountingServiceSafely() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, StepService::class.java).setAction(StepService.ACTION_START_FGS)
        )
    }

    private fun startBleServiceSafely() {
        BleAdvertScanService.start(this)
    }

    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
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
            if (granted) startBleServiceSafely()
            else Toast.makeText(this, "Bluetooth scan permission denied", Toast.LENGTH_SHORT).show()
        }

    private fun ensureBlePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (hasBleScanPermission()) startBleServiceSafely()
            else requestBlePerms.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
        } else {
            if (hasLocationPermission()) startBleServiceSafely()
        }
    }

    private fun signOutAndGoToLogin() {
        lifecycleScope.launch {
            runCatching { stopService(Intent(this@MainActivity, StepService::class.java)) }
            runCatching { BleAdvertScanService.stop(this@MainActivity) }
            Firebase.auth.signOut()
            startActivity(
                Intent(this@MainActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }

    // ---- onCreate: drawer + navigation only ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.nav_main)

        // Scheduler
        FourHourUploadScheduler.scheduleNext(this)

        // Permissions chain
        if (Build.VERSION.SDK_INT >= 33) {
            requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureActivityPermission()
        }

        if (intent?.action == "ACTION_REQUEST_BLE_PERMISSIONS") {
            ensureBlePermission()
        }

        // ---- Navigation setup ----
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = findViewById(R.id.nav_view)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Tell AppBarConfiguration which destinations are "top-level"
        // (these show the hamburger icon instead of the back arrow)
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_steps, R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow),
            drawerLayout
        )

        setSupportActionBar(findViewById(R.id.toolbar))
        setupActionBarWithNavController(navController, appBarConfiguration)

        // This single line wires the drawer menu items to the nav graph!
        // The menu item IDs must match the fragment IDs in nav_graph.xml.
        navView.setupWithNavController(navController)

        // Handle sign-out separately (it's not a fragment destination)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_sign_out -> {
                    signOutAndGoToLogin()
                    true
                }
                else -> {
                    // Let NavController handle all fragment destinations
                    val handled = androidx.navigation.ui.NavigationUI
                        .onNavDestinationSelected(item, navController)
                    if (handled) drawerLayout.closeDrawers()
                    handled
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}