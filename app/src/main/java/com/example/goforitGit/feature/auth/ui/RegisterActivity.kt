package com.example.goforitGit.feature.auth.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.core.util.DeviceSecurity.DeviceIdentity
import com.example.goforitGit.databinding.FeatureAuthRegisterBinding
import com.example.goforitGit.navigation.MainActivity
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: FeatureAuthRegisterBinding
    private val registerInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Firebase.auth.currentUser?.let {
            goToMain()
            return
        }

        binding = FeatureAuthRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener {
            attemptRegister()
        }

        binding.btnGoToLogin.setOnClickListener {
            finish()
        }
    }

    private fun attemptRegister() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val pass = binding.etPassword.text?.toString().orEmpty()
        val confirm = binding.etConfirmPassword.text?.toString().orEmpty()

        clearErrors()

        var ok = true

        if (email.isBlank()) {
            binding.tilEmail.error = "Email is required"
            ok = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email address"
            ok = false
        }

        if (pass.isBlank()) {
            binding.tilPassword.error = "Password is required"
            ok = false
        } else if (pass.length < 6) {
            binding.tilPassword.error = "Password must be at least 6 characters"
            ok = false
        }

        if (confirm.isBlank()) {
            binding.tilConfirmPassword.error = "Confirm password is required"
            ok = false
        } else if (pass != confirm) {
            binding.tilConfirmPassword.error = "Passwords do not match"
            ok = false
        }

        if (!ok) return

        registerOnce(email, pass)
    }

    private fun registerOnce(email: String, password: String) {
        lifecycleScope.launch {
            if (!registerInFlight.compareAndSet(false, true)) return@launch
            setLoading(true)

            try {
                val reg = FirebaseServerApi.register(email, password)
                reg.onFailure { e ->
                    Toast.makeText(
                        this@RegisterActivity,
                        "Register failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                runCatching { FirebaseServerApi.registerFcmTokenResult() }
                    .getOrNull()
                    ?.onFailure { e ->
                        Log.e("FCM", "Token register failed: ${e.message}", e)
                    }

                seedDeviceTrustSafely()
                goToMain()
            } finally {
                setLoading(false)
                registerInFlight.set(false)
            }
        }
    }

    /**
     * Records this device as the account's trusted device right after
     * registration. A brand-new account always allows silently (there's no
     * prior device to conflict with) — this just makes sure the trust record
     * exists from the start, so the very next login from a different device
     * is refused instead of slipping in unchallenged. See
     * FirebaseServerApi's DEVICE TRUST section for the full flow.
     */
    private suspend fun seedDeviceTrustSafely() {
        val deviceId = DeviceIdentity.getOrCreateDeviceId(this)
        val deviceName = DeviceIdentity.getDeviceName()

        runCatching { FirebaseServerApi.checkDeviceTrustResult(deviceId, deviceName) }
            .getOrNull()
            ?.onSuccess { check ->
                if (!check.allowed) {
                    Log.e("AUTH", "Unexpected device trust refusal right after registration")
                }
            }
            ?.onFailure { e ->
                Log.e("AUTH", "seedDeviceTrust failed: ${e.message}", e)
            }
    }

    private fun clearErrors() {
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !loading
        binding.btnGoToLogin.isEnabled = !loading
        binding.etEmail.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.etConfirmPassword.isEnabled = !loading
    }

    private fun goToMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }
}