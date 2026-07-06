package com.example.goforitGit.feature.challenges.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.goforitGit.R
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.core.data.StepsData.StepBus
import com.example.goforitGit.databinding.FeaturePersonalChallengesActivityBinding
import com.example.goforitGit.feature.challenges.model.ActiveChallenge
import com.example.goforitGit.feature.challenges.model.ChallengeDifficulty
import com.example.goforitGit.feature.challenges.model.ChallengeOffers
import com.example.goforitGit.feature.challenges.model.ChallengeStatus
import com.example.goforitGit.feature.challenges.model.ChallengeType
import com.example.goforitGit.feature.challenges.model.DifficultyTier
import com.example.goforitGit.feature.challenges.model.PersonalChallengesState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Personal Challenges screen.
 *
 * Cloud Functions remain authoritative for challenge targets, rewards,
 * completion, points, and leaderboard updates. While this screen is visible,
 * it overlays a local, display-only preview from StepBus so the progress bar
 * responds immediately without sending frequent Firebase requests.
 *
 * Local preview steps are shown as pending verification. They never grant
 * rewards or change persisted challenge state on the Android client.
 */
class PersonalChallengesActivity : AppCompatActivity() {

    private lateinit var binding: FeaturePersonalChallengesActivityBinding

    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    /** Prevents overlapping network calls / duplicate button taps. */
    private var requestInFlight = false

    /** Currently displayed state (may be a cached, potentially-stale copy). */
    private var currentState: PersonalChallengesState? = null

    /** Difficulty selected for Raise Your Baseline, if any. */
    private var selectedTier: DifficultyTier? = null

    /**
     * StepBus publishes the app's persisted cumulative valid-step total.
     * It is observed only for immediate UI preview; it never triggers a network call.
     */
    private var observedLocalStepTotal: Int = 0

    /**
     * The last server timestamp lets window checks use a server-aligned clock even
     * when the device clock is slightly inaccurate.
     */
    private var serverClockOffsetMs: Long = 0L

    /** Schedules the local Study-Break preview anchor when its window begins. */
    private var studyWindowAnchorRunnable: Runnable? = null

    private val previewPrefs by lazy {
        getSharedPreferences(PREVIEW_PREFS_NAME, MODE_PRIVATE)
    }

    companion object {
        private const val PREVIEW_PREFS_NAME = "personal_challenge_preview"
        private const val NO_STEP_ANCHOR = Int.MIN_VALUE

        /** Display-only cache of the last successfully loaded state. */
        private var cachedState: PersonalChallengesState? = null

        fun createIntent(context: Context): Intent =
            Intent(context, PersonalChallengesActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = FeaturePersonalChallengesActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnRetry.setOnClickListener { loadInitial() }

        binding.chipGroupBaseline.setOnCheckedStateChangeListener { _, _ ->
            updateBaselineTargetPreview()
        }

        binding.btnStartBaseline.setOnClickListener {
            val tier = selectedTier
            if (tier == null) {
                Toast.makeText(this, R.string.pc_pick_difficulty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmAndStart(
                type = ChallengeType.RAISE_BASELINE,
                difficulty = tier.difficulty,
                title = getString(R.string.pc_baseline_title),
                target = getString(R.string.pc_baseline_target_value, formatCount(tier.targetSteps)),
                reward = tier.reward,
            )
        }

        binding.btnStartStudy.setOnClickListener {
            val offer = currentState?.offers?.studyBreak ?: return@setOnClickListener
            confirmAndStart(
                type = ChallengeType.STUDY_BREAK_BOOST,
                difficulty = null,
                title = getString(R.string.pc_study_title),
                target = getString(
                    R.string.pc_study_target_value,
                    formatCount(offer.goalSteps),
                    offer.selectedIntervalLabel ?: ""
                ),
                reward = offer.reward,
            )
        }

        binding.btnStartCampus.setOnClickListener {
            val offer = currentState?.offers?.campusExplorer ?: return@setOnClickListener
            confirmAndStart(
                type = ChallengeType.CAMPUS_EXPLORER,
                difficulty = null,
                title = getString(R.string.pc_campus_title),
                target = getString(R.string.pc_campus_target_value, formatCount(offer.goalSteps)),
                reward = offer.reward,
            )
        }

        observedLocalStepTotal = StepBus.steps.value
        observeLocalSteps()
        loadInitial()
    }

    override fun onResume() {
        super.onResume()
        // Refresh silently so progress / completion stay current without a flash.
        if (currentState != null) refresh() else loadInitial()
    }

    override fun onPause() {
        super.onPause()
        stopCountdown()
        stopStudyWindowAnchor()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_personal_challenges, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_refresh) {
            refresh()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private fun loadInitial() {
        val cache = cachedState
        if (cache != null) {
            currentState = cache
            renderState(cache, stale = true)
        } else {
            showLoading()
        }
        fetch()
    }

    private fun refresh() {
        // Keep whatever is on screen; just re-fetch canonical state.
        fetch()
    }

    private fun fetch() {
        if (requestInFlight) return
        requestInFlight = true

        lifecycleScope.launch {
            val result = FirebaseServerApi.getMyPersonalChallengesResult()
            requestInFlight = false
            result
                .onSuccess { state ->
                    serverClockOffsetMs = state.serverNowMs - System.currentTimeMillis()
                    cachedState = state
                    currentState = state
                    renderState(state, stale = false)
                }
                .onFailure { err ->
                    val cache = currentState
                    if (cache != null) {
                        // Show the last known state, clearly marked as possibly outdated,
                        // with acceptance disabled.
                        renderState(cache, stale = true)
                    } else {
                        showError(err.message ?: getString(R.string.pc_error_generic))
                    }
                }
        }
    }

    // -------------------------------------------------------------------------
    // Top-level rendering
    // -------------------------------------------------------------------------

    private fun showLoading() {
        stopCountdown()
        binding.loadingBox.visibility = View.VISIBLE
        binding.errorBox.visibility = View.GONE
        binding.staleBanner.visibility = View.GONE
        binding.activeCard.visibility = View.GONE
        binding.offersBox.visibility = View.GONE
    }

    private fun showError(message: String) {
        stopCountdown()
        binding.loadingBox.visibility = View.GONE
        binding.activeCard.visibility = View.GONE
        binding.offersBox.visibility = View.GONE
        binding.staleBanner.visibility = View.GONE
        binding.errorBox.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun renderState(state: PersonalChallengesState, stale: Boolean) {
        binding.loadingBox.visibility = View.GONE
        binding.errorBox.visibility = View.GONE
        binding.staleBanner.visibility = if (stale) View.VISIBLE else View.GONE

        val active = state.active
        if (active != null) {
            ensureLocalPreviewAnchor(active)
            renderActive(active)
            renderOffersWhenChosen(active)
        } else {
            binding.activeCard.visibility = View.GONE
            renderOffers(state.offers, stale)
        }
    }

    // -------------------------------------------------------------------------
    // Active challenge
    // -------------------------------------------------------------------------

    private fun renderActive(active: ActiveChallenge) {
        binding.activeCard.visibility = View.VISIBLE

        binding.ivActiveIcon.setImageResource(iconFor(active.type))
        binding.tvActiveTitle.text = titleFor(active.type)
        binding.tvActiveReward.text =
            getString(R.string.pc_reward_line, active.rewardPoints)

        // Status pill
        val (statusText, statusColor) = when (active.status) {
            ChallengeStatus.COMPLETED -> getString(R.string.pc_status_completed) to Color.parseColor("#4FB36A")
            ChallengeStatus.EXPIRED -> getString(R.string.pc_status_expired) to Color.parseColor("#7B8197")
            else -> getString(R.string.pc_status_active) to Color.parseColor("#4FB36A")
        }
        binding.tvActiveStatus.text = statusText
        binding.tvActiveStatus.backgroundTintList = ColorStateList.valueOf(statusColor)

        // Reset sub-sections
        binding.activeProgressBox.visibility = View.GONE
        binding.campusProgressBox.visibility = View.GONE
        binding.completedBox.visibility = View.GONE

        when (active.status) {
            ChallengeStatus.COMPLETED -> {
                stopCountdown()
                binding.tvTimeRemaining.visibility = View.GONE
                binding.completedBox.visibility = View.VISIBLE
                binding.tvCompletedReward.text =
                    getString(R.string.pc_complete_reward, active.rewardPoints)
            }

            ChallengeStatus.EXPIRED -> {
                stopCountdown()
                binding.tvTimeRemaining.visibility = View.VISIBLE
                binding.tvTimeRemaining.text = getString(R.string.pc_expired_window)
                renderProgressBody(active)
            }

            else -> {
                binding.tvTimeRemaining.visibility = View.VISIBLE
                renderProgressBody(active)
                startCountdown(active)
            }
        }
    }

    /** Renders the progress rows for a non-completed active challenge. */
    private fun renderProgressBody(active: ActiveChallenge) {
        when (active.type) {
            ChallengeType.RAISE_BASELINE -> {
                val preview = previewProgress(active)
                binding.activeProgressBox.visibility = View.VISIBLE
                binding.tvActiveProgress.text = withPendingVerification(
                    base = getString(
                        R.string.pc_progress_baseline,
                        formatCount(preview.displayedSteps),
                        formatCount(active.targetSteps)
                    ),
                    pendingSteps = preview.pendingSteps,
                    targetReachedLocally = preview.displayedSteps >= active.targetSteps
                )
                setProgress(binding.pbActive, preview.displayedSteps, active.targetSteps)
            }

            ChallengeType.STUDY_BREAK_BOOST -> {
                val preview = previewProgress(active)
                binding.activeProgressBox.visibility = View.VISIBLE
                binding.tvActiveProgress.text = withPendingVerification(
                    base = getString(
                        R.string.pc_progress_study,
                        formatCount(preview.displayedSteps),
                        formatCount(active.targetSteps),
                        active.selectedIntervalLabel ?: ""
                    ),
                    pendingSteps = preview.pendingSteps,
                    targetReachedLocally = preview.displayedSteps >= active.targetSteps
                )
                setProgress(binding.pbActive, preview.displayedSteps, active.targetSteps)
            }

            ChallengeType.CAMPUS_EXPLORER -> {
                /*
                 * StepBus contains all valid local steps, not verified campus-only
                 * steps. Keep Campus Explorer server-confirmed until the existing
                 * campus/geofence component exposes a separate qualified-step flow.
                 */
                binding.campusProgressBox.visibility = View.VISIBLE
                binding.tvCampusSteps.text = getString(
                    R.string.pc_progress_campus_steps,
                    formatCount(active.progressSteps),
                    formatCount(active.targetSteps)
                )
                setProgress(binding.pbCampusSteps, active.progressSteps, active.targetSteps)

                val stepsDone = active.progressSteps >= active.targetSteps
                TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    binding.tvCampusSteps,
                    0,
                    0,
                    if (stepsDone) R.drawable.ic_check_circle else 0,
                    0
                )
            }
        }
    }

    private data class LocalProgressPreview(
        val displayedSteps: Int,
        val pendingSteps: Int,
    )

    /**
     * Combines canonical server progress with a local StepBus preview.
     *
     * We use max(server, local), never addition: the local total already contains
     * steps that may later be included by a periodic server sync.
     */
    private fun previewProgress(active: ActiveChallenge): LocalProgressPreview {
        val serverProgress = active.progressSteps.coerceAtLeast(0)
        if (active.status != ChallengeStatus.ACTIVE) {
            return LocalProgressPreview(serverProgress, 0)
        }

        val localProgress = when (active.type) {
            ChallengeType.RAISE_BASELINE ->
                localStepsSinceAnchor(acceptedAnchorKey(active))

            ChallengeType.STUDY_BREAK_BOOST ->
                localStudyBreakSteps(active)

            ChallengeType.CAMPUS_EXPLORER -> null
        }

        val displayed = maxOf(serverProgress, localProgress ?: serverProgress)
            .coerceAtMost(active.targetSteps.coerceAtLeast(0))

        return LocalProgressPreview(
            displayedSteps = displayed,
            pendingSteps = (displayed - serverProgress).coerceAtLeast(0)
        )
    }

    private fun withPendingVerification(
        base: String,
        pendingSteps: Int,
        targetReachedLocally: Boolean,
    ): String {
        if (pendingSteps <= 0) return base

        val pending = if (pendingSteps == 1) {
            "+1 locally counted step awaiting verification."
        } else {
            "+${formatCount(pendingSteps)} locally counted steps awaiting verification."
        }

        return if (targetReachedLocally) {
            "$base\n$pending\nTarget reached — waiting for server verification."
        } else {
            "$base\n$pending"
        }
    }

    private fun observeLocalSteps() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StepBus.steps.collectLatest { totalSteps ->
                    observedLocalStepTotal = totalSteps.coerceAtLeast(0)

                    val active = currentState?.active
                    if (active?.status == ChallengeStatus.ACTIVE) {
                        ensureLocalPreviewAnchor(active)
                        renderProgressBody(active)
                    }
                }
            }
        }
    }

    private fun ensureLocalPreviewAnchor(active: ActiveChallenge) {
        if (active.status != ChallengeStatus.ACTIVE) return

        when (active.type) {
            ChallengeType.RAISE_BASELINE -> ensureAnchor(acceptedAnchorKey(active))
            ChallengeType.STUDY_BREAK_BOOST -> ensureStudyWindowAnchor(active)
            ChallengeType.CAMPUS_EXPLORER -> Unit
        }
    }

    /**
     * Called after the server confirms a newly accepted challenge. It establishes
     * the client-side baseline without writing any progress to Firebase.
     */
    private fun resetLocalPreviewAnchor(active: ActiveChallenge) {
        clearAnchor(acceptedAnchorKey(active))
        clearAnchor(studyWindowAnchorKey(active))
        stopStudyWindowAnchor()

        when (active.type) {
            ChallengeType.RAISE_BASELINE -> saveAnchor(acceptedAnchorKey(active))
            ChallengeType.STUDY_BREAK_BOOST -> ensureStudyWindowAnchor(active)
            ChallengeType.CAMPUS_EXPLORER -> Unit
        }
    }

    private fun localStepsSinceAnchor(key: String): Int? {
        val anchor = previewPrefs.getInt(key, NO_STEP_ANCHOR)
        if (anchor == NO_STEP_ANCHOR) return null
        return (observedLocalStepTotal - anchor).coerceAtLeast(0)
    }

    /**
     * Counts only local steps after the Study-Break window starts (or after
     * acceptance if the challenge was accepted during that window).
     */
    private fun localStudyBreakSteps(active: ActiveChallenge): Int? {
        val now = challengeNowMs()
        if (active.windowStartMs <= 0L || active.windowEndMs <= active.windowStartMs) {
            return null
        }
        if (now < active.windowStartMs || now >= active.windowEndMs) {
            return null
        }
        return localStepsSinceAnchor(studyWindowAnchorKey(active))
    }

    private fun ensureStudyWindowAnchor(active: ActiveChallenge) {
        val startMs = active.windowStartMs
        val endMs = active.windowEndMs
        if (startMs <= 0L || endMs <= startMs) return

        val now = challengeNowMs()
        val key = studyWindowAnchorKey(active)

        when {
            now >= endMs -> {
                stopStudyWindowAnchor()
            }

            now >= startMs -> {
                stopStudyWindowAnchor()
                ensureAnchor(key)
            }

            // A single runnable is enough. Do not cancel and recreate it on every step.
            studyWindowAnchorRunnable != null -> Unit

            else -> {
                val delayMs = (startMs - now).coerceAtLeast(0L)
                studyWindowAnchorRunnable = Runnable {
                    saveAnchor(key)
                    studyWindowAnchorRunnable = null
                    currentState?.active
                        ?.takeIf { it.type == ChallengeType.STUDY_BREAK_BOOST }
                        ?.let(::renderProgressBody)
                }
                handler.postDelayed(studyWindowAnchorRunnable!!, delayMs)
            }
        }
    }

    private fun stopStudyWindowAnchor() {
        studyWindowAnchorRunnable?.let(handler::removeCallbacks)
        studyWindowAnchorRunnable = null
    }

    private fun ensureAnchor(key: String) {
        if (previewPrefs.getInt(key, NO_STEP_ANCHOR) == NO_STEP_ANCHOR) {
            saveAnchor(key)
        }
    }

    private fun saveAnchor(key: String) {
        previewPrefs.edit().putInt(key, observedLocalStepTotal).apply()
    }

    private fun clearAnchor(key: String) {
        previewPrefs.edit().remove(key).apply()
    }

    private fun acceptedAnchorKey(active: ActiveChallenge): String =
        "accepted_${currentState?.dayKey.orEmpty()}_${active.type.wire}"

    private fun studyWindowAnchorKey(active: ActiveChallenge): String =
        "study_window_${currentState?.dayKey.orEmpty()}_${active.type.wire}"

    private fun challengeNowMs(): Long =
        System.currentTimeMillis() + serverClockOffsetMs

    // -------------------------------------------------------------------------
    // Offers (no challenge chosen yet)
    // -------------------------------------------------------------------------

    private fun renderOffers(offers: ChallengeOffers?, stale: Boolean) {
        if (offers == null) {
            binding.offersBox.visibility = View.GONE
            return
        }
        binding.offersBox.visibility = View.VISIBLE
        binding.cardBaseline.visibility = View.VISIBLE
        binding.cardStudyBreak.visibility = View.VISIBLE
        binding.cardCampus.visibility = View.VISIBLE

        // When showing a stale/offline copy, acceptance must be disabled.
        val acceptEnabled = !stale

        // ---- Raise Your Baseline ----
        val rb = offers.raiseBaseline
        binding.tvBaselineInfo.text =
            getString(R.string.pc_baseline_info, formatCount(rb.baselineSteps))
        val baselineTiers = rb.tiers.associateBy { it.difficulty }
        // Keep tiers available for target computation.
        chipTierMap = baselineTiers
        selectedTier = null
        binding.chipGroupBaseline.clearCheck()
        binding.tvBaselineTarget.text = getString(R.string.pc_baseline_target_hint)

        if (rb.available && acceptEnabled) {
            setCardEnabled(baselineCardEnabled = true)
            binding.tvBaselineUnavailable.visibility = View.GONE
            binding.chipGroupBaseline.visibility = View.VISIBLE
            binding.tvBaselineTarget.visibility = View.VISIBLE
        } else {
            setCardEnabled(baselineCardEnabled = false)
            binding.tvBaselineUnavailable.visibility = View.VISIBLE
            binding.tvBaselineUnavailable.text =
                rb.reason ?: getString(R.string.pc_error_generic)
        }

        // ---- Study-Break Boost ----
        val sb = offers.studyBreak
        binding.tvStudyWindow.text = getString(
            R.string.pc_study_window, sb.selectedIntervalLabel ?: getString(R.string.pc_unknown_window)
        )
        binding.tvStudyGoal.text =
            getString(R.string.pc_study_goal, formatCount(sb.goalSteps))
        binding.tvStudyReward.text = getString(R.string.pc_reward_line, sb.reward)

        if (sb.available && acceptEnabled) {
            muteCard(binding.cardStudyBreak, muted = false)
            binding.btnStartStudy.isEnabled = true
            binding.btnStartStudy.visibility = View.VISIBLE
            binding.tvStudyUnavailable.visibility = View.GONE
        } else {
            muteCard(binding.cardStudyBreak, muted = true)
            binding.btnStartStudy.isEnabled = false
            binding.tvStudyUnavailable.visibility = View.VISIBLE
            binding.tvStudyUnavailable.text =
                sb.reason ?: getString(R.string.pc_error_generic)
        }

        // ---- Campus Explorer ----
        val ce = offers.campusExplorer
        binding.tvCampusReward.text = getString(R.string.pc_reward_line, ce.reward)

        if (ce.available && acceptEnabled) {
            muteCard(binding.cardCampus, muted = false)
            binding.btnStartCampus.isEnabled = true
            binding.btnStartCampus.visibility = View.VISIBLE
            binding.tvCampusUnavailable.visibility = View.GONE
        } else {
            muteCard(binding.cardCampus, muted = true)
            binding.btnStartCampus.isEnabled = false
            binding.tvCampusUnavailable.visibility = View.VISIBLE
            binding.tvCampusUnavailable.text =
                ce.reason ?: getString(R.string.pc_error_generic)
        }
    }

    /** Tiers for the currently displayed baseline offer, keyed by difficulty. */
    private var chipTierMap: Map<ChallengeDifficulty, DifficultyTier> = emptyMap()

    private fun updateBaselineTargetPreview() {
        val checkedId = binding.chipGroupBaseline.checkedChipId
        val difficulty = when (checkedId) {
            R.id.chipEasy -> ChallengeDifficulty.EASY
            R.id.chipMedium -> ChallengeDifficulty.MEDIUM
            R.id.chipHard -> ChallengeDifficulty.HARD
            else -> null
        }
        val tier = difficulty?.let { chipTierMap[it] }
        selectedTier = tier
        binding.tvBaselineTarget.text = if (tier != null) {
            getString(R.string.pc_baseline_target_value, formatCount(tier.targetSteps))
        } else {
            getString(R.string.pc_baseline_target_hint)
        }
    }

    private fun setCardEnabled(baselineCardEnabled: Boolean) {
        muteCard(binding.cardBaseline, muted = !baselineCardEnabled)
        binding.chipEasy.isEnabled = baselineCardEnabled
        binding.chipMedium.isEnabled = baselineCardEnabled
        binding.chipHard.isEnabled = baselineCardEnabled
        binding.btnStartBaseline.isEnabled = baselineCardEnabled
    }

    /**
     * When a challenge is already chosen for today, keep the other two cards
     * visible but disabled with an explanatory note. Selection is not possible.
     */
    private fun renderOffersWhenChosen(active: ActiveChallenge) {
        // For completed / expired we disable ALL selection for the rest of the day.
        val terminal = active.isCompleted || active.isExpired
        binding.offersBox.visibility = View.VISIBLE

        configureChosenCard(
            card = binding.cardBaseline,
            type = ChallengeType.RAISE_BASELINE,
            active = active,
            terminal = terminal,
        )
        configureChosenCard(
            card = binding.cardStudyBreak,
            type = ChallengeType.STUDY_BREAK_BOOST,
            active = active,
            terminal = terminal,
        )
        configureChosenCard(
            card = binding.cardCampus,
            type = ChallengeType.CAMPUS_EXPLORER,
            active = active,
            terminal = terminal,
        )
    }

    private fun configureChosenCard(
        card: View,
        type: ChallengeType,
        active: ActiveChallenge,
        terminal: Boolean,
    ) {
        // Hide the card representing the chosen challenge (shown as the active card),
        // unless the day is terminal, in which case hide all three offer cards.
        if (type == active.type || terminal) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        muteCard(card, muted = true)
        when (type) {
            ChallengeType.RAISE_BASELINE -> {
                setCardEnabled(baselineCardEnabled = false)
                binding.chipGroupBaseline.visibility = View.GONE
                binding.tvBaselineTarget.visibility = View.GONE
                binding.tvBaselineUnavailable.visibility = View.VISIBLE
                binding.tvBaselineUnavailable.text = getString(R.string.pc_already_selected)
            }
            ChallengeType.STUDY_BREAK_BOOST -> {
                binding.btnStartStudy.isEnabled = false
                binding.tvStudyUnavailable.visibility = View.VISIBLE
                binding.tvStudyUnavailable.text = getString(R.string.pc_already_selected)
            }
            ChallengeType.CAMPUS_EXPLORER -> {
                binding.btnStartCampus.isEnabled = false
                binding.tvCampusUnavailable.visibility = View.VISIBLE
                binding.tvCampusUnavailable.text = getString(R.string.pc_already_selected)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Acceptance
    // -------------------------------------------------------------------------

    private fun confirmAndStart(
        type: ChallengeType,
        difficulty: ChallengeDifficulty?,
        title: String,
        target: String,
        reward: Int,
    ) {
        val message = getString(R.string.pc_confirm_body, target, reward)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.pc_not_now, null)
            .setPositiveButton(R.string.pc_start_challenge) { _, _ ->
                performAccept(type, difficulty)
            }
            .create()

        dialog.setOnShowListener {
            dialog.window?.decorView?.apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
            }

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
        }

        dialog.show()
    }

    private fun performAccept(type: ChallengeType, difficulty: ChallengeDifficulty?) {
        if (requestInFlight) return
        requestInFlight = true
        setAllStartButtonsEnabled(false)

        lifecycleScope.launch {
            val result = FirebaseServerApi.acceptPersonalChallengeResult(
                challengeType = type.wire,
                difficulty = difficulty?.wire,
            )
            requestInFlight = false
            result
                .onSuccess { state ->
                    serverClockOffsetMs = state.serverNowMs - System.currentTimeMillis()
                    cachedState = state
                    currentState = state
                    state.active?.let(::resetLocalPreviewAnchor)
                    renderState(state, stale = false)
                }
                .onFailure { err ->
                    Toast.makeText(
                        this@PersonalChallengesActivity,
                        err.message ?: getString(R.string.pc_accept_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    setAllStartButtonsEnabled(true)
                    // Re-sync with the server so availability reflects the true state.
                    refresh()
                }
        }
    }

    private fun setAllStartButtonsEnabled(enabled: Boolean) {
        binding.btnStartBaseline.isEnabled = enabled
        binding.btnStartStudy.isEnabled = enabled
        binding.btnStartCampus.isEnabled = enabled
    }

    // -------------------------------------------------------------------------
    // Countdown to midnight (server-provided absolute epoch)
    // -------------------------------------------------------------------------

    private fun startCountdown(active: ActiveChallenge) {
        stopCountdown()
        val midnightMs = currentState?.midnightMs ?: return
        // Study-Break Boost ends when its 4-hour window closes, not at midnight.
        val windowed = active.type == ChallengeType.STUDY_BREAK_BOOST && active.windowEndMs > 0L
        val runnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (windowed && now < active.windowStartMs) {
                    // Window not open yet — count down to its start.
                    binding.tvTimeRemaining.text = getString(
                        R.string.pc_window_starts_in,
                        formatDuration(active.windowStartMs - now)
                    )
                    handler.postDelayed(this, 1000L)
                    return
                }
                val deadlineMs = if (windowed) active.windowEndMs else midnightMs
                val label = if (windowed) R.string.pc_window_time_remaining
                else R.string.pc_time_remaining
                val remaining = deadlineMs - now
                if (remaining <= 0) {
                    // Window closed / day rolled over — pull fresh state so the
                    // server can mark the challenge expired or start a new day.
                    binding.tvTimeRemaining.text = getString(label, "00:00:00")
                    refresh()
                    return
                }
                binding.tvTimeRemaining.text = getString(label, formatDuration(remaining))
                handler.postDelayed(this, 1000L)
            }
        }
        countdownRunnable = runnable
        handler.post(runnable)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun iconFor(type: ChallengeType): Int = when (type) {
        ChallengeType.RAISE_BASELINE -> R.drawable.ic_trending_up
        ChallengeType.STUDY_BREAK_BOOST -> R.drawable.ic_study_break
        ChallengeType.CAMPUS_EXPLORER -> R.drawable.ic_route
    }

    private fun titleFor(type: ChallengeType): String = when (type) {
        ChallengeType.RAISE_BASELINE -> getString(R.string.pc_baseline_title)
        ChallengeType.STUDY_BREAK_BOOST -> getString(R.string.pc_study_title)
        ChallengeType.CAMPUS_EXPLORER -> getString(R.string.pc_campus_title)
    }

    private fun setProgress(bar: android.widget.ProgressBar, progress: Int, target: Int) {
        val max = if (target > 0) target else 1
        bar.max = max
        bar.progress = progress.coerceIn(0, max)
    }

    private fun muteCard(card: View, muted: Boolean) {
        card.alpha = if (muted) 0.6f else 1f
    }

    private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}