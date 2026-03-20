package com.example.goforitGit.feature.auth.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.navigation.MainActivity
import com.example.goforitGit.databinding.FeatureAuthLoginBinding
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean


// TODO
//  the code connects the "app user" to the firebase server,
//  there should be an registerActivity
//  in order to execute every code that involves firebase functions
//  Whether the function is callable or scheduled the user must be registered and logged in

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: FeatureAuthLoginBinding

    // Single-flight gate: prevents double sign-in
    private val loginInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = FeatureAuthLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // If already signed in → go straight to MainActivity
        Firebase.auth.currentUser?.let {
            goToMain()
            return
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()

            // Basic validation
            binding.tilEmail.error = null
            binding.tilPassword.error = null

            var ok = true
            if (email.isBlank()) {
                binding.tilEmail.error = "Email is required"
                ok = false
            }
            if (password.isBlank()) {
                binding.tilPassword.error = "Password is required"
                ok = false
            }
            if (!ok) return@setOnClickListener

            loginAndRegisterOnce(email, password)
        }

        binding.btnGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun loginAndRegisterOnce(email: String, password: String) {
        lifecycleScope.launch {
            // Prevent concurrent / duplicate runs
            if (!loginInFlight.compareAndSet(false, true)) {
                Log.d("AUTH", "Login already in progress - ignoring duplicate call")
                return@launch
            }

            setLoading(true)

            try {
                // If already signed in (race / fast back-stack), don't sign in again
                Firebase.auth.currentUser?.let { existing ->
                    Log.d("AUTH", "Already logged in: uid=${existing.uid}")
                    registerFcmTokenSafely()
                    goToMain()
                    return@launch
                }

                // 1) Login (Email/Password) -> Result<Unit>
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
                    Log.e(
                        "AUTH",
                        "Login finished but currentUser is null (check FirebaseServerApi.login implementation)"
                    )
                    Toast.makeText(this@LoginActivity, "Login failed (no user)", Toast.LENGTH_LONG)
                        .show()
                    return@launch
                }

                Log.d("AUTH", "Logged in: uid=${user.uid}")

                // 2) Register FCM token (Result<Boolean>)
                registerFcmTokenSafely()

                // 3) Navigate
                goToMain()

            } finally {
                setLoading(false)
                loginInFlight.set(false)
            }
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

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.etEmail.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            // Clear Login from back stack
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}