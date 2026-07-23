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
import com.example.goforitGit.databinding.FeatureAuthLoginBinding
import com.example.goforitGit.navigation.MainActivity
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: FeatureAuthLoginBinding
    private val loginInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = FeatureAuthLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Firebase.auth.currentUser?.let {
            goToMain()
            return
        }

        binding.btnLogin.setOnClickListener {
            attemptLogin()
        }

        binding.btnGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.btnForgotPassword.setOnClickListener {
            sendPasswordReset()
        }
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        clearErrors()

        var ok = true

        if (email.isBlank()) {
            binding.tilEmail.error = "Email is required"
            ok = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email address"
            ok = false
        }

        if (password.isBlank()) {
            binding.tilPassword.error = "Password is required"
            ok = false
        }

        if (!ok) return

        loginAndRegisterOnce(email, password)
    }

    private fun sendPasswordReset() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()

        binding.tilEmail.error = null

        if (email.isBlank()) {
            binding.tilEmail.error = "Enter your email first"
            binding.etEmail.requestFocus()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email address"
            binding.etEmail.requestFocus()
            return
        }

        setLoading(true)

        Firebase.auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                setLoading(false)

                if (task.isSuccessful) {
                    Toast.makeText(
                        this,
                        "Password reset email sent to $email",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val message = task.exception?.message ?: "Failed to send reset email"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun loginAndRegisterOnce(email: String, password: String) {
        lifecycleScope.launch {
            if (!loginInFlight.compareAndSet(false, true)) {
                Log.d("AUTH", "Login already in progress - ignoring duplicate call")
                return@launch
            }

            setLoading(true)

            try {
                Firebase.auth.currentUser?.let { existing ->
                    Log.d("AUTH", "Already logged in: uid=${existing.uid}")
                    registerFcmTokenSafely()
                    proceedAfterAuth()
                    return@launch
                }

                val login = FirebaseServerApi.login(email, password)
                login.onFailure { e ->
                    Log.e("AUTH", "Login failed: ${e.message}", e)
                    Toast.makeText(
                        this@LoginActivity,
                        "Login failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val user = Firebase.auth.currentUser
                if (user == null) {
                    Log.e("AUTH", "Login finished but currentUser is null")
                    Toast.makeText(
                        this@LoginActivity,
                        "Login failed (no user)",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                Log.d("AUTH", "Logged in: uid=${user.uid}")

                registerFcmTokenSafely()
                proceedAfterAuth()

            } finally {
                setLoading(false)
                loginInFlight.set(false)
            }
        }
    }

    /**
     * Checks whether this device is allowed to be the account's active
     * device and routes accordingly. If a different device already holds
     * the account's trust, this device is refused: it signs back out of
     * Firebase Auth immediately (so a relaunch of the app can't slip past
     * this check via LoginActivity's auto-login branch) and shows an error
     * instead of entering the app. See FirebaseServerApi's DEVICE TRUST
     * section for the full flow.
     *
     * Fails open on a network/server error: a hiccup in this new check should
     * never strand a user who already presented valid credentials out of the
     * app entirely.
     */
    private suspend fun proceedAfterAuth() {
        val deviceId = DeviceIdentity.getOrCreateDeviceId(this)
        val deviceName = DeviceIdentity.getDeviceName()

        val trustCheck = FirebaseServerApi.checkDeviceTrustResult(deviceId, deviceName)

        trustCheck.onFailure { e ->
            Log.e("AUTH", "checkDeviceTrust failed, failing open: ${e.message}", e)
            goToMain()
        }

        val check = trustCheck.getOrNull() ?: return

        if (check.allowed) {
            goToMain()
        } else {
            Firebase.auth.signOut()
            Toast.makeText(
                this,
                "This account is already signed in on another device. Sign out there first, " +
                    "then try again here.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun registerFcmTokenSafely() {
        val tokenResult = runCatching { FirebaseServerApi.registerFcmTokenResult() }
            .getOrElse { e ->
                Log.e("FCM", "registerFcmTokenResult() call failed: ${e.message}", e)
                return
            }

        tokenResult
            .onSuccess { ok -> Log.d("FCM", "FCM token registered: $ok") }
            .onFailure { e -> Log.e("FCM", "FCM token registration failed: ${e.message}", e) }
    }

    private fun clearErrors() {
        binding.tilEmail.error = null
        binding.tilPassword.error = null
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnGoRegister.isEnabled = !loading
        binding.btnForgotPassword.isEnabled = !loading
        binding.etEmail.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}