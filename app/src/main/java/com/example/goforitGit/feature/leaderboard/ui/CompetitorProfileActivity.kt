package com.example.goforitGit.feature.leaderboard.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.R
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.databinding.FeatureCompetitorProfileActivityBinding
import com.example.goforitGit.feature.leaderboard.model.LeaderboardEntry
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

class CompetitorProfileActivity : AppCompatActivity() {

    private lateinit var binding: FeatureCompetitorProfileActivityBinding

    private val auth by lazy { FirebaseAuth.getInstance() }

    private var entry: LeaderboardEntry = LeaderboardEntry()
    private var dayKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        binding = FeatureCompetitorProfileActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        entry = readEntryFromIntent()
        dayKey = intent.getStringExtra(EXTRA_DAY_KEY).orEmpty()

        if (entry.uid.isBlank()) {
            toast("Competitor profile is unavailable.")
            finish()
            return
        }

        setupToolbar()
        renderSnapshot(entry)
        loadPublicProfileIfAvailable(entry.uid)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.toolbar.title = if (isCurrentUser(entry.uid)) {
            "Your Public Profile"
        } else {
            "Competitor Profile"
        }
    }

    private fun renderSnapshot(item: LeaderboardEntry) {
        val isYou = isCurrentUser(item.uid)
        val fmt = NumberFormat.getInstance(Locale.getDefault())

        val username = item.username.ifBlank {
            if (isYou) "You" else "Loading profile…"
        }

        binding.tvYouChip.isVisible = isYou
        binding.tvHeaderName.text = username

        binding.tvDepartment.text = item.faculty.ifBlank { "General" }

        binding.tvRankValue.text = if (item.rank > 0) "#${item.rank}" else "—"
        binding.tvPointsValue.text = fmt.format(item.totalPoints)
        binding.tvStepsValue.text = fmt.format(item.totalSteps)
        binding.tvBonusValue.text = fmt.format(item.bonusPoints)

        binding.tvFacultyValue.text = item.faculty.ifBlank { "General" }
        binding.tvDayValue.text = dayKey.ifBlank { "Current leaderboard" }

        binding.tvProfileSource.text = if (item.username.isNotBlank() || item.profileImageUrl.isNotBlank()) {
            "Public profile loaded."
        } else {
            "Loading public profile."
        }

        renderProfileImage(item.profileImageUrl)
        applyRankAccent(item.rank)
    }

    private fun loadPublicProfileIfAvailable(uid: String) {
        setLoading(true)

        lifecycleScope.launch {
            val result = FirebaseServerApi.getPublicUserProfileResult(uid)

            result
                .onSuccess { profile ->
                    val merged = entry.copy(
                        username = profile.username.ifBlank { entry.username },
                        profileImageUrl = profile.profileImageUrl.ifBlank { entry.profileImageUrl },
                        faculty = profile.faculty.ifBlank { entry.faculty }
                    )
                    entry = merged
                    renderSnapshot(merged)
                }
                .onFailure { e ->
                    Log.w(TAG, "Failed to load public profile for uid=$uid", e)
                    if (entry.username.isBlank()) {
                        entry = entry.copy(username = if (isCurrentUser(uid)) "You" else "Unknown user")
                        renderSnapshot(entry)
                    }
                    binding.tvProfileSource.text = "Public profile is unavailable."
                }

            setLoading(false)
        }
    }

    private fun applyRankAccent(rank: Int) {
        val accent = when (rank) {
            1 -> Color.parseColor("#4FB36A")
            2 -> Color.parseColor("#6F788E")
            3 -> Color.parseColor("#C9824A")
            else -> Color.parseColor("#883FA8")
        }

        binding.tvRankValue.setTextColor(accent)
        binding.cardAvatar.strokeColor = accent
        binding.cardRankBadge.setCardBackgroundColor(accent)
        binding.tvRankBadge.text = if (rank > 0) "#$rank" else "—"
    }

    private fun renderProfileImage(url: String) {
        if (url.isBlank()) {
            setDefaultProfileImage()
            return
        }

        lifecycleScope.launch {
            val bitmap = loadRemoteImage(url)
            if (bitmap != null) {
                setBitmapProfileImage(bitmap)
            } else {
                setDefaultProfileImage()
            }
        }
    }

    private suspend fun loadRemoteImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.doInput = true
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Profile image fetch failed: HTTP $code for $url")
                null
            } else {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Profile image fetch failed for $url", e)
            null
        }
    }

    private fun setBitmapProfileImage(bitmap: Bitmap) {
        binding.ivProfileImage.clearColorFilter()
        ImageViewCompat.setImageTintList(binding.ivProfileImage, null)
        binding.ivProfileImage.setPadding(0, 0, 0, 0)
        binding.ivProfileImage.setImageBitmap(bitmap)
    }

    private fun setDefaultProfileImage() {
        val pad = (22 * resources.displayMetrics.density).toInt()
        binding.ivProfileImage.setPadding(pad, pad, pad, pad)
        binding.ivProfileImage.setImageResource(R.drawable.ic_person)
        ImageViewCompat.setImageTintList(binding.ivProfileImage, null)
        binding.ivProfileImage.setColorFilter(Color.parseColor("#7C39A0"))
    }

    private fun readEntryFromIntent(): LeaderboardEntry {
        return LeaderboardEntry(
            uid = intent.getStringExtra(EXTRA_UID).orEmpty(),
            rank = intent.getIntExtra(EXTRA_RANK, 0),
            totalPoints = intent.getIntExtra(EXTRA_TOTAL_POINTS, 0),
            totalSteps = intent.getIntExtra(EXTRA_TOTAL_STEPS, 0),
            bonusPoints = intent.getIntExtra(EXTRA_BONUS_POINTS, 0),
            faculty = intent.getStringExtra(EXTRA_FACULTY).orEmpty(),
            username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
            profileImageUrl = intent.getStringExtra(EXTRA_PROFILE_IMAGE_URL).orEmpty()
        )
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
    }

    private fun isCurrentUser(uid: String): Boolean {
        return auth.currentUser?.uid == uid
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "CompetitorProfile"

        private const val EXTRA_DAY_KEY = "extra_day_key"
        private const val EXTRA_UID = "extra_uid"
        private const val EXTRA_RANK = "extra_rank"
        private const val EXTRA_TOTAL_POINTS = "extra_total_points"
        private const val EXTRA_TOTAL_STEPS = "extra_total_steps"
        private const val EXTRA_BONUS_POINTS = "extra_bonus_points"
        private const val EXTRA_FACULTY = "extra_faculty"
        private const val EXTRA_USERNAME = "extra_username"
        private const val EXTRA_PROFILE_IMAGE_URL = "extra_profile_image_url"

        fun createIntent(
            context: Context,
            entry: LeaderboardEntry,
            dayKey: String
        ): Intent {
            return Intent(context, CompetitorProfileActivity::class.java).apply {
                putExtra(EXTRA_DAY_KEY, dayKey)
                putExtra(EXTRA_UID, entry.uid)
                putExtra(EXTRA_RANK, entry.rank)
                putExtra(EXTRA_TOTAL_POINTS, entry.totalPoints)
                putExtra(EXTRA_TOTAL_STEPS, entry.totalSteps)
                putExtra(EXTRA_BONUS_POINTS, entry.bonusPoints)
                putExtra(EXTRA_FACULTY, entry.faculty)
                putExtra(EXTRA_USERNAME, entry.username)
                putExtra(EXTRA_PROFILE_IMAGE_URL, entry.profileImageUrl)
            }
        }
    }
}
