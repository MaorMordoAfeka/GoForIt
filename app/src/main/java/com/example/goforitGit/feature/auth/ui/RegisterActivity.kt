package com.example.goforitGit.feature.auth.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.navigation.MainActivity
import com.example.goforitGit.databinding.FeatureAuthRegisterBinding
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: FeatureAuthRegisterBinding
    private val registerInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = FeatureAuthRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            val pass = binding.etPassword.text?.toString().orEmpty()
            val confirm = binding.etConfirmPassword.text?.toString().orEmpty()

            binding.tilEmail.error = null
            binding.tilPassword.error = null
            binding.tilConfirmPassword.error = null

            var ok = true
            if (email.isBlank()) { binding.tilEmail.error = "Email is required"; ok = false }
            if (pass.isBlank()) { binding.tilPassword.error = "Password is required"; ok = false }
            if (confirm.isBlank()) { binding.tilConfirmPassword.error = "Confirm password is required"; ok = false }
            if (pass.isNotBlank() && confirm.isNotBlank() && pass != confirm) {
                binding.tilConfirmPassword.error = "Passwords do not match"
                ok = false
            }
            if (pass.length in 1..5) {
                binding.tilPassword.error = "Password must be at least 6 characters"
                ok = false
            }
            if (!ok) return@setOnClickListener

            registerOnce(email, pass)
        }

        binding.btnGoToLogin.setOnClickListener {
            finish() // back to LoginActivity
        }
    }

    private fun registerOnce(email: String, password: String) {
        lifecycleScope.launch {
            if (!registerInFlight.compareAndSet(false, true)) return@launch
            setLoading(true)

            try {
                val reg = FirebaseServerApi.register(email, password)
                reg.onFailure { e ->
                    Toast.makeText(this@RegisterActivity, "Register failed: ${e.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Optional but recommended: register FCM token now (user doc is created server-side anyway)
                runCatching { FirebaseServerApi.registerFcmTokenResult() }
                    .getOrNull()
                    ?.onFailure { e -> Log.e("FCM", "Token register failed: ${e.message}", e) }

                goToMain()
            } finally {
                setLoading(false)
                registerInFlight.set(false)
            }
        }
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