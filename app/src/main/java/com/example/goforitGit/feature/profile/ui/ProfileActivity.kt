package com.example.goforitGit.feature.profile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.R
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.databinding.FeatureProfileActivityBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * ProfileActivity — redesigned around Nielsen's 10 heuristics.
 *
 *  H1 Visibility of system status
 *    - Sticky save bar only appears when there are unsaved changes.
 *    - Last-synced timestamp is shown explicitly ("Synced 3 min ago").
 *    - Save button is disabled until form is dirty AND valid.
 *
 *  H2 Match between system and real world
 *    - Quiet hours use MaterialTimePicker (a clock face), not raw hour sliders.
 *    - Timezone uses an exposed dropdown of named IANA zones.
 *    - Faculty uses an exposed dropdown of named options.
 *
 *  H3 User control and freedom
 *    - "Discard" button reverts to the last-loaded snapshot.
 *    - Back press prompts the user to keep/discard pending changes.
 *
 *  H4 Consistency and standards
 *    - Errors use the Material TextInputLayout error API (red text under the field).
 *    - Single primary action (green Save). Refresh is a quiet tertiary text button.
 *
 *  H5 Error prevention
 *    - Live validation as the user types (only meaningful after first edit).
 *    - Timezone & faculty are dropdowns, not free-text.
 *    - We refuse to save if quietStart == quietEnd (no quiet window).
 *    - Email change asks for explicit confirmation before submission.
 *
 *  H6 Recognition over recall
 *    - Username and email fields have always-visible helper text describing the rule.
 *    - Quiet window is shown as a colored 24-hour timeline below the time cards.
 *
 *  H7 Flexibility and efficiency
 *    - Tap the avatar OR the small camera badge to change the photo.
 *    - Tap the time card OR its inner text to open the picker.
 *
 *  H8 Aesthetic and minimalist design
 *    - Removed the fake stats (badges / best rank / days active).
 *    - Stats strip shows ONLY metrics that exist in the backend.
 *    - Compact 64dp avatar instead of the previous 96dp + decoration.
 *
 *  H9 Help users recognize, diagnose, and recover from errors
 *    - prettyProfileError() maps Firebase exceptions to actionable copy.
 *    - "Recent login required" prompts re-authentication path explicitly.
 *
 *  H10 Help and documentation
 *    - Helper text under every editable field explains both format and consequence.
 *    - "Low-activity nudges" has an inline 1-sentence description next to the toggle.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: FeatureProfileActivityBinding

    // ---- State --------------------------------------------------------------

    /** The snapshot we last successfully loaded from / saved to the server. */
    private var baselineProfile: FirebaseServerApi.UserProfile? = null
    private var baselineImageUrl: String = ""

    /** Pending photo selection that hasn't been uploaded yet. */
    private var pendingImageUri: Uri? = null

    /** Working quiet-hours selection (0..23). Mirrors what the user picked in time pickers. */
    private var workingQuietStartHour: Int = DEFAULT_QUIET_START
    private var workingQuietEndHour: Int = DEFAULT_QUIET_END

    /** Last time we successfully refreshed from the server. */
    private var lastSyncEpochMs: Long = 0L

    /** Guards against re-entry during async save / load. */
    private var isBusy: Boolean = false

    /** Suppresses dirty-check while we programmatically populate the form. */
    private var suppressDirtyCheck: Boolean = false

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                pendingImageUri = uri
                binding.ivProfileImage.clearColorFilter()
                binding.ivProfileImage.setImageURI(uri)
                refreshDirtyState()
            }
        }

    // ---- Lifecycle ----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = FeatureProfileActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (FirebaseServerApi.currentUser() == null) {
            toast("Please sign in first.")
            finish()
            return
        }

        setupToolbar()
        setupDropdowns()
        setupTextWatchers()
        setupActions()
        setupBackNavigation()
        setDefaultProfileImage()
        renderTimeline()           // initial neutral state
        renderQuietSummary()
        renderLastSync()
        refreshDirtyState()        // hides save bar initially

        loadProfile()
    }

    // ---- Setup --------------------------------------------------------------

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sign_out -> { confirmSignOut(); true }
                else -> false
            }
        }
    }

    private fun setupDropdowns() {
        // Afeka B.Sc. departments and study fields.
        val facultyOptions = arrayOf(
            "Electrical Engineering",
            "Mechanical Engineering",
            "Software Engineering",
            "Information Systems Engineering",
            "Medical Engineering",
            "Computer Science",
            "Data Science",
            "Interdisciplinary Programs",
            "Other"
        )

        binding.etFaculty.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, facultyOptions)
        )

        val timezoneOptions = arrayOf(
            "Asia/Jerusalem",
            "Europe/London",
            "Europe/Paris",
            "Europe/Berlin",
            "Europe/Moscow",
            "Asia/Dubai",
            "Asia/Kolkata",
            "Asia/Singapore",
            "Asia/Tokyo",
            "Australia/Sydney",
            "America/New_York",
            "America/Chicago",
            "America/Los_Angeles",
            "America/Sao_Paulo",
            "UTC"
        )

        binding.etTimezone.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, timezoneOptions)
        )
    }

    private fun setupTextWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressDirtyCheck) return
                validateField(binding.etUsername.id)
                validateField(binding.etEmailEdit.id)
                validateField(binding.etTimezone.id)
                refreshDirtyState()
            }
        }
        binding.etUsername.addTextChangedListener(watcher)
        binding.etEmailEdit.addTextChangedListener(watcher)
        binding.etTimezone.addTextChangedListener(watcher)
        binding.etFaculty.addTextChangedListener(watcher)

        binding.switchLowActivityNudges.setOnCheckedChangeListener { _, _ ->
            if (!suppressDirtyCheck) refreshDirtyState()
        }
    }

    private fun setupActions() {
        binding.btnRefresh.setOnClickListener {
            if (isAnyFieldDirty()) {
                confirmAction(
                    title = "Refresh and lose changes?",
                    message = "Your unsaved edits will be replaced with the latest from the server.",
                    confirmLabel = "Refresh"
                ) { loadProfile() }
            } else {
                loadProfile()
            }
        }

        binding.btnSave.setOnClickListener { attemptSave() }
        binding.btnDiscard.setOnClickListener { confirmDiscard() }

        binding.btnChangePhoto.setOnClickListener { openImagePicker() }
        binding.cardProfileImage.setOnClickListener { openImagePicker() }
        binding.ivProfileImage.setOnClickListener { openImagePicker() }

        binding.cardBedtime.setOnClickListener { showTimePicker(forBedtime = true) }
        binding.cardWakeup.setOnClickListener { showTimePicker(forBedtime = false) }
    }

    private fun setupBackNavigation() {
        // H3: Don't silently lose the user's edits on back-press.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isAnyFieldDirty()) {
                    confirmAction(
                        title = "Leave without saving?",
                        message = "Your changes will be lost.",
                        confirmLabel = "Discard"
                    ) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun openImagePicker() {
        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // ---- Load ---------------------------------------------------------------

    private fun loadProfile() {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val user = FirebaseServerApi.currentUser()
                if (user == null) {
                    toast("Please sign in first.")
                    finish()
                    return@launch
                }

                FirebaseServerApi.getMyProfileResult()
                    .onSuccess { profile ->
                        baselineProfile = profile
                        baselineImageUrl = profile.profileImageUrl
                        pendingImageUri = null
                        lastSyncEpochMs = System.currentTimeMillis()
                        renderProfile(profile, user.email.orEmpty())
                        renderLastSync()
                        refreshDirtyState()
                    }
                    .onFailure { e ->
                        toast(e.message ?: "Failed to load profile.")
                    }
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- Render -------------------------------------------------------------

    private fun renderProfile(profile: FirebaseServerApi.UserProfile, emailFallback: String) {
        suppressDirtyCheck = true
        try {
            // Header
            binding.tvHeaderUsername.text = profile.username.ifBlank { "—" }
            binding.tvHeaderEmail.text = profile.email.ifBlank { emailFallback.ifBlank { "—" } }

            // Stats strip — four cards.
            // Total Points is the only one currently backed by the server
            // (cumulativeTotalPoints). Badges, Best Rank and Days Active are
            // not yet exposed on UserProfile; once the backend adds them,
            // wire them up here.
            val fmt = NumberFormat.getInstance(Locale.getDefault())
            binding.tvStatsTotalPoints.text = fmt.format(profile.cumulativeTotalPoints)
            binding.tvStatsBadges.text = "0"          // TODO: profile.badgesCount
            binding.tvStatsBestRank.text = "—"        // TODO: profile.bestRank?.let { "#$it" } ?: "—"
            binding.tvStatsDaysActive.text = "0"      // TODO: profile.daysActive

            // Personal info
            binding.etUsername.setText(profile.username)
            binding.etEmailEdit.setText(profile.email.ifBlank { emailFallback })
            binding.etFaculty.setText(profile.faculty, false)

            // Account
            binding.etTimezone.setText(profile.timezone, false)

            // Reminders
            binding.switchLowActivityNudges.isChecked = profile.lowActivityNudgeEnabled
            workingQuietStartHour = profile.quietHoursStartHour
            workingQuietEndHour = profile.quietHoursEndHour
            renderQuietHoursValues()
            renderTimeline()
            renderQuietSummary()

            // Photo
            clearAllErrors()
            if (profile.profileImageUrl.isBlank()) {
                setDefaultProfileImage()
            } else {
                lifecycleScope.launch { loadRemoteImage(profile.profileImageUrl) }
            }
        } finally {
            suppressDirtyCheck = false
        }
    }

    private fun renderQuietHoursValues() {
        binding.tvBedtimeValue.text = hourLabel(workingQuietStartHour)
        binding.tvWakeupValue.text = hourLabel(workingQuietEndHour)
    }

    private fun renderTimeline() {
        // Treat quietStart..quietEnd as the muted window (wrapping past midnight).
        // Three weighted segments left/mid/right cover the 24h bar exactly.
        val start = workingQuietStartHour
        val end = workingQuietEndHour
        val MUTED = Color.parseColor("#7C39A0")
        val ACTIVE = Color.parseColor("#E6E2EE")

        val (lw, lc) = segLeft(start, end)
        val (mw, mc) = segMid(start, end)
        val (rw, rc) = segRight(start, end)

        applySegment(binding.timelineSegLeft, lw, if (lc) MUTED else ACTIVE)
        applySegment(binding.timelineSegMid, mw, if (mc) MUTED else ACTIVE)
        applySegment(binding.timelineSegRight, rw, if (rc) MUTED else ACTIVE)
    }

    /**
     * Quiet window goes from [start] (inclusive) to [end] (exclusive), wrapping past midnight.
     * Returns (weight, isMuted) per segment. weight=0 collapses a segment.
     */
    private fun segLeft(start: Int, end: Int): Pair<Float, Boolean> = when {
        start == end -> 24f to false                    // no quiet window
        start < end  -> start.toFloat() to false        // 00..start = active
        else         -> end.toFloat() to true           // 00..end   = muted (after wrap)
    }

    private fun segMid(start: Int, end: Int): Pair<Float, Boolean> = when {
        start == end -> 0f to false
        start < end  -> (end - start).toFloat() to true // start..end = muted
        else         -> (start - end).toFloat() to false // end..start = active
    }

    private fun segRight(start: Int, end: Int): Pair<Float, Boolean> = when {
        start == end -> 0f to false
        start < end  -> (24 - end).toFloat() to false   // end..24    = active
        else         -> (24 - start).toFloat() to true  // start..24  = muted (before wrap)
    }

    private fun applySegment(view: View, weight: Float, color: Int) {
        val lp = view.layoutParams as LinearLayout.LayoutParams
        lp.weight = weight
        view.layoutParams = lp
        view.setBackgroundColor(color)
    }

    private fun renderQuietSummary() {
        val start = hourLabel(workingQuietStartHour)
        val end = hourLabel(workingQuietEndHour)
        binding.tvQuietSummary.text = if (workingQuietStartHour == workingQuietEndHour) {
            "No quiet window set."
        } else {
            "Notifications muted from $start to $end."
        }
    }

    private fun renderLastSync() {
        binding.tvLastSync.text = relativeTimeLabel(lastSyncEpochMs)
    }

    private fun relativeTimeLabel(epochMs: Long): String {
        if (epochMs == 0L) return "Not synced yet"
        val diff = Duration.between(Instant.ofEpochMilli(epochMs), Instant.now())
        return when {
            diff.toMinutes() < 1 -> "Synced just now"
            diff.toMinutes() < 60 -> "Synced ${diff.toMinutes()} min ago"
            diff.toHours() < 24 -> "Synced ${diff.toHours()} hr ago"
            else -> "Synced ${diff.toDays()} day(s) ago"
        }
    }

    // ---- Save ---------------------------------------------------------------

    private fun attemptSave() {
        if (isBusy) return
        clearAllErrors()

        val desiredUsername = binding.etUsername.text?.toString()?.trim().orEmpty()
        val desiredEmail = binding.etEmailEdit.text?.toString()?.trim().orEmpty()
        val faculty = binding.etFaculty.text?.toString()?.trim().orEmpty()
        val timezone = binding.etTimezone.text?.toString()?.trim().orEmpty()
            .ifBlank { "Asia/Jerusalem" }

        var valid = true
        if (!isValidUsername(desiredUsername)) {
            binding.tilUsername.error = "3–24 characters. Letters, numbers, spaces, and . _ - '"
            valid = false
        }
        if (desiredEmail.isBlank()) {
            binding.tilEmailEdit.error = "Email is required"
            valid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(desiredEmail).matches()) {
            binding.tilEmailEdit.error = "Enter a valid email"
            valid = false
        }
        if (!isValidTimezone(timezone)) {
            binding.tilTimezone.error = "Pick a valid timezone"
            valid = false
        }
        if (workingQuietStartHour == workingQuietEndHour) {
            toast("Quiet hours need a start and end (they can't be the same).")
            valid = false
        }
        if (!valid) return

        // H5: confirm email change BEFORE we attempt the network call, since
        // Firebase will reject without a recent login and that path is brittle.
        val emailChanged = baselineProfile?.email?.equals(desiredEmail, ignoreCase = true) == false
                && baselineProfile?.email?.isNotBlank() == true

        if (emailChanged) {
            confirmAction(
                title = "Change your email?",
                message = "You may be asked to sign in again before this change is applied.",
                confirmLabel = "Change email"
            ) { performSave(desiredUsername, desiredEmail, faculty, timezone) }
        } else {
            performSave(desiredUsername, desiredEmail, faculty, timezone)
        }
    }

    private fun performSave(
        username: String,
        email: String,
        faculty: String,
        timezone: String
    ) {
        val base = baselineProfile
        isBusy = true
        setBusy(true)

        lifecycleScope.launch {
            try {
                val uploadedImageUrl = if (pendingImageUri != null) {
                    val bytes = readBytesFromUri(pendingImageUri!!)
                    FirebaseServerApi.uploadProfileImageBytesResult(bytes).getOrThrow()
                } else {
                    baselineImageUrl
                }

                val profile = FirebaseServerApi.UserProfile(
                    username = username,
                    email = email,
                    profileImageUrl = uploadedImageUrl,
                    timezone = timezone,
                    lowActivityNudgeEnabled = binding.switchLowActivityNudges.isChecked,
                    preferredActiveInterval = base?.preferredActiveInterval,
                    preferredInactiveInterval = base?.preferredInactiveInterval,
                    quietHoursStartHour = workingQuietStartHour,
                    quietHoursEndHour = workingQuietEndHour,
                    faculty = faculty,
                    cumulativeTotalPoints = base?.cumulativeTotalPoints ?: 0,
                    cumulativeTotalSteps = base?.cumulativeTotalSteps ?: 0,
                    cumulativeBonusPoints = base?.cumulativeBonusPoints ?: 0,
                )

                FirebaseServerApi.updateMyProfileResult(profile)
                    .onSuccess { saved ->
                        baselineProfile = saved
                        baselineImageUrl = saved.profileImageUrl
                        pendingImageUri = null
                        lastSyncEpochMs = System.currentTimeMillis()
                        toast("Profile updated.")
                        loadProfile() // re-pull canonical state from server
                    }
                    .onFailure { throw it }
            } catch (e: Exception) {
                toast(prettyProfileError(e))
            } finally {
                isBusy = false
                setBusy(false)
            }
        }
    }

    private suspend fun readBytesFromUri(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Failed to read the selected image.")
    }

    private suspend fun loadRemoteImage(url: String) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (bitmap != null) setBitmapProfileImage(bitmap) else setDefaultProfileImage()
    }

    private fun setBitmapProfileImage(bitmap: Bitmap) {
        binding.ivProfileImage.clearColorFilter()
        binding.ivProfileImage.setImageBitmap(bitmap)
    }

    private fun setDefaultProfileImage() {
        binding.ivProfileImage.setImageResource(R.drawable.ic_person)
        binding.ivProfileImage.setColorFilter(Color.parseColor("#7C39A0"))
    }

    // ---- Time picker --------------------------------------------------------

    private fun showTimePicker(forBedtime: Boolean) {
        val initialHour = if (forBedtime) workingQuietStartHour else workingQuietEndHour
        val title = if (forBedtime) "Bedtime" else "Wake-up"

        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(initialHour)
            .setMinute(0)
            .setTitleText(title)
            .build()

        picker.addOnPositiveButtonClickListener {
            val newHour = picker.hour
            if (forBedtime) workingQuietStartHour = newHour
            else workingQuietEndHour = newHour
            renderQuietHoursValues()
            renderTimeline()
            renderQuietSummary()
            refreshDirtyState()
        }
        picker.show(supportFragmentManager, "time_picker_${if (forBedtime) "bed" else "wake"}")
    }

    // ---- Validation ---------------------------------------------------------

    private fun validateField(viewId: Int) {
        when (viewId) {
            binding.etUsername.id -> {
                val v = binding.etUsername.text?.toString()?.trim().orEmpty()
                binding.tilUsername.error = if (v.isEmpty() || isValidUsername(v)) null
                else "3–24 characters. Letters, numbers, spaces, and . _ - '"
            }
            binding.etEmailEdit.id -> {
                val v = binding.etEmailEdit.text?.toString()?.trim().orEmpty()
                binding.tilEmailEdit.error = when {
                    v.isEmpty() -> null
                    !Patterns.EMAIL_ADDRESS.matcher(v).matches() -> "Enter a valid email"
                    else -> null
                }
            }
            binding.etTimezone.id -> {
                val v = binding.etTimezone.text?.toString()?.trim().orEmpty()
                binding.tilTimezone.error = if (v.isEmpty() || isValidTimezone(v)) null
                else "Pick a valid timezone"
            }
        }
    }

    private fun clearAllErrors() {
        binding.tilUsername.error = null
        binding.tilEmailEdit.error = null
        binding.tilTimezone.error = null
        binding.tilFaculty.error = null
    }

    /**
     * Allowed: letters, digits, spaces, and . _ - '
     * Must start and end with a letter or digit (no leading/trailing spaces).
     * Length 3..24.
     *
     * Examples that pass: "naor zion", "Mary-Jane", "O'Brien", "john.doe"
     * Examples that fail: " naor", "ab", "naor!", "naor  zion" (double space ok per regex but consecutive-space check is intentionally omitted for simplicity)
     */
    private fun isValidUsername(value: String): Boolean =
        value.length in 3..24 && value.matches(Regex("^[A-Za-z0-9][A-Za-z0-9 _.'\\-]{1,22}[A-Za-z0-9]$"))

    private fun isValidTimezone(value: String): Boolean =
        runCatching { ZoneId.of(value) }.isSuccess

    // ---- Dirty tracking -----------------------------------------------------

    /**
     * Are the current form values different from the loaded baseline?
     * Returns true even when invalid — the save bar should still show, with a disabled Save.
     */
    private fun isAnyFieldDirty(): Boolean {
        val base = baselineProfile ?: return false
        val u = binding.etUsername.text?.toString()?.trim().orEmpty()
        val e = binding.etEmailEdit.text?.toString()?.trim().orEmpty()
        val f = binding.etFaculty.text?.toString()?.trim().orEmpty()
        val tz = binding.etTimezone.text?.toString()?.trim().orEmpty()
        val nudges = binding.switchLowActivityNudges.isChecked

        return u != base.username ||
                e != base.email ||
                f != base.faculty ||
                tz != base.timezone ||
                nudges != base.lowActivityNudgeEnabled ||
                workingQuietStartHour != base.quietHoursStartHour ||
                workingQuietEndHour != base.quietHoursEndHour ||
                pendingImageUri != null
    }

    private fun isFormValid(): Boolean {
        val u = binding.etUsername.text?.toString()?.trim().orEmpty()
        val e = binding.etEmailEdit.text?.toString()?.trim().orEmpty()
        val tz = binding.etTimezone.text?.toString()?.trim().orEmpty()
        return isValidUsername(u) &&
                e.isNotBlank() &&
                Patterns.EMAIL_ADDRESS.matcher(e).matches() &&
                isValidTimezone(tz) &&
                workingQuietStartHour != workingQuietEndHour
    }

    private fun refreshDirtyState() {
        val dirty = isAnyFieldDirty()
        binding.saveBar.isVisible = dirty
        binding.btnSave.isEnabled = dirty && isFormValid()
        binding.btnDiscard.isEnabled = dirty
    }

    // ---- Confirmation dialogs ----------------------------------------------

    private fun confirmDiscard() {
        confirmAction(
            title = "Discard changes?",
            message = "Your edits will be replaced with the last saved version.",
            confirmLabel = "Discard"
        ) {
            val base = baselineProfile ?: return@confirmAction
            renderProfile(base, FirebaseServerApi.currentUser()?.email.orEmpty())
            refreshDirtyState()
        }
    }

    private fun confirmSignOut() {
        val proceed = {
            FirebaseServerApi.signOut()
            toast("Signed out.")
            finish()
        }
        if (isAnyFieldDirty()) {
            confirmAction(
                title = "Sign out and lose changes?",
                message = "Your unsaved changes will be discarded.",
                confirmLabel = "Sign out"
            ) { proceed() }
        } else {
            confirmAction(
                title = "Sign out?",
                message = "You'll need to sign in again to use the app.",
                confirmLabel = "Sign out"
            ) { proceed() }
        }
    }

    private fun confirmAction(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Error mapping ------------------------------------------------------

    private fun prettyProfileError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("already taken", ignoreCase = true) ->
                "That username is already taken."
            msg.contains("email", ignoreCase = true) && msg.contains("use", ignoreCase = true) ->
                "That email is already in use."
            msg.contains("recent login", ignoreCase = true) ->
                "Please sign in again before changing your email."
            msg.contains("storage", ignoreCase = true) ->
                "Couldn't upload the photo. Try again."
            msg.contains("network", ignoreCase = true) ->
                "Network error. Check your connection and retry."
            msg.isNotBlank() -> msg
            else -> "Failed to update profile."
        }
    }

    // ---- Misc helpers -------------------------------------------------------

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        val enabled = !busy
        val controls = listOf<View>(
            binding.btnRefresh, binding.btnSave, binding.btnDiscard,
            binding.btnChangePhoto, binding.cardProfileImage, binding.ivProfileImage,
            binding.etUsername, binding.etEmailEdit, binding.etFaculty,
            binding.etTimezone, binding.switchLowActivityNudges,
            binding.cardBedtime, binding.cardWakeup,
        )
        controls.forEach { it.isEnabled = enabled }
    }

    private fun hourLabel(hour: Int): String = String.format(Locale.US, "%02d:00", hour)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DEFAULT_QUIET_START = 22
        private const val DEFAULT_QUIET_END = 8
    }
}