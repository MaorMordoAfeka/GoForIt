package com.example.goforitGit.feature.qa.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.goforitGit.R
import com.example.goforitGit.core.service.BleAdvertScanService
import com.example.goforitGit.feature.leaderboard.ui.LeaderboardActivity
import com.example.goforitGit.feature.qa.QaAccess
import com.example.goforitGit.feature.steps.viewmodel.StepViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Temporary in-app acceptance-test console.
 *
 * Access is checked against BOTH the dedicated QA email and UID. The home-screen
 * entry is hidden for every other account, and this Activity performs the same
 * check again so it cannot be opened by an explicit Intent from an unauthorized
 * signed-in account.
 */
class QaActivity : AppCompatActivity() {

    private val stepViewModel: StepViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var etBleRunId: TextInputEditText
    private lateinit var etLeaderboardDayKey: TextInputEditText

    private lateinit var btnGenerateBleRunId: MaterialButton
    private lateinit var btnStartBleQaRun: MaterialButton
    private lateinit var btnRunLeaderboardQa: MaterialButton
    private lateinit var btnRunEmptyLeaderboardQa: MaterialButton
    private lateinit var btnRecordPreCrash: MaterialButton
    private lateinit var btnControlledCrash: MaterialButton
    private lateinit var btnEvaluateRecovery: MaterialButton

    private lateinit var tvBleStatus: TextView
    private lateinit var tvBleSummary: TextView
    private lateinit var tvLeaderboardStatus: TextView
    private lateinit var tvLeaderboardSummary: TextView
    private lateinit var tvCurrentTotalSteps: TextView
    private lateinit var tvCurrentTodaySteps: TextView
    private lateinit var tvRecordedBeforeCrash: TextView
    private lateinit var tvRecoveryStatus: TextView
    private lateinit var tvRecoverySummary: TextView

    private var currentTotalSteps = 0
    private var currentTodaySteps = 0
    private var hasReceivedStepValue = false

    private var activeBleRunId: String? = null
    private var activeBleStartedAtElapsedMs = 0L
    private var bleTimeoutRunnable: Runnable? = null

    private val leaderboardLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleLeaderboardResult(result.resultCode, result.data)
        }

    private val bleResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BleAdvertScanService.ACTION_QA_RUN_RESULT) return

            val runId = intent.getStringExtra(BleAdvertScanService.EXTRA_QA_RESULT_RUN_ID)
                .orEmpty()
            val expected = activeBleRunId ?: return
            if (runId != expected) return

            val success = intent.getBooleanExtra(
                BleAdvertScanService.EXTRA_QA_RESULT_SUCCESS,
                false
            )
            val duplicate = intent.getBooleanExtra(
                BleAdvertScanService.EXTRA_QA_RESULT_DUPLICATE,
                false
            )
            val elapsedMs = intent.getLongExtra(
                BleAdvertScanService.EXTRA_QA_RESULT_ELAPSED_MS,
                SystemClock.elapsedRealtime() - activeBleStartedAtElapsedMs
            )
            val message = intent.getStringExtra(
                BleAdvertScanService.EXTRA_QA_RESULT_MESSAGE
            ).orEmpty()

            finishBleRun(
                runId = runId,
                success = success && !duplicate && elapsedMs <= BLE_TIMEOUT_MS,
                elapsedMs = elapsedMs,
                details = if (duplicate) {
                    "Run ID already existed in Firebase. Use a new ID."
                } else {
                    message.ifBlank { if (success) "Firebase recorded the QA visit." else "QA run failed." }
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!QaAccess.isAuthorized()) {
            Toast.makeText(this, "QA access is not authorized for this account.", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        supportActionBar?.hide()
        setContentView(R.layout.feature_qa_activity)

        bindViews()
        setupActions()
        observeSteps()
        restoreUiState()
    }

    override fun onStart() {
        super.onStart()

        if (!QaAccess.isAuthorized()) {
            finish()
            return
        }

        ContextCompat.registerReceiver(
            this,
            bleResultReceiver,
            IntentFilter(BleAdvertScanService.ACTION_QA_RUN_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(bleResultReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        bleTimeoutRunnable?.let(handler::removeCallbacks)
        super.onDestroy()
    }

    private fun bindViews() {
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        etBleRunId = findViewById(R.id.etBleRunId)
        etLeaderboardDayKey = findViewById(R.id.etLeaderboardDayKey)

        btnGenerateBleRunId = findViewById(R.id.btnGenerateBleRunId)
        btnStartBleQaRun = findViewById(R.id.btnStartBleQaRun)
        btnRunLeaderboardQa = findViewById(R.id.btnRunLeaderboardQa)
        btnRunEmptyLeaderboardQa = findViewById(R.id.btnRunEmptyLeaderboardQa)
        btnRecordPreCrash = findViewById(R.id.btnRecordPreCrash)
        btnControlledCrash = findViewById(R.id.btnControlledCrash)
        btnEvaluateRecovery = findViewById(R.id.btnEvaluateRecovery)

        tvBleStatus = findViewById(R.id.tvBleStatus)
        tvBleSummary = findViewById(R.id.tvBleSummary)
        tvLeaderboardStatus = findViewById(R.id.tvLeaderboardStatus)
        tvLeaderboardSummary = findViewById(R.id.tvLeaderboardSummary)
        tvCurrentTotalSteps = findViewById(R.id.tvCurrentTotalSteps)
        tvCurrentTodaySteps = findViewById(R.id.tvCurrentTodaySteps)
        tvRecordedBeforeCrash = findViewById(R.id.tvRecordedBeforeCrash)
        tvRecoveryStatus = findViewById(R.id.tvRecoveryStatus)
        tvRecoverySummary = findViewById(R.id.tvRecoverySummary)
    }

    private fun setupActions() {
        btnGenerateBleRunId.setOnClickListener {
            etBleRunId.setText(generateNextBleRunId())
            etBleRunId.setSelection(etBleRunId.text?.length ?: 0)
        }

        btnStartBleQaRun.setOnClickListener { startBleRun() }

        findViewById<MaterialButton>(R.id.btnResetBleResults).setOnClickListener {
            confirmReset("Reset BLE results?") {
                prefs.edit()
                    .remove(KEY_BLE_TOTAL)
                    .remove(KEY_BLE_PASSED)
                    .remove(KEY_BLE_IDS)
                    .remove(KEY_BLE_SEQUENCE)
                    .apply()
                tvBleStatus.text = "BLE results were reset."
                updateBleSummary()
                etBleRunId.setText(generateNextBleRunId())
            }
        }

        btnRunLeaderboardQa.setOnClickListener {
            startLeaderboardRun(expectEmpty = false)
        }

        btnRunEmptyLeaderboardQa.setOnClickListener {
            startLeaderboardRun(expectEmpty = true)
        }

        findViewById<MaterialButton>(R.id.btnResetLeaderboardResults).setOnClickListener {
            confirmReset("Reset leaderboard results?") {
                prefs.edit()
                    .remove(KEY_LEADERBOARD_TOTAL)
                    .remove(KEY_LEADERBOARD_PASSED)
                    .remove(KEY_LEADERBOARD_IDS)
                    .apply()
                tvLeaderboardStatus.text = "Leaderboard results were reset."
                updateLeaderboardSummary()
            }
        }

        btnRecordPreCrash.setOnClickListener { recordPreCrashCount() }

        findViewById<MaterialButton>(R.id.btnCopyForceStop).setOnClickListener {
            val command = "adb shell am force-stop $packageName"
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("GoForIt force-stop command", command))
            Toast.makeText(this, "ADB command copied.", Toast.LENGTH_SHORT).show()
        }

        btnControlledCrash.setOnClickListener { confirmControlledCrash() }
        btnEvaluateRecovery.setOnClickListener { evaluateRecovery() }

        findViewById<MaterialButton>(R.id.btnResetRecoveryResults).setOnClickListener {
            confirmReset("Reset recovery results?") {
                prefs.edit()
                    .remove(KEY_RECOVERY_TOTAL)
                    .remove(KEY_RECOVERY_PASSED)
                    .remove(KEY_PRE_CRASH_TOTAL)
                    .remove(KEY_PRE_CRASH_TODAY)
                    .remove(KEY_PRE_CRASH_PENDING)
                    .remove(KEY_PRE_CRASH_RECORDED_AT)
                    .apply()
                tvRecoveryStatus.text = "Recovery results were reset."
                updateRecoverySummary()
                updateRecordedPreCrashLabel()
            }
        }
    }

    private fun observeSteps() {
        stepViewModel.steps.observe(this) { value ->
            currentTotalSteps = value
            hasReceivedStepValue = true
            tvCurrentTotalSteps.text = "Current total steps: ${formatCount(value)}"
            updateRecoveryButtons()
        }

        stepViewModel.stepsToday.observe(this) { value ->
            currentTodaySteps = value
            tvCurrentTodaySteps.text = "Current steps today: ${formatCount(value)}"
        }
    }

    private fun restoreUiState() {
        etLeaderboardDayKey.setText("2099-12-31")
        etBleRunId.setText(generateNextBleRunId())

        updateBleSummary()
        updateLeaderboardSummary()
        updateRecoverySummary()
        updateRecordedPreCrashLabel()
        updateRecoveryButtons()
    }

    // ---------------------------------------------------------------------
    // Test 1 — BLE reliability
    // ---------------------------------------------------------------------

    private fun startBleRun() {
        if (activeBleRunId != null) {
            toast("A BLE QA run is already active.")
            return
        }

        val runId = etBleRunId.text?.toString()?.trim().orEmpty()
        if (!RUN_ID_REGEX.matches(runId)) {
            toast("Use 3–80 letters, numbers, underscores, or hyphens.")
            return
        }

        val completedIds = prefs.getStringSet(KEY_BLE_IDS, emptySet()).orEmpty()
        if (runId in completedIds) {
            toast("This run ID was already recorded locally. Generate a new ID.")
            return
        }

        activeBleRunId = runId
        activeBleStartedAtElapsedMs = SystemClock.elapsedRealtime()
        setBleRunning(true)
        tvBleStatus.text = "Run $runId armed. Stay exactly 2 meters away for up to 10 seconds…"

        try {
            BleAdvertScanService.startQaRun(this, runId)
        } catch (e: IllegalArgumentException) {
            activeBleRunId = null
            setBleRunning(false)
            toast(e.message ?: "Invalid BLE QA run ID.")
            return
        }

        val timeout = Runnable {
            if (activeBleRunId == runId) {
                finishBleRun(
                    runId = runId,
                    success = false,
                    elapsedMs = BLE_TIMEOUT_MS,
                    details = "No successful Firebase confirmation arrived within 10 seconds."
                )
            }
        }

        bleTimeoutRunnable = timeout
        handler.postDelayed(timeout, BLE_TIMEOUT_MS)
    }

    private fun finishBleRun(
        runId: String,
        success: Boolean,
        elapsedMs: Long,
        details: String
    ) {
        bleTimeoutRunnable?.let(handler::removeCallbacks)
        bleTimeoutRunnable = null

        val currentIds = prefs.getStringSet(KEY_BLE_IDS, emptySet()).orEmpty().toMutableSet()
        if (!currentIds.add(runId)) {
            activeBleRunId = null
            setBleRunning(false)
            return
        }

        val total = prefs.getInt(KEY_BLE_TOTAL, 0) + 1
        val passed = prefs.getInt(KEY_BLE_PASSED, 0) + if (success) 1 else 0

        prefs.edit()
            .putInt(KEY_BLE_TOTAL, total)
            .putInt(KEY_BLE_PASSED, passed)
            .putStringSet(KEY_BLE_IDS, currentIds)
            .apply()

        tvBleStatus.text = buildString {
            append(if (success) "PASS" else "FAIL")
            append(" — ")
            append(runId)
            append(" — ")
            append(String.format(Locale.US, "%.2f s", elapsedMs / 1000.0))
            append("\n")
            append(details)
        }

        activeBleRunId = null
        setBleRunning(false)
        updateBleSummary()
        etBleRunId.setText(generateNextBleRunId())
    }

    private fun setBleRunning(running: Boolean) {
        btnStartBleQaRun.isEnabled = !running
        btnGenerateBleRunId.isEnabled = !running
        etBleRunId.isEnabled = !running
    }

    private fun generateNextBleRunId(): String {
        val next = prefs.getInt(KEY_BLE_SEQUENCE, 0) + 1
        prefs.edit().putInt(KEY_BLE_SEQUENCE, next).apply()

        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "ble_${date}_${next.toString().padStart(2, '0')}"
    }

    private fun updateBleSummary() {
        val total = prefs.getInt(KEY_BLE_TOTAL, 0)
        val passed = prefs.getInt(KEY_BLE_PASSED, 0)
        val rate = if (total == 0) 0.0 else passed * 100.0 / total
        val final = when {
            total < 30 -> "In progress"
            passed >= 29 -> "FINAL PASS"
            else -> "FINAL FAIL"
        }

        tvBleSummary.text = String.format(
            Locale.US,
            "Completed: %d/30 • Passed: %d • Rate: %.2f%% • %s",
            total,
            passed,
            rate,
            final
        )
    }

    // ---------------------------------------------------------------------
    // Test 2 — leaderboard performance
    // ---------------------------------------------------------------------

    private fun startLeaderboardRun(expectEmpty: Boolean) {
        val dayKey = etLeaderboardDayKey.text?.toString()?.trim().orEmpty()
        if (!DAY_KEY_REGEX.matches(dayKey)) {
            toast("Enter the day as YYYY-MM-DD.")
            return
        }

        val runNumber = if (expectEmpty) {
            "empty_${System.currentTimeMillis()}"
        } else {
            val next = prefs.getInt(KEY_LEADERBOARD_TOTAL, 0) + 1
            "leaderboard_${LocalDate.now()}_${next.toString().padStart(2, '0')}"
        }

        val intent = LeaderboardActivity.createQaIntent(
            context = this,
            dayKey = dayKey,
            expectEmpty = expectEmpty,
            qaRunId = runNumber,
            startedAtElapsedMs = SystemClock.elapsedRealtime()
        )

        leaderboardLauncher.launch(intent)
    }

    private fun handleLeaderboardResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            tvLeaderboardStatus.text = "Leaderboard QA run was cancelled before a result was recorded."
            return
        }

        val runId = data.getStringExtra(LeaderboardActivity.QA_RESULT_RUN_ID).orEmpty()
        val success = data.getBooleanExtra(LeaderboardActivity.QA_RESULT_SUCCESS, false)
        val expectEmpty = data.getBooleanExtra(LeaderboardActivity.QA_RESULT_EXPECT_EMPTY, false)
        val elapsedMs = data.getLongExtra(LeaderboardActivity.QA_RESULT_ELAPSED_MS, -1L)
        val entryCount = data.getIntExtra(LeaderboardActivity.QA_RESULT_ENTRY_COUNT, -1)
        val podiumCount = data.getIntExtra(LeaderboardActivity.QA_RESULT_PODIUM_COUNT, -1)
        val listCount = data.getIntExtra(LeaderboardActivity.QA_RESULT_LIST_COUNT, -1)
        val emptyVisible = data.getBooleanExtra(
            LeaderboardActivity.QA_RESULT_EMPTY_VISIBLE,
            false
        )
        val error = data.getStringExtra(LeaderboardActivity.QA_RESULT_ERROR).orEmpty()

        tvLeaderboardStatus.text = buildString {
            append(if (success) "PASS" else "FAIL")
            append(" — ")
            append(if (expectEmpty) "Empty-state check" else runId)
            if (elapsedMs >= 0) {
                append(" — ")
                append(String.format(Locale.US, "%.2f s", elapsedMs / 1000.0))
            }
            append("\nEntries: $entryCount • Podium: $podiumCount • List: $listCount")
            if (expectEmpty) append(" • Empty message: $emptyVisible")
            if (error.isNotBlank()) append("\n$error")
        }

        if (expectEmpty) return

        val ids = prefs.getStringSet(KEY_LEADERBOARD_IDS, emptySet()).orEmpty().toMutableSet()
        if (!ids.add(runId)) return

        val total = prefs.getInt(KEY_LEADERBOARD_TOTAL, 0) + 1
        val passed = prefs.getInt(KEY_LEADERBOARD_PASSED, 0) + if (success) 1 else 0

        prefs.edit()
            .putInt(KEY_LEADERBOARD_TOTAL, total)
            .putInt(KEY_LEADERBOARD_PASSED, passed)
            .putStringSet(KEY_LEADERBOARD_IDS, ids)
            .apply()

        updateLeaderboardSummary()
    }

    private fun updateLeaderboardSummary() {
        val total = prefs.getInt(KEY_LEADERBOARD_TOTAL, 0)
        val passed = prefs.getInt(KEY_LEADERBOARD_PASSED, 0)
        val rate = if (total == 0) 0.0 else passed * 100.0 / total
        val final = when {
            total < 30 -> "In progress"
            passed >= 27 -> "FINAL PASS"
            else -> "FINAL FAIL"
        }

        tvLeaderboardSummary.text = String.format(
            Locale.US,
            "Completed: %d/30 • Passed: %d • Rate: %.2f%% • %s",
            total,
            passed,
            rate,
            final
        )
    }

    // ---------------------------------------------------------------------
    // Test 3 — recoverability
    // ---------------------------------------------------------------------

    private fun recordPreCrashCount() {
        if (!hasReceivedStepValue) {
            toast("Wait until the current step count appears.")
            return
        }

        prefs.edit()
            .putInt(KEY_PRE_CRASH_TOTAL, currentTotalSteps)
            .putInt(KEY_PRE_CRASH_TODAY, currentTodaySteps)
            .putBoolean(KEY_PRE_CRASH_PENDING, true)
            .putLong(KEY_PRE_CRASH_RECORDED_AT, System.currentTimeMillis())
            .apply()

        tvRecoveryStatus.text = "Pre-crash count recorded. Now force-stop or use Controlled crash."
        updateRecordedPreCrashLabel()
        updateRecoveryButtons()
    }

    private fun confirmControlledCrash() {
        if (!prefs.getBoolean(KEY_PRE_CRASH_PENDING, false)) {
            toast("Record the pre-crash count first.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Crash the app deliberately?")
            .setMessage(
                "This closes the app immediately. Reopen it, return to the QA screen, " +
                        "wait for the recovered count, and press Evaluate recovery."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Crash now") { _, _ ->
                throw RuntimeException("Controlled QA crash")
            }
            .show()
    }

    private fun evaluateRecovery() {
        if (!prefs.getBoolean(KEY_PRE_CRASH_PENDING, false)) {
            toast("No pre-crash count is waiting for evaluation.")
            return
        }
        if (!hasReceivedStepValue) {
            toast("Wait until the recovered step count appears.")
            return
        }

        val before = prefs.getInt(KEY_PRE_CRASH_TOTAL, 0)
        val after = currentTotalSteps
        val lost = max(0, before - after)
        val success = lost <= MAX_ALLOWED_LOST_STEPS

        val total = prefs.getInt(KEY_RECOVERY_TOTAL, 0) + 1
        val passed = prefs.getInt(KEY_RECOVERY_PASSED, 0) + if (success) 1 else 0

        prefs.edit()
            .putInt(KEY_RECOVERY_TOTAL, total)
            .putInt(KEY_RECOVERY_PASSED, passed)
            .putBoolean(KEY_PRE_CRASH_PENDING, false)
            .apply()

        tvRecoveryStatus.text =
            "${if (success) "PASS" else "FAIL"} — Before: ${formatCount(before)} • " +
                    "After: ${formatCount(after)} • Lost: $lost steps"

        updateRecoverySummary()
        updateRecordedPreCrashLabel()
        updateRecoveryButtons()
    }

    private fun updateRecordedPreCrashLabel() {
        val pending = prefs.getBoolean(KEY_PRE_CRASH_PENDING, false)
        if (!pending) {
            tvRecordedBeforeCrash.text = "No pre-crash count is currently recorded."
            return
        }

        val total = prefs.getInt(KEY_PRE_CRASH_TOTAL, 0)
        val today = prefs.getInt(KEY_PRE_CRASH_TODAY, 0)
        tvRecordedBeforeCrash.text =
            "Waiting for recovery evaluation — recorded total: ${formatCount(total)} " +
                    "• recorded today: ${formatCount(today)}"
    }

    private fun updateRecoveryButtons() {
        val pending = prefs.getBoolean(KEY_PRE_CRASH_PENDING, false)
        btnControlledCrash.isEnabled = pending
        btnEvaluateRecovery.isEnabled = pending && hasReceivedStepValue
    }

    private fun updateRecoverySummary() {
        val total = prefs.getInt(KEY_RECOVERY_TOTAL, 0)
        val passed = prefs.getInt(KEY_RECOVERY_PASSED, 0)
        val final = when {
            total < 30 -> "In progress"
            passed == 30 -> "FINAL PASS"
            else -> "FINAL FAIL"
        }

        tvRecoverySummary.text =
            "Completed: $total/30 • Passed: $passed • Required: all 30 • $final"
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private fun confirmReset(title: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("This clears only the local QA counters on this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ -> onConfirm() }
            .show()
    }

    private fun formatCount(value: Int): String =
        String.format(Locale.US, "%,d", value)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PREFS_NAME = "goforit_qa_test_results"

        private const val KEY_BLE_TOTAL = "ble_total"
        private const val KEY_BLE_PASSED = "ble_passed"
        private const val KEY_BLE_IDS = "ble_ids"
        private const val KEY_BLE_SEQUENCE = "ble_sequence"

        private const val KEY_LEADERBOARD_TOTAL = "leaderboard_total"
        private const val KEY_LEADERBOARD_PASSED = "leaderboard_passed"
        private const val KEY_LEADERBOARD_IDS = "leaderboard_ids"

        private const val KEY_RECOVERY_TOTAL = "recovery_total"
        private const val KEY_RECOVERY_PASSED = "recovery_passed"
        private const val KEY_PRE_CRASH_TOTAL = "pre_crash_total"
        private const val KEY_PRE_CRASH_TODAY = "pre_crash_today"
        private const val KEY_PRE_CRASH_PENDING = "pre_crash_pending"
        private const val KEY_PRE_CRASH_RECORDED_AT = "pre_crash_recorded_at"

        private const val BLE_TIMEOUT_MS = 10_000L
        private const val MAX_ALLOWED_LOST_STEPS = 20

        private val RUN_ID_REGEX = Regex("^[A-Za-z0-9_-]{3,80}$")
        private val DAY_KEY_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}
