package com.example.goforitGit.feature.steps.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.R
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.core.data.StepsData.StepBus
import com.example.goforitGit.core.util.StepsUtils.StepCounterZC
import com.example.goforitGit.feature.challenges.ui.PersonalChallengesActivity
import com.example.goforitGit.feature.leaderboard.ui.LeaderboardActivity
import com.example.goforitGit.feature.map.ui.MapAndRoutesActivity
import com.example.goforitGit.feature.profile.ui.ProfileActivity
import com.example.goforitGit.feature.qa.QaAccess
import com.example.goforitGit.feature.qa.ui.QaActivity
import com.example.goforitGit.feature.statistics.ui.StatisticsActivity
import com.example.goforitGit.feature.steps.viewmodel.StepViewModel
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class StepsFragment : Fragment() {

    private val vm: StepViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.feature_steps_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vm.spm.observe(viewLifecycleOwner) { spm ->
            view.findViewById<TextView>(R.id.spmText).text =
                "SPM: ${spm.toInt()}"
        }

        vm.kmhLD.observe(viewLifecycleOwner) { speed ->
            val speedText = speed?.let { formatSpeed(it * 3.6f) } ?: "0.000"

            view.findViewById<TextView>(R.id.KmH).text =
                "Km/H: $speedText"
        }

        vm.steps.observe(viewLifecycleOwner) { steps ->
            view.findViewById<TextView>(R.id.count).text =
                "Steps: ${formatCount(steps)}"
        }

        vm.stepsToday.observe(viewLifecycleOwner) { todaySteps ->
            view.findViewById<TextView>(R.id.todayStepsVal).text =
                formatCount(todaySteps)

            updateDailyProgress(view, todaySteps)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            StepBus.mode.collect { mode ->
                updateStatus(view, mode)
            }
        }

        view.findViewById<MaterialCardView>(R.id.mapAndRoutesBtn)
            .setOnClickListener {
                startActivity(Intent(requireContext(), MapAndRoutesActivity::class.java))
            }

        view.findViewById<MaterialCardView>(R.id.statisticsBtn)
            .setOnClickListener {
                startActivity(Intent(requireContext(), StatisticsActivity::class.java))
            }

        view.findViewById<MaterialCardView>(R.id.leaderboardBtn)
            .setOnClickListener {
                startActivity(
                    LeaderboardActivity.createIntent(
                        context = requireContext(),
                        dayKey = null
                    )
                )
            }

        view.findViewById<MaterialCardView>(R.id.btnOpenProfile)
            .setOnClickListener {
                startActivity(Intent(requireContext(), ProfileActivity::class.java))
            }

        view.findViewById<MaterialCardView>(R.id.cardHomeProfileImage)
            .setOnClickListener {
                startActivity(Intent(requireContext(), ProfileActivity::class.java))
            }

        view.findViewById<MaterialCardView>(R.id.cardPersonalChallenges)
            .setOnClickListener {
                startActivity(Intent(requireContext(), PersonalChallengesActivity::class.java))
            }

        view.findViewById<MaterialCardView>(R.id.btnPersonalChallenges)
            .setOnClickListener {
                startActivity(PersonalChallengesActivity.createIntent(requireContext()))
            }

        view.findViewById<MaterialCardView>(R.id.btnQaTesting)
            .setOnClickListener {
                if (QaAccess.isAuthorized(FirebaseAuth.getInstance().currentUser)) {
                    startActivity(Intent(requireContext(), QaActivity::class.java))
                }
            }

        updateQaEntryVisibility(view)
    }

    private fun updateDailyProgress(view: View, todaySteps: Int) {
        val progressBar = view.findViewById<ProgressBar>(R.id.dailyProgressBar)

        progressBar.max = DAILY_GOAL
        progressBar.progress = todaySteps.coerceIn(0, DAILY_GOAL)

        val percentage = (todaySteps * 100 / DAILY_GOAL)
            .coerceIn(0, 100)

        view.findViewById<TextView>(R.id.tvGoalCaption).text =
            "$percentage% of ${formatCount(DAILY_GOAL)} goal"
    }

    private fun updateStatus(view: View, mode: StepCounterZC.MotionMode) {
        val pill = view.findViewById<LinearLayout>(R.id.statusPill)
        val icon = view.findViewById<ImageView>(R.id.ivStatusDot)
        val label = view.findViewById<TextView>(R.id.tvStatusPill)

        pill.setBackgroundResource(R.drawable.bg_status_pill_brand_mint)

        icon.setImageResource(modeIconRes(mode))

        ImageViewCompat.setImageTintList(
            icon,
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    R.color.text_purple_dark
                )
            )
        )

        label.text = "Current mode: ${mode.displayLabel()}"
    }

    private fun modeTintColor(
        mode: StepCounterZC.MotionMode
    ): Int {
        return when (mode) {
            StepCounterZC.MotionMode.WALKING,
            StepCounterZC.MotionMode.RUNNING,
            StepCounterZC.MotionMode.CYCLING -> R.color.status_ok

            else -> R.color.status_idle
        }
    }

    private fun modeIconRes(
        mode: StepCounterZC.MotionMode
    ): Int {
        return when (mode) {
            StepCounterZC.MotionMode.UNKNOWN ->
                R.drawable.ic_mode_unknown

            StepCounterZC.MotionMode.STATIONARY ->
                R.drawable.ic_mode_stationary

            StepCounterZC.MotionMode.STANDING_STILL ->
                R.drawable.ic_mode_standing_still

            StepCounterZC.MotionMode.WALKING ->
                R.drawable.ic_mode_walking

            StepCounterZC.MotionMode.RUNNING ->
                R.drawable.ic_mode_running

            StepCounterZC.MotionMode.CYCLING ->
                R.drawable.ic_mode_cycling

            StepCounterZC.MotionMode.DRIVING ->
                R.drawable.ic_mode_driving
        }
    }

    private fun StepCounterZC.MotionMode.displayLabel(): String {
        return when (this) {
            StepCounterZC.MotionMode.UNKNOWN -> "Unknown"
            StepCounterZC.MotionMode.STATIONARY -> "Stationary"
            StepCounterZC.MotionMode.STANDING_STILL -> "Standing"
            StepCounterZC.MotionMode.WALKING -> "Walking"
            StepCounterZC.MotionMode.RUNNING -> "Running"
            StepCounterZC.MotionMode.CYCLING -> "Cycling"
            StepCounterZC.MotionMode.DRIVING -> "Driving"
        }
    }

    override fun onResume() {
        super.onResume()
        loadProfileAvatar()
        view?.let(::updateQaEntryVisibility)
    }

    private fun updateQaEntryVisibility(root: View) {
        val qaCard = root.findViewById<MaterialCardView>(R.id.btnQaTesting)
        val authorized = QaAccess.isAuthorized(FirebaseAuth.getInstance().currentUser)

        qaCard.isVisible = authorized
        qaCard.isEnabled = authorized
    }

    private fun loadProfileAvatar() {
        val imageView =
            view?.findViewById<ImageView>(R.id.ivHomeProfileImage) ?: return

        if (FirebaseServerApi.currentUser() == null) {
            setDefaultAvatar(imageView)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            FirebaseServerApi.getMyProfileResult()
                .onSuccess { profile ->
                    val imageUrl = profile.profileImageUrl

                    if (imageUrl.isBlank()) {
                        setDefaultAvatar(imageView)
                        return@onSuccess
                    }

                    val bitmap = withContext(Dispatchers.IO) {
                        fetchBitmap(imageUrl)
                    }

                    if (bitmap != null) {
                        setBitmapAvatar(imageView, bitmap)
                    } else {
                        setDefaultAvatar(imageView)
                    }
                }
                .onFailure {
                    setDefaultAvatar(imageView)
                }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                doInput = true
            }

            connection.connect()

            if (connection.responseCode in 200..299) {
                connection.inputStream.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun setBitmapAvatar(
        imageView: ImageView,
        bitmap: Bitmap
    ) {
        imageView.clearColorFilter()
        ImageViewCompat.setImageTintList(imageView, null)
        imageView.setPadding(0, 0, 0, 0)
        imageView.setImageBitmap(bitmap)
    }

    private fun setDefaultAvatar(imageView: ImageView) {
        val padding = (12 * resources.displayMetrics.density).toInt()

        imageView.setPadding(padding, padding, padding, padding)
        imageView.setImageResource(R.drawable.ic_person)
        ImageViewCompat.setImageTintList(imageView, null)
        imageView.setColorFilter(Color.parseColor("#563078"))
    }

    private fun formatCount(value: Int): String {
        return String.format(Locale.US, "%,d", value)
    }

    private fun formatSpeed(value: Float): String {
        return String.format(Locale.US, "%.3f", value)
    }

    private companion object {
        const val DAILY_GOAL = 10_000
    }
}