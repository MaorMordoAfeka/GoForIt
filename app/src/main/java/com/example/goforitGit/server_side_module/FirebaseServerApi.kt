package com.example.goforitGit.server_side_module

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.tasks.await
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * FirebaseServerApi
 *
 * A single, centralized client wrapper for our Firebase backend.
 *
 * What this file is responsible for:
 * 1) Authentication (Email/Password) so callable Functions receive request.auth.uid
 * 2) Calling your callable Cloud Functions (registerFcmToken, uploadStepInterval, recordBonusVisit)
 * 3) Providing small helpers (current dayKey + 4-hour interval index)
 *
 * What this file is NOT responsible for:
 * - Calling scheduled server jobs (finalizeDay / finalizeMonth / dispatchNotificationJobs).
 *   Those run automatically on Firebase and are not called from Android.
 *
 * Typical flow:
 * - User logs in/registers -> registerFcmTokenResult()
 * - Every 4 hours -> uploadStepIntervalResult(...)
 * - On BLE validation -> recordBonusVisitResult(...)
 */
object FirebaseServerApi {

    /** Cloud Functions region MUST match Firebase Console (Functions) region. */
    private const val FUNCTIONS_REGION = "us-central1"

    /** Default timezone used for dayKey/interval calculations on the client. */
    private const val DEFAULT_TIMEZONE = "Asia/Jerusalem"

    /** Cloud Functions entry point bound to the chosen region. */
    private val functions: FirebaseFunctions by lazy { Firebase.functions(FUNCTIONS_REGION) }

    /**
     * Minimal auth info that the UI can use after login/register.
     *
     * Why this exists:
     * - Lets you confirm success and immediately access uid/email without re-reading FirebaseAuth state.
     */
    data class AuthInfo(
        val uid: String,
        val email: String?,
    )

    // -------------------------------------------------------------------------
    // AUTH (Email/Password)
    // -------------------------------------------------------------------------

    /**
     * Returns the currently signed-in Firebase user, or null if signed out.
     *
     * Use this:
     * - If you want to gate screens/logic based on "is logged in?"
     * - Before calling functions, if you want to avoid predictable failures.
     */
    fun currentUser(): FirebaseUser? = Firebase.auth.currentUser

    /**
     * Signs out the current Firebase user.
     *
     * Side effects:
     * - All callable Functions that require auth will fail until the user logs in again.
     * - After logging in as a different user, call registerFcmTokenResult() again so push delivery
     *   is mapped under the correct uid on the server.
     */
    fun signOut() {
        Firebase.auth.signOut()
    }

    /**
     * Logs in an existing user with Email/Password.
     *
     * Returns:
     * - Result.success(AuthInfo) if authentication succeeds.
     * - Result.failure(error) if authentication fails (wrong password, user not found, network, etc.).
     *
     * Recommended next step (on success):
     * - Call registerFcmTokenResult() so the server can deliver push notifications to this device.
     */
    suspend fun login(email: String, password: String): Result<AuthInfo> = runCatching {
        val result = Firebase.auth.signInWithEmailAndPassword(email, password).await()
        val user = result.user ?: error("Login succeeded but FirebaseUser is null.")
        AuthInfo(uid = user.uid, email = user.email)
    }

    /**
     * Creates a new user with Email/Password.
     *
     * Returns:
     * - Result.success(AuthInfo) if registration succeeds.
     * - Result.failure(error) if registration fails (email already used, weak password, etc.).
     *
     * Recommended next step (on success):
     * - Call registerFcmTokenResult() so this device can receive reminders via FCM.
     */
    suspend fun register(email: String, password: String): Result<AuthInfo> = runCatching {
        val result = Firebase.auth.createUserWithEmailAndPassword(email, password).await()
        val user = result.user ?: error("Register succeeded but FirebaseUser is null.")
        AuthInfo(uid = user.uid, email = user.email)
    }

    // -------------------------------------------------------------------------
    // INTERNAL HELPERS (callable functions)
    // -------------------------------------------------------------------------

    /**
     * Calls a callable Function and converts its output into Result<Boolean>.
     *
     * Expected server response shape (recommended):
     * - { ok: true } or { ok: false }
     *
     * Fallback behavior:
     * - If the call succeeds but the payload does not contain "ok", we treat it as success (true).
     *
     * Returns:
     * - Result.success(true/false) if the call completed successfully.
     * - Result.failure(error) if the call failed (unauthenticated, network, permission, validation, etc.).
     */
    private suspend fun callOkResult(
        functionName: String,
        data: Map<String, Any?>,
    ): Result<Boolean> = runCatching {
        val res = functions.getHttpsCallable(functionName).call(data).await()
        val map = res.data as? Map<*, *>
        (map?.get("ok") as? Boolean) ?: true
    }

    /**
     * Ensures that a user is logged in before calling server endpoints that require auth.
     *
     * Returns:
     * - Result.success(uid) if a user is logged in.
     * - Result.failure(...) if no user is logged in.
     */
    private fun requireUid(): Result<String> {
        val uid = Firebase.auth.currentUser?.uid
        return if (uid != null) Result.success(uid)
        else Result.failure(IllegalStateException("Not signed in. Call login/register first."))
    }

    // -------------------------------------------------------------------------
    // PUSH NOTIFICATIONS (FCM token registration)
    // -------------------------------------------------------------------------

    /**
     * Registers this device's current FCM token on the server under the current user.
     *
     * What an FCM token is:
     * - A unique identifier that represents "this app install on this device" for push delivery.
     * - It can rotate (reinstall, clear data, OS changes), so re-registering is normal.
     *
     * Why this is required for your project:
     * - Your server schedules reminder jobs and dispatches notifications.
     * - To deliver a push, the server needs a destination token stored under:
     *     users/{uid}/fcm_tokens/{token}
     * - If no tokens exist for the user, server dispatch will mark NO_TOKENS and nothing will arrive.
     *
     * When to call:
     * - Immediately after a successful login/register (recommended).
     * - On every app start (safe for a PoC; idempotent on the server).
     *
     * Returns:
     * - Result.success(true/false) based on server response (usually true).
     * - Result.failure(...) if not signed in, token fetch fails, or the callable fails.
     *
     * Android usage:
     * lifecycleScope.launch {
     *     val r = FirebaseServerApi.registerFcmTokenResult()
     *     r.onSuccess { ok -> Log.d("FCM", "Token registered: $ok") }
     *      .onFailure { e -> Log.e("FCM", "Token register failed: ${e.message}", e) }
     * }
     *
     * Verify in Firestore:
     * users/{uid}/fcm_tokens/{token} exists.
     */
    suspend fun registerFcmTokenResult(): Result<Boolean> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure)
            return Result.failure(uidCheck.exceptionOrNull()!!)

        return runCatching {
            val token = Firebase.messaging.token.await()
            callOkResult("registerFcmToken", mapOf("token" to token)).getOrThrow()
        }
    }

    // -------------------------------------------------------------------------
    // STEPS (4-hour buckets / "sixths")
    // -------------------------------------------------------------------------

    /**
     * Uploads step totals for a given day and 4-hour interval.
     *
     * Server-side intent:
     * - Save the user's steps per interval ("sixth") so the backend can:
     *   - Determine the best interval at end-of-day
     *   - Learn monthly patterns
     *   - Schedule reminders in low-activity intervals (outside 00:00–06:00)
     *
     * Parameters:
     * - dayKey: "YYYY-MM-DD"
     * - intervalIndex: 0..5 (4-hour intervals)
     * - stepsTotal: steps attributed to that interval
     * - uploadIntervalIndex: which interval the upload occurred in (usually same as intervalIndex)
     * - attributedIntervalIndex: which interval the steps belong to (useful for late/batched attribution)
     *
     * Returns:
     * - Result.success(true/false) based on server {ok}.
     * - Result.failure(...) if not signed in or the server rejects the payload/call.
     *
     * Android usage upload “now”:
     * lifecycleScope.launch {
     *     val (dayKey, intervalIndex) = FirebaseServerApi.currentDayKeyAndInterval()
     *     val r = FirebaseServerApi.uploadStepIntervalResult(
     *         dayKey = dayKey,
     *         intervalIndex = intervalIndex,
     *         stepsTotal = stepsInThisInterval
     *     )
     *     r.onSuccess { ok -> Log.d("STEPS", "Uploaded: $ok") }
     *      .onFailure { e -> Log.e("STEPS", "Upload failed: ${e.message}", e) }
     * }
     *
     * Android usage upload “Batch” (multiple intervals):
     * lifecycleScope.launch {
     *     val dayKey = "2026-02-16"
     *     val stepsByInterval = listOf(120, 340, 560, 200, 0, 90)
     *
     *     for (i in 0..5) {
     *         val r = FirebaseServerApi.uploadStepIntervalResult(dayKey, i, stepsByInterval[i])
     *         if (r.isFailure) {
     *             Log.e("STEPS", "Interval $i failed: ${r.exceptionOrNull()?.message}")
     *         }
     *     }
     * }
     *
     * Verify in Firestore:
     * users/{uid}/step_sessions/{dayKey_intervalIndex}
     * users/{uid}/daily/{dayKey} (aggregate updated)
     */
    suspend fun uploadStepIntervalResult(
        dayKey: String,
        intervalIndex: Int,
        stepsTotal: Int,
        uploadIntervalIndex: Int = intervalIndex,
        attributedIntervalIndex: Int = intervalIndex,
    ): Result<Boolean> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) return Result.failure(uidCheck.exceptionOrNull()!!)

        val data = mapOf(
            "dayKey" to dayKey,
            "intervalIndex" to intervalIndex,
            "stepsTotal" to stepsTotal,
            "uploadIntervalIndex" to uploadIntervalIndex,
            "attributedIntervalIndex" to attributedIntervalIndex,
        )
        return callOkResult("uploadStepInterval", data)
    }

    /**
     * Convenience wrapper: uploads steps for the *current* day + current 4-hour interval.
     * Use this when you are uploading instantly/"now" (most common case).
     */
    suspend fun uploadCurrentIntervalStepsResult(
        stepsTotal: Int,
        timezone: String = DEFAULT_TIMEZONE
    ): Result<Boolean> {
        val (dayKey, intervalIndex) = currentDayKeyAndInterval(timezone)
        return uploadStepIntervalResult(
            dayKey = dayKey,
            intervalIndex = intervalIndex,
            stepsTotal = stepsTotal
        )
    }

    /**
     * Computes:
     * - dayKey in "YYYY-MM-DD"
     * - intervalIndex in 0..5 (4-hour buckets)
     *
     * Interval mapping:
     * 0: 00–04
     * 1: 04–08
     * 2: 08–12
     * 3: 12–16
     * 4: 16–20
     * 5: 20–24
     *
     * Use this:
     * - Right before uploading steps, to decide which "sixth" you are currently in.
     */
    fun currentDayKeyAndInterval(timezone: String = DEFAULT_TIMEZONE): Pair<String, Int> {
        val now = ZonedDateTime.now(ZoneId.of(timezone))
        val dayKey = now.toLocalDate().toString()
        val intervalIndex = now.hour / 4
        return dayKey to intervalIndex
    }

    // -------------------------------------------------------------------------
    // BONUS VISITS (BLE validated)
    // -------------------------------------------------------------------------

    /**
     * Records a bonus station visit on the server (after BLE validation).
     *
     * Server-side intent:
     * - Mark that the user achieved a bonus checkpoint for the day.
     * - Update daily state (didBonus flag, bonus points) so leaderboards and summaries can use it.
     *
     * Parameters:
     * - stationId: the server-side station identifier (must exist in Firestore as a valid station)
     * - visitedAtMs: timestamp in epoch millis; defaults to "now"
     *
     * Returns:
     * - Result.success(true/false) based on server {ok}.
     * - Result.failure(...) if not signed in or the server rejects the payload/call.
     *
     * Android usage:
     * lifecycleScope.launch {
     *     val r = FirebaseServerApi.recordBonusVisitResult(stationId = "S01")
     *     r.onSuccess { ok ->
     *         if (ok) Log.d("BONUS", "Bonus awarded ✅")
     *         else Log.d("BONUS", "Bonus already claimed today")
     *     }.onFailure { e ->
     *         Log.e("BONUS", "Bonus failed: ${e.message}", e)
     *     }
     * }
     *
     * Verify in Firestore:
     * users/{uid}/bonus_visits/{dayKey} (since “one per day”)
     * users/{uid}/daily/{dayKey} has didBonus=true and bonus points updated
     *
     */
    suspend fun recordBonusVisitResult(
        stationId: String,
        visitedAtMs: Long = System.currentTimeMillis(),
    ): Result<Boolean> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure)
            return Result.failure(uidCheck.exceptionOrNull()!!)

        val data = mapOf(
            "stationId" to stationId,
            "visitedAtMs" to visitedAtMs,
        )
        return callOkResult("recordBonusVisit", data)
    }
}
