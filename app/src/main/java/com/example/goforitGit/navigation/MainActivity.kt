package com.example.goforitGit.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.view.GravityCompat
import androidx.core.app.ActivityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.goforitGit.R
import com.example.goforitGit.core.service.BleAdvertScanService
import com.example.goforitGit.core.service.StepService
import com.example.goforitGit.core.util.FourHourBuckets.FourHourUploadScheduler
import com.example.goforitGit.core.util.TrackingLifecycle.OnboardingPrefs
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingPermissions
import com.example.goforitGit.core.util.TrackingLifecycle.TrackingServiceManager
import com.example.goforitGit.feature.auth.ui.LoginActivity
import com.google.android.material.navigation.NavigationView
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch


/**
 * Hosts the navigation drawer and runs the runtime-permission chain.
 *
 * Critical correctness rules (learned the hard way from earlier bugs):
 *
 *  1. ONE DIALOG AT A TIME. Never call show() on a new AlertDialog
 *     synchronously inside an existing dialog's button callback.
 *     dialog.dismiss() only schedules teardown; the previous dialog is
 *     still in the view hierarchy. On slow OEM compositors (MIUI is the
 *     classic offender) the two dialogs render simultaneously for a few
 *     frames. We solve this with setOnDismissListener: schedule the next
 *     step from there, not from the click callback.
 *
 *  2. PERSIST CHAIN STATE ACROSS CONFIG CHANGES. baselineChainFinished
 *     and currentDialog identity need to survive the Activity recreation
 *     that happens when the user returns from a Settings page that did
 *     a theme/rotation change. Otherwise the chain re-runs and prompts
 *     duplicate.
 *
 *  3. RE-CHECK PERMISSIONS BEFORE EACH STEP. Even if a step was
 *     "asked", an unrelated lifecycle re-entry should never re-show
 *     it if the permission is already granted. Each shouldShowXStep()
 *     short-circuits on both: already-asked AND already-granted.
 *
 *  4. SERVICES START ONCE, AFTER BASELINE. TrackingServiceManager is
 *     only invoked from finishBaselineChain. Any earlier invocation
 *     risks the BLE service posting "Open app to resume BLE scanning.
 *     Permissions may be missing." while the user is still answering
 *     baseline rationale dialogs.
 *
 * Permission ordering:
 *   POST_NOTIFICATIONS
 *     -> ACTIVITY_RECOGNITION
 *       -> ACCESS_FINE/COARSE_LOCATION
 *         -> BLUETOOTH_SCAN / BLUETOOTH_CONNECT
 *           -> ACCESS_BACKGROUND_LOCATION (24/7 tier)
 *             -> Battery optimization exemption (24/7 tier)
 *               -> MIUI Autostart (24/7 tier, Xiaomi only)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration

    /**
     * Becomes true exactly once, when the baseline permission chain has
     * finished. Persisted across config changes via onSaveInstanceState.
     */
    private var baselineChainFinished = false

    /**
     * The dialog currently shown to the user. Tracked so we never stack
     * two dialogs on top of each other, and so a re-entry into
     * maybeStartTwentyFourSevenOnboarding while a dialog is visible
     * becomes a no-op instead of opening a duplicate.
     */
    private var currentDialog: AlertDialog? = null

    /**
     * Tracks whether onResume has already fired since onCreate. Some
     * dialog-advancement paths rely on onResume firing after the user
     * returns from Settings; this lets us distinguish "fresh launch
     * onResume" from "back-from-settings onResume".
     */
    private var hasResumedOnce = false

    // -------------------------------------------------------------------------
    // BASELINE PERMISSIONS
    // -------------------------------------------------------------------------

    // ---- Notifications ----

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        else true

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            OnboardingPrefs.markAskedNotifications(this)
            ensureActivityPermission()
        }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission()) {
            ensureActivityPermission()
            return
        }

        if (shouldShowRationaleForNotifications()) {
            showNotificationsRationale()
        } else {
            requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun shouldShowRationaleForNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val firstTime = !OnboardingPrefs.hasAskedNotifications(this)
        val systemSaysExplain = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.POST_NOTIFICATIONS
        )
        return firstTime || systemSaysExplain
    }

    private fun showNotificationsRationale() {
        showDialog(
            title = "Allow GoForIt to show notifications",
            message =
                "GoForIt needs to show a small ongoing notification so Android keeps " +
                        "step counting alive in the background.\n\n" +
                        "Without this, your steps would stop being counted whenever you " +
                        "leave the app.\n\n" +
                        "Tap Continue, then choose \"Allow\" on the system dialog.",
            onContinueRunAfterDismiss = {
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onNotNowRunAfterDismiss = {
                OnboardingPrefs.markAskedNotifications(this)
                ensureActivityPermission()
            }
        )
    }

    // ---- Activity Recognition ----

    private fun hasActivityPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 29)
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        else true

    private val requestActivityRecognition =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            OnboardingPrefs.markAskedActivityRecognition(this)
            if (!granted && !hasActivityPermission()) {
                Toast.makeText(this, "Physical Activity permission denied", Toast.LENGTH_SHORT)
                    .show()
            }
            ensureLocationPermission()
        }

    private fun ensureActivityPermission() {
        if (Build.VERSION.SDK_INT < 29 || hasActivityPermission()) {
            ensureLocationPermission()
            return
        }

        if (shouldShowRationaleForActivityRecognition()) {
            showActivityRecognitionRationale()
        } else {
            requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun shouldShowRationaleForActivityRecognition(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val firstTime = !OnboardingPrefs.hasAskedActivityRecognition(this)
        val systemSaysExplain = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.ACTIVITY_RECOGNITION
        )
        return firstTime || systemSaysExplain
    }

    private fun showActivityRecognitionRationale() {
        showDialog(
            title = "Allow GoForIt to detect your activity",
            message =
                "To count steps and detect whether you're walking, running, cycling, " +
                        "or stationary, GoForIt needs access to physical-activity data " +
                        "from your phone's motion sensors.\n\n" +
                        "Tap Continue, then choose \"Allow\" on the system dialog.",
            onContinueRunAfterDismiss = {
                requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            },
            onNotNowRunAfterDismiss = {
                OnboardingPrefs.markAskedActivityRecognition(this)
                ensureLocationPermission()
            }
        )
    }

    // ---- Location (FINE/COARSE) ----

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) ==
                PackageManager.PERMISSION_GRANTED

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            OnboardingPrefs.markAskedLocation(this)
            ensureBlePermission()
        }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            ensureBlePermission()
            return
        }

        if (shouldShowRationaleForLocation()) {
            showLocationRationale()
        } else {
            requestLocation.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun shouldShowRationaleForLocation(): Boolean {
        val firstTime = !OnboardingPrefs.hasAskedLocation(this)
        val systemSaysExplain =
            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.ACCESS_COARSE_LOCATION
                    )
        return firstTime || systemSaysExplain
    }

    private fun showLocationRationale() {
        showDialog(
            title = "Allow GoForIt to access location",
            message =
                "GoForIt uses your location while the app is open to:\n\n" +
                        "  • show your current speed\n" +
                        "  • detect when you're inside the college bonus zone\n" +
                        "  • discover nearby Bluetooth bonus stations\n\n" +
                        "Tap Continue, then choose \"While using the app\" or \"Precise\".",
            onContinueRunAfterDismiss = {
                requestLocation.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onNotNowRunAfterDismiss = {
                OnboardingPrefs.markAskedLocation(this)
                ensureBlePermission()
            }
        )
    }

    // ---- Bluetooth ----

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
            OnboardingPrefs.markAskedBle(this)
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                grants[Manifest.permission.BLUETOOTH_SCAN] == true || hasBleScanPermission()
            } else {
                hasLocationPermission()
            }
            if (!granted) {
                Toast.makeText(this, "Bluetooth scan permission denied", Toast.LENGTH_SHORT).show()
            }
            finishBaselineChain()
        }

    private fun ensureBlePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            finishBaselineChain()
            return
        }

        if (hasBleScanPermission()) {
            finishBaselineChain()
            return
        }

        if (shouldShowRationaleForBle()) {
            showBleRationale()
        } else {
            requestBlePerms.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
        }
    }

    private fun shouldShowRationaleForBle(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val firstTime = !OnboardingPrefs.hasAskedBle(this)
        val systemSaysExplain =
            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.BLUETOOTH_CONNECT
                    )
        return firstTime || systemSaysExplain
    }

    private fun showBleRationale() {
        showDialog(
            title = "Allow GoForIt to scan for bonus stations",
            message =
                "Bonus stations around campus broadcast a short Bluetooth signal that " +
                        "GoForIt uses to award extra points when you walk past them.\n\n" +
                        "We only scan for these stations — we don't read messages, files, " +
                        "or connect to your other Bluetooth devices.\n\n" +
                        "Tap Continue, then choose \"Allow\" on the system dialog.",
            onContinueRunAfterDismiss = {
                requestBlePerms.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            },
            onNotNowRunAfterDismiss = {
                OnboardingPrefs.markAskedBle(this)
                finishBaselineChain()
            }
        )
    }

    /**
     * Called once the baseline chain is resolved. Idempotent — calling
     * it more than once is a no-op past the first time.
     */
    private fun finishBaselineChain() {
        if (!baselineChainFinished) {
            baselineChainFinished = true
            TrackingServiceManager.startTracking(this)
        }
        maybeStartTwentyFourSevenOnboarding()
    }

    // -------------------------------------------------------------------------
    // 24/7 TIER
    // -------------------------------------------------------------------------

    /**
     * Re-entrant. If a dialog is already visible (currentDialog != null),
     * we no-op — the next step will be triggered when that dialog's
     * onDismissListener fires.
     */
    private fun maybeStartTwentyFourSevenOnboarding() {
        if (currentDialog != null) return

        when {
            shouldShowBackgroundLocationStep() -> showBackgroundLocationRationale()
            shouldShowBatteryExemptionStep() -> showBatteryExemptionRationale()
            shouldShowMiuiAutostartStep() -> showMiuiAutostartRationale()
            else -> { /* Done. */
            }
        }
    }

    // ---- Step 1: Background location ----

    /**
     * Two ways this step is suppressed:
     *  - Already asked once on this install (asked flag persists across
     *    Activity recreation, since it lives in SharedPreferences).
     *  - Already granted (covered by shouldAskForBackgroundLocation).
     */
    private fun shouldShowBackgroundLocationStep(): Boolean {
        if (TrackingPermissions.hasBackgroundLocationPermission(this)) return false
        if (OnboardingPrefs.hasAskedBackgroundLocation(this)) return false
        return TrackingPermissions.shouldAskForBackgroundLocation(this)
    }

    private val requestBackgroundLocationInline =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(
                    this,
                    "Background location granted — bonus zone tracking active 24/7.",
                    Toast.LENGTH_SHORT
                ).show()
                TrackingServiceManager.notifyPermissionsUpdated(this)
            }
            OnboardingPrefs.markAskedBackgroundLocation(this)
            maybeStartTwentyFourSevenOnboarding()
        }

    private fun showBackgroundLocationRationale() {
        showDialog(
            title = "Always-on bonus zone tracking",
            message =
                "For accurate bonus-zone tracking, GoForIt needs location access even " +
                        "when the app isn't open.\n\n" +
                        "Tap Continue. Android will open this app's settings page.\n\n" +
                        "From there, please:\n" +
                        "  1. Tap \"Permissions\"\n" +
                        "  2. Tap \"Location\"\n" +
                        "  3. Choose \"Allow all the time\"\n\n" +
                        "Then press the back button to return here.\n\n" +
                        "We only use this to credit you for steps inside the college bonus zone.",
            onContinueRunAfterDismiss = {
                // Mark as asked BEFORE launching settings, so that if the
                // user backgrounds the app and a config change recreates
                // the Activity, we won't re-prompt on the way back.
                OnboardingPrefs.markAskedBackgroundLocation(this)
                requestBackgroundLocation()
            },
            onNotNowRunAfterDismiss = {
                OnboardingPrefs.markAskedBackgroundLocation(this)
                maybeStartTwentyFourSevenOnboarding()
            }
        )
    }

    private fun requestBackgroundLocation() {
        if (TrackingPermissions.canRequestBackgroundLocationInline()) {
            requestBackgroundLocationInline.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }

        val launched = TrackingPermissions.launchBackgroundLocationSettings(this)
        if (!launched) {
            Toast.makeText(
                this,
                "Could not open location settings — please change it manually in Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
        // onResume will continue the chain when the user returns.
    }

    // ---- Step 2: Battery optimization exemption ----

    private fun shouldShowBatteryExemptionStep(): Boolean {
        if (TrackingPermissions.isIgnoringBatteryOptimizations(this)) return false
        if (OnboardingPrefs.hasAskedBatteryExemption(this)) return false
        return true
    }

    private fun showBatteryExemptionRationale() {
        showDialog(
            title = "Keep step counting alive in the background",
            message =
                "Android may pause step counting after long idle periods to save battery.\n\n" +
                        "To keep GoForIt tracking your steps 24/7 and recover automatically " +
                        "within ~15 minutes if anything goes wrong, please disable battery " +
                        "optimization for this app.\n\n" +
                        "Tap Continue and choose \"Allow\" on the system dialog.",
            onContinueRunAfterDismiss = {
                OnboardingPrefs.markAskedBatteryExemption(this)
                val launched = TrackingPermissions.launchBatteryOptimizationExemptionRequest(this)
                if (!launched) {
                    Toast.makeText(
                        this,
                        "Could not open battery settings — please disable battery optimization manually.",
                        Toast.LENGTH_LONG
                    ).show()
                    maybeStartTwentyFourSevenOnboarding()
                }
            },
            onNotNowRunAfterDismiss = {
                OnboardingPrefs.markAskedBatteryExemption(this)
                maybeStartTwentyFourSevenOnboarding()
            }
        )
    }

    // ---- Step 3: MIUI Autostart ----

    private fun shouldShowMiuiAutostartStep(): Boolean {
        if (OnboardingPrefs.hasAskedMiuiAutostart(this)) return false
        return TrackingPermissions.isMiuiLikeDevice()
    }

    private fun showMiuiAutostartRationale() {
        showDialog(
            title = "One more step on Xiaomi devices",
            message =
                "MIUI / HyperOS prevents apps from restarting after reboot unless you " +
                        "enable \"Autostart\" for them.\n\n" +
                        "Please tap Continue, find GoForIt in the list that opens, " +
                        "and turn its Autostart switch ON.\n\n" +
                        "Without this, the step counter won't restart automatically after you " +
                        "reboot your phone.",
            onContinueRunAfterDismiss = {
                OnboardingPrefs.markAskedMiuiAutostart(this)
                TrackingPermissions.launchMiuiAutostartSettings(this)
            },
            onNotNowRunAfterDismiss = {
                OnboardingPrefs.markAskedMiuiAutostart(this)
                maybeStartTwentyFourSevenOnboarding()
            }
        )
    }

    // -------------------------------------------------------------------------
    // DIALOG INFRASTRUCTURE
    //
    // Single chokepoint for every rationale dialog. Guarantees:
    //  - we never have two dialogs visible at once (currentDialog guard)
    //  - the next step always runs AFTER the current dialog has fully
    //    dismissed, never inside its click callback (onDismissListener)
    // -------------------------------------------------------------------------

    /**
     * Show a rationale dialog. The provided lambdas run *after* dismissal
     * completes, not during the button click — this is what eliminates
     * the dialog-overlap glitch.
     */
    private fun showDialog(
        title: String,
        message: String,
        onContinueRunAfterDismiss: () -> Unit,
        onNotNowRunAfterDismiss: () -> Unit
    ) {
        if (currentDialog?.isShowing == true) return

        var pendingAction: (() -> Unit)? = null

        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (20 * density).toInt()
        val padBottom = (16 * density).toInt()

        // Custom title view: left-aligned LTR title.
        val titleView = android.widget.TextView(this).apply {
            text = title
            textDirection = android.view.View.TEXT_DIRECTION_LTR
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            setPadding(padH, padV, padH, padV / 2)
        }

        // Message view.
        val messageView = android.widget.TextView(this).apply {
            text = message
            textDirection = android.view.View.TEXT_DIRECTION_LTR
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            gravity = android.view.Gravity.START
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setPadding(padH, 0, padH, (12 * density).toInt())
        }

        val continueButton = android.widget.Button(this).apply {
            text = "Continue"
            textDirection = android.view.View.TEXT_DIRECTION_LTR
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            setOnClickListener {
                pendingAction = onContinueRunAfterDismiss
                currentDialog?.dismiss()
            }
        }

        val notNowButton = android.widget.Button(this).apply {
            text = "Not now"
            textDirection = android.view.View.TEXT_DIRECTION_LTR
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            setOnClickListener {
                pendingAction = onNotNowRunAfterDismiss
                currentDialog?.dismiss()
            }
        }

        val spacer = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            setPadding(padH, 0, padH, padBottom)

            addView(
                continueButton,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(spacer)

            addView(
                notNowButton,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val contentView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR

            addView(
                messageView,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                buttonRow,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setView(contentView)
            .setOnDismissListener {
                currentDialog = null
                pendingAction?.invoke()
            }
            .setCancelable(false)
            .create()

        currentDialog = dialog
        dialog.show()
    }

    // -------------------------------------------------------------------------

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

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.nav_main)

        FourHourUploadScheduler.scheduleNext(this)

        // Restore chain state across config changes (theme switch from a
        // dark-mode Settings page, screen rotation, etc.). Without this,
        // returning from Settings re-runs the chain and re-prompts.
        if (savedInstanceState != null) {
            baselineChainFinished =
                savedInstanceState.getBoolean(KEY_BASELINE_CHAIN_FINISHED, false)
        }

        // Kick off the permission chain only if it hasn't already
        // finished (which would be the case after recreation).
        if (!baselineChainFinished) {
            ensureNotificationPermission()
        }

        if (intent?.action == "ACTION_REQUEST_BLE_PERMISSIONS") {
            ensureBlePermission()
        }

        // ---- Navigation setup ----
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)

        if (intent.getBooleanExtra(DrawerNavigator.EXTRA_OPEN_DRAWER, false)) {
            drawerLayout.post {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        val navView: NavigationView = findViewById(R.id.nav_view)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_steps,
                R.id.nav_statistics,
                R.id.nav_map,
                R.id.nav_leaderboard,
                R.id.nav_profile
            ),
            drawerLayout
        )

        setSupportActionBar(findViewById(R.id.toolbar))
        setupActionBarWithNavController(navController, appBarConfiguration)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)

        toolbar.post {
            (toolbar.navigationIcon as? DrawerArrowDrawable)?.color =
                getColor(R.color.brand_purple_deep)
        }

        navView.setupWithNavController(navController)

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_sign_out -> {
                    signOutAndGoToLogin()
                    true
                }

                else -> {
                    val handled = androidx.navigation.ui.NavigationUI
                        .onNavDestinationSelected(item, navController)
                    if (handled) drawerLayout.closeDrawers()
                    handled
                }
            }
        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_BASELINE_CHAIN_FINISHED, baselineChainFinished)
    }

    override fun onResume() {
        super.onResume()

        // The first onResume after onCreate is the natural continuation
        // of the chain — onCreate already kicked it off via
        // ensureNotificationPermission. We don't want to also call
        // maybeStartTwentyFourSevenOnboarding here, or we'd potentially
        // race with a dialog already in the process of being shown.
        if (!hasResumedOnce) {
            hasResumedOnce = true
            return
        }

        // Subsequent onResume calls are "the user came back from
        // somewhere" — most commonly a Settings page after a 24/7-tier
        // step. Re-advance the chain.
        if (baselineChainFinished) {
            TrackingServiceManager.notifyPermissionsUpdated(this)
            maybeStartTwentyFourSevenOnboarding()
        }
    }

    override fun onPause() {
        // If the Activity is being paused while a dialog is showing
        // (e.g. user pulled the notification shade), dismiss it cleanly
        // rather than leaking the window. The chain will re-pick-up
        // from where it left off in onResume because the asked flags
        // are durable.
        currentDialog?.takeIf { it.isShowing }?.dismiss()
        currentDialog = null
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.getBooleanExtra(DrawerNavigator.EXTRA_OPEN_DRAWER, false)) {
            findViewById<DrawerLayout>(R.id.drawer_layout).post {
                findViewById<DrawerLayout>(R.id.drawer_layout)
                    .openDrawer(GravityCompat.START)
            }
        }
    }

    companion object {
        private const val KEY_BASELINE_CHAIN_FINISHED = "baseline_chain_finished"
    }
}