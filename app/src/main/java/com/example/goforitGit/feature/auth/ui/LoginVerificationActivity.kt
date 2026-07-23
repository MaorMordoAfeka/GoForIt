package com.example.goforitGit.feature.auth.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.databinding.FeatureAuthLoginVerificationBinding
import com.example.goforitGit.navigation.MainActivity
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocks access to the app until the 6-digit code pushed to the account's
 * already-trusted device is entered here. Reached only from LoginActivity
 * when checkDeviceTrustResult() returns "verification_required" — see
 * FirebaseServerApi's DEVICE TRUST section for the full flow.
 */
class LoginVerificationActivity : AppCompatActivity() {

    private lateinit var binding: FeatureAuthLoginVerificationBinding
    private var verificationId: String = ""
    private var countDownTimer: CountDownTimer? = null
    private val confirmInFlight = AtomicBoolean(false)

    companion object {
        const val EXTRA_VERIFICATION_ID = "extra_verification_id"
        const val EXTRA_EXPIRES_AT_MS = "extra_expires_at_ms"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = FeatureAuthLoginVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        verificationId = intent.getStringExtra(EXTRA_VERIFICATION_ID).orEmpty()
        val expiresAtMs = intent.getLongExtra(EXTRA_EXPIRES_AT_MS, 0L)

        if (verificationId.isBlank()) {
            Log.e("AUTH", "LoginVerificationActivity started without a verificationId")
            goBackToLogin()
            return
        }

        startCountdown(expiresAtMs)

        binding.btnConfirm.setOnClickListener {
            attemptConfirm()
        }

        binding.btnNotMe.setOnClickListener {
            signOutAndGoToLogin()
        }
    }

    private fun startCountdown(expiresAtMs: Long) {
        val remaining = expiresAtMs - System.currentTimeMillis()
        if (remaining <= 0) {
            markExpired()
            return
        }

        countDownTimer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                binding.tvCountdown.text =
                    String.format(Locale.US, "Code expires in %d:%02d", minutes, seconds)
            }

            override fun onFinish() {
                markExpired()
            }
        }.start()
    }

    private fun markExpired() {
        binding.tvCountdown.text = "Code expired — go back and sign in again"
        binding.etCode.isEnabled = false
        binding.btnConfirm.isEnabled = false
    }

    private fun attemptConfirm() {
        val code = binding.etCode.text?.toString()?.trim().orEmpty()

        binding.tilCode.error = null
        if (!Regex("^\\d{6}$").matches(code)) {
            binding.tilCode.error = "Enter the 6-digit code"
            return
        }

        lifecycleScope.launch {
            if (!confirmInFlight.compareAndSet(false, true)) return@launch

            setLoading(true)
            try {
                val result = FirebaseServerApi.confirmLoginVerificationResult(verificationId, code)

                result.onFailure { e ->
                    Log.e("AUTH", "confirmLoginVerification failed: ${e.message}", e)
                    Toast.makeText(
                        this@LoginVerificationActivity,
                        "Couldn't verify that code: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val verification = result.getOrNull() ?: return@launch
                when (verification.status) {
                    "trusted" -> goToMain()

                    "invalid_code" -> {
                        val remaining = verification.attemptsRemaining
                        binding.tilCode.error = if (remaining != null && remaining > 0) {
                            "Wrong code — $remaining attempt(s) left"
                        } else {
                            "Wrong code"
                        }
                    }

                    "expired" -> markExpired()

                    else -> Log.e("AUTH", "Unexpected verification status: ${verification.status}")
                }
            } finally {
                setLoading(false)
                confirmInFlight.set(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConfirm.isEnabled = !loading
        binding.btnNotMe.isEnabled = !loading
        binding.etCode.isEnabled = !loading
    }

    private fun signOutAndGoToLogin() {
        FirebaseServerApi.signOut()
        goBackToLogin()
    }

    private fun goBackToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}
