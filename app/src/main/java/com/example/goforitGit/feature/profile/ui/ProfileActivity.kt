package com.example.goforitGit.feature.profile.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.databinding.FeatureProfileActivityBinding
import kotlinx.coroutines.launch
import java.time.ZoneId

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: FeatureProfileActivityBinding

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
        setupStaticUserInfo()
        setupSliders()
        setupActions()

        loadProfile()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupStaticUserInfo() {
        val user = FirebaseServerApi.currentUser()
        binding.tvEmailValue.text = user?.email?.takeIf { it.isNotBlank() } ?: "-"
        binding.tvUidValue.text = user?.uid ?: "-"
    }

    private fun setupSliders() {
        binding.sliderQuietStart.addOnChangeListener { _, _, _ -> renderQuietHoursLabels() }
        binding.sliderQuietEnd.addOnChangeListener { _, _, _ -> renderQuietHoursLabels() }
        renderQuietHoursLabels()
    }

    private fun setupActions() {
        binding.btnRefresh.setOnClickListener {
            loadProfile()
        }

        binding.btnSave.setOnClickListener {
            saveProfile()
        }
    }

    private fun loadProfile() {
        setBusy(true)

        lifecycleScope.launch {
            FirebaseServerApi.getMyProfileResult()
                .onSuccess { profile ->
                    binding.etFaculty.setText(profile.faculty)
                    binding.etTimezone.setText(profile.timezone)
                    binding.switchLowActivityNudges.isChecked = profile.lowActivityNudgeEnabled
                    binding.sliderQuietStart.value = profile.quietHoursStartHour.toFloat()
                    binding.sliderQuietEnd.value = profile.quietHoursEndHour.toFloat()
                    renderQuietHoursLabels()
                }
                .onFailure { e ->
                    toast(e.message ?: "Failed to load profile.")
                }

            setBusy(false)
        }
    }

    private fun saveProfile() {
        val timezone = binding.etTimezone.text?.toString()?.trim().orEmpty()
            .ifBlank { "Asia/Jerusalem" }

        if (!isValidTimezone(timezone)) {
            binding.tilTimezone.error = "Use a valid IANA timezone, for example Asia/Jerusalem"
            return
        } else {
            binding.tilTimezone.error = null
        }

        val faculty = binding.etFaculty.text?.toString()?.trim().orEmpty()

        val profile = FirebaseServerApi.UserProfile(
            timezone = timezone,
            lowActivityNudgeEnabled = binding.switchLowActivityNudges.isChecked,
            preferredActiveInterval = null,
            preferredInactiveInterval = null,
            quietHoursStartHour = binding.sliderQuietStart.value.toInt(),
            quietHoursEndHour = binding.sliderQuietEnd.value.toInt(),
            faculty = faculty,
            cumulativeTotalPoints = 0,
            cumulativeTotalSteps = 0,
            cumulativeBonusPoints = 0,
        )

        setBusy(true)

        lifecycleScope.launch {
            FirebaseServerApi.updateMyProfileResult(profile)
                .onSuccess { updated ->
                    binding.etFaculty.setText(updated.faculty)
                    binding.etTimezone.setText(updated.timezone)
                    binding.switchLowActivityNudges.isChecked = updated.lowActivityNudgeEnabled
                    binding.sliderQuietStart.value = updated.quietHoursStartHour.toFloat()
                    binding.sliderQuietEnd.value = updated.quietHoursEndHour.toFloat()
                    renderQuietHoursLabels()
                    toast("Profile updated successfully.")
                }
                .onFailure { e ->
                    toast(e.message ?: "Failed to update profile.")
                }

            setBusy(false)
        }
    }

    private fun renderQuietHoursLabels() {
        val start = binding.sliderQuietStart.value.toInt()
        val end = binding.sliderQuietEnd.value.toInt()

        binding.tvQuietStartValue.text = hourLabel(start)
        binding.tvQuietEndValue.text = hourLabel(end)
        binding.tvQuietSummary.text = "Quiet hours: ${hourLabel(start)} → ${hourLabel(end)}"
    }

    private fun hourLabel(hour: Int): String = String.format("%02d:00", hour)

    private fun isValidTimezone(value: String): Boolean {
        return runCatching { ZoneId.of(value) }.isSuccess
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy

        val enabled = !busy
        val controls = listOf<View>(
            binding.btnRefresh,
            binding.btnSave,
            binding.etFaculty,
            binding.etTimezone,
            binding.switchLowActivityNudges,
            binding.sliderQuietStart,
            binding.sliderQuietEnd
        )
        controls.forEach { it.isEnabled = enabled }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}