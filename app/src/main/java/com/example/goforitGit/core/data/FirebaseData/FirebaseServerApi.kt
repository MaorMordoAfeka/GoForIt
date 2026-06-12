package com.example.goforitGit.core.data.FirebaseData

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.google.firebase.messaging.messaging
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.collections.get

/**
 * FirebaseServerApi
 *
 * Centralized client wrapper for Firebase Auth + callable Cloud Functions.
 *
 * Responsibilities:
 * 1) Authentication (Email/Password)
 * 2) Calling callable Functions:
 *    - registerFcmToken
 *    - getMyProfile
 *    - updateMyProfile
 *    - updateQuietHours
 *    - uploadStepInterval
 *    - recordBonusVisit
 *    - syncCollegeAreaSteps
 * 3) Providing small helpers for current dayKey + current 4-hour interval
 *
 * Not responsible for:
 * - Calling scheduled server jobs (finalizeDay / finalizeMonth / dispatchNotificationJobs).
 *   Those run automatically on Firebase.
 */
object FirebaseServerApi {

    /** Cloud Functions region MUST match Firebase Console. */
    private const val FUNCTIONS_REGION = "us-central1"

    /** Default timezone used for dayKey / interval calculations on the client. */
    private const val DEFAULT_TIMEZONE = "Asia/Jerusalem"

    /** Default quiet-hours profile for a typical user. */
    const val DEFAULT_QUIET_HOURS_START = 22
    const val DEFAULT_QUIET_HOURS_END = 8

    /** Cloud Functions entry point bound to the chosen region. */
    private val functions: FirebaseFunctions by lazy { Firebase.functions(FUNCTIONS_REGION) }
    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }

    /**
     * Minimal auth info returned after login/register.
     */
    data class AuthInfo(
        val uid: String,
        val email: String?,
    )

    /**
     * Quiet-hours payload returned by the server after updateQuietHours.
     */
    data class QuietHoursInfo(
        val quietHoursStartHour: Int,
        val quietHoursEndHour: Int,
    )

    /**
     * Full editable user profile returned by profile callables.
     */
    data class UserProfile(
        val username: String,
        val email: String,
        val profileImageUrl: String,
        val timezone: String,
        val lowActivityNudgeEnabled: Boolean,
        val preferredActiveInterval: Int?,
        val preferredInactiveInterval: Int?,
        val quietHoursStartHour: Int,
        val quietHoursEndHour: Int,
        val faculty: String,
        val cumulativeTotalPoints: Int,
        val cumulativeTotalSteps: Int,
        val cumulativeBonusPoints: Int,
    )

    /**
     * Public-safe profile fields used by leaderboard and competitor profile screens.
     * Do not add email, timezone, quiet hours, notification settings, or other private data here.
     */
    data class PublicUserProfile(
        val uid: String,
        val username: String,
        val profileImageUrl: String,
        val faculty: String,
    )

    // -------------------------------------------------------------------------
    // AUTH (Email/Password)
    // -------------------------------------------------------------------------

    /**
     * Returns the currently signed-in Firebase user, or null if signed out.
     */
    fun currentUser(): FirebaseUser? = Firebase.auth.currentUser

    /**
     * Signs out the current Firebase user.
     */
    fun signOut() {
        Firebase.auth.signOut()
    }

    /**
     * Logs in an existing user with Email/Password.
     */
    suspend fun login(email: String, password: String): Result<AuthInfo> = runCatching {
        val result = Firebase.auth.signInWithEmailAndPassword(email, password).await()
        val user = result.user ?: error("Login succeeded but FirebaseUser is null.")
        AuthInfo(uid = user.uid, email = user.email)
    }

    /**
     * Creates a new user with Email/Password.
     */
    suspend fun register(email: String, password: String): Result<AuthInfo> = runCatching {
        val result = Firebase.auth.createUserWithEmailAndPassword(email, password).await()
        val user = result.user ?: error("Register succeeded but FirebaseUser is null.")
        AuthInfo(uid = user.uid, email = user.email)
    }

    // -------------------------------------------------------------------------
    // INTERNAL HELPERS
    // -------------------------------------------------------------------------

    /**
     * Calls a callable Function and converts its output into Result<Boolean>.
     *
     * Expected payload:
     * - { ok: true } or { ok: false }
     *
     * Fallback:
     * - If the call succeeds and "ok" is missing, treat as success(true).
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
     * Ensures that a user is signed in before calling auth-protected functions.
     */
    private fun requireUid(): Result<String> {
        val uid = Firebase.auth.currentUser?.uid
        return if (uid != null) Result.success(uid)
        else Result.failure(IllegalStateException("Not signed in. Call login/register first."))
    }

    /**
     * Validates a quiet-hours hour value on the client before sending to the server.
     */
    private fun requireValidHour(value: Int, name: String) {
        require(value in 0..23) { "$name must be between 0 and 23." }
    }

    /**
     * Converts a callable response map into UserProfile with safe defaults.
     */
    private fun mapToUserProfile(map: Map<*, *>): UserProfile {
        return UserProfile(
            username = map["username"] as? String ?: "",
            email = map["email"] as? String ?: "",
            profileImageUrl = map["profileImageUrl"] as? String ?: "",
            timezone = map["timezone"] as? String ?: DEFAULT_TIMEZONE,
            lowActivityNudgeEnabled = map["lowActivityNudgeEnabled"] as? Boolean ?: true,
            preferredActiveInterval = (map["preferredActiveInterval"] as? Number)?.toInt(),
            preferredInactiveInterval = (map["preferredInactiveInterval"] as? Number)?.toInt(),
            quietHoursStartHour = (map["quietHoursStartHour"] as? Number)?.toInt()
                ?: DEFAULT_QUIET_HOURS_START,
            quietHoursEndHour = (map["quietHoursEndHour"] as? Number)?.toInt()
                ?: DEFAULT_QUIET_HOURS_END,
            faculty = map["faculty"] as? String ?: "",
            cumulativeTotalPoints = (map["cumulativeTotalPoints"] as? Number)?.toInt() ?: 0,
            cumulativeTotalSteps = (map["cumulativeTotalSteps"] as? Number)?.toInt() ?: 0,
            cumulativeBonusPoints = (map["cumulativeBonusPoints"] as? Number)?.toInt() ?: 0,
        )
    }

    private fun mapToPublicUserProfile(map: Map<*, *>, fallbackUid: String): PublicUserProfile {
        return PublicUserProfile(
            uid = map["uid"] as? String ?: fallbackUid,
            username = map["username"] as? String ?: "",
            profileImageUrl = map["profileImageUrl"] as? String ?: "",
            faculty = map["faculty"] as? String ?: "",
        )
    }

    // -------------------------------------------------------------------------
    // PUSH NOTIFICATIONS (FCM token registration)
    // -------------------------------------------------------------------------

    /**
     * Registers this device's current FCM token on the server under the current user.
     *
     * Verify in Firestore:
     * users/{uid}/fcm_tokens/{token}
     */
    suspend fun registerFcmTokenResult(): Result<Boolean> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) {
            return Result.failure(uidCheck.exceptionOrNull()!!)
        }

        return runCatching {
            val token = Firebase.messaging.token.await()
            callOkResult("registerFcmToken", mapOf("token" to token)).getOrThrow()
        }
    }

    // -------------------------------------------------------------------------
    // PROFILE
    // -------------------------------------------------------------------------

    /**
     * Loads a public-safe profile for another user.
     *
     * Expected callable: getPublicUserProfile
     * Allowed response fields only: uid, username, profileImageUrl, faculty.
     */
    suspend fun getPublicUserProfileResult(uid: String): Result<PublicUserProfile> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) {
            return Result.failure(uidCheck.exceptionOrNull()!!)
        }

        return runCatching {
            require(uid.isNotBlank()) { "uid is required." }

            val res = functions.getHttpsCallable("getPublicUserProfile")
                .call(mapOf("uid" to uid))
                .await()

            val map = res.data as? Map<*, *>
                ?: error("getPublicUserProfile returned invalid payload.")

            val ok = map["ok"] as? Boolean ?: false
            if (!ok) error("getPublicUserProfile returned ok=false.")

            mapToPublicUserProfile(map, uid)
        }
    }

    /**
     * Loads the current user's editable profile from the server.
     */
    suspend fun getMyProfileResult(): Result<UserProfile> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) {
            return Result.failure(uidCheck.exceptionOrNull()!!)
        }

        return runCatching {
            val res = functions.getHttpsCallable("getMyProfile").call().await()
            val map = res.data as? Map<*, *> ?: error("getMyProfile returned invalid payload.")

            val ok = map["ok"] as? Boolean ?: false
            if (!ok) error("getMyProfile returned ok=false.")

            mapToUserProfile(map)
        }
    }

    /**
     * Updates the current user's editable profile on the server.
     *
     * Important:
     * - If quiet hours change, the backend may reset learned preferred intervals
     *   so it can rebuild them using the new awake window.
     */
    suspend fun updateMyProfileResult(profile: UserProfile): Result<UserProfile> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) {
            return Result.failure(uidCheck.exceptionOrNull()!!)
        }

        return runCatching {
            requireValidHour(profile.quietHoursStartHour, "quietHoursStartHour")
            requireValidHour(profile.quietHoursEndHour, "quietHoursEndHour")

            val payload = mapOf(
                "username" to profile.username.trim(),
                "email" to profile.email.trim(),
                "profileImageUrl" to profile.profileImageUrl.trim(),
                "timezone" to profile.timezone,
                "faculty" to profile.faculty.trim(),
                "lowActivityNudgeEnabled" to profile.lowActivityNudgeEnabled,
                "quietHoursStartHour" to profile.quietHoursStartHour,
                "quietHoursEndHour" to profile.quietHoursEndHour,
            )

            val res = functions.getHttpsCallable("updateMyProfile").call(payload).await()
            val map = res.data as? Map<*, *> ?: error("updateMyProfile returned invalid payload.")

            val ok = map["ok"] as? Boolean ?: false
            if (!ok) error("updateMyProfile returned ok=false.")

            mapToUserProfile(map)
        }
    }

    suspend fun uploadProfileImageBytesResult(bytes: ByteArray): Result<String> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) {
            return Result.failure(uidCheck.exceptionOrNull()!!)
        }

        return runCatching {
            val uid = uidCheck.getOrThrow()
            val ref = storage.reference.child("profile_images/$uid.jpg")
            ref.putBytes(bytes).await()
            ref.downloadUrl.await().toString()
        }
    }

    // -------------------------------------------------------------------------
    // QUIET HOURS / REMINDER WINDOW
    // -------------------------------------------------------------------------

    /**
     * Updates the current user's quiet hours on the server.
     *
     * Meaning:
     * - quietHoursStartHour = beginning of the user's sleep / do-not-disturb window
     * - quietHoursEndHour = end of the user's sleep / do-not-disturb window
     *
     * Examples:
     * - Regular user: 22 -> 8
     * - Night-active user: 8 -> 16
     *
     * Important:
     * - The server resets preferred reminder intervals when quiet hours change,
     *   so monthly learning can rebuild using the new awake window.
     */
    suspend fun updateQuietHoursResult(
        quietHoursStartHour: Int,
        quietHoursEndHour: Int,
    ): Result<QuietHoursInfo> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) {
            return Result.failure(uidCheck.exceptionOrNull()!!)
        }

        return runCatching {
            requireValidHour(quietHoursStartHour, "quietHoursStartHour")
            requireValidHour(quietHoursEndHour, "quietHoursEndHour")

            val payload = mapOf(
                "quietHoursStartHour" to quietHoursStartHour,
                "quietHoursEndHour" to quietHoursEndHour,
            )

            val res = functions.getHttpsCallable("updateQuietHours").call(payload).await()
            val map = res.data as? Map<*, *> ?: error("updateQuietHours returned invalid payload.")

            val ok = (map["ok"] as? Boolean) ?: false
            if (!ok) error("updateQuietHours returned ok=false.")

            val start = (map["quietHoursStartHour"] as? Number)?.toInt()
                ?: error("quietHoursStartHour missing in server response.")
            val end = (map["quietHoursEndHour"] as? Number)?.toInt()
                ?: error("quietHoursEndHour missing in server response.")

            QuietHoursInfo(
                quietHoursStartHour = start,
                quietHoursEndHour = end
            )
        }
    }

    // -------------------------------------------------------------------------
    // STEPS (4-hour buckets / "sixths")
    // -------------------------------------------------------------------------

    /**
     * Uploads step totals for a given day and 4-hour interval.
     *
     * Server-side intent:
     * - Save the user's steps per interval so the backend can:
     *   - determine the best interval at end-of-day
     *   - learn monthly patterns
     *   - schedule reminders outside the user's quiet hours
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
     * Convenience wrapper: uploads steps for the current day + current 4-hour interval.
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
     * Mapping:
     * 0: 00–04
     * 1: 04–08
     * 2: 08–12
     * 3: 12–16
     * 4: 16–20
     * 5: 20–24
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
     * Records a bonus station visit on the server after BLE validation.
     *
     * Verify in Firestore:
     * users/{uid}/bonus_visits/{dayKey}
     * users/{uid}/daily/{dayKey}
     */
    suspend fun recordBonusVisitResult(
        stationId: String,
        visitedAtMs: Long = System.currentTimeMillis(),
    ): Result<Boolean> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) {
            return Result.failure(uidCheck.exceptionOrNull()!!)
        }

        val data = mapOf(
            "stationId" to stationId,
            "visitedAtMs" to visitedAtMs,
        )
        return callOkResult("recordBonusVisit", data)
    }

    /**
     * Syncs the cumulative number of steps that were locally validated as being
     * inside the college bonus polygon for the given day.
     *
     * Important:
     * - The client sends a cumulative total, not a delta.
     * - The server advances only if the submitted total is higher than what it already has.
     *   This makes retries and restarts idempotent.
     */
    suspend fun syncCollegeAreaStepsResult(
        dayKey: String,
        qualifiedStepsTotal: Int,
        observedAtMs: Long = System.currentTimeMillis(),
    ): Result<Boolean> {
        val uidCheck = requireUid()
        if (uidCheck.isFailure) {
            return Result.failure(uidCheck.exceptionOrNull()!!)
        }

        require(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(dayKey)) {
            "dayKey must be YYYY-MM-DD."
        }
        require(qualifiedStepsTotal >= 0) {
            "qualifiedStepsTotal must be >= 0."
        }

        val data = mapOf(
            "dayKey" to dayKey,
            "qualifiedStepsTotal" to qualifiedStepsTotal,
            "observedAtMs" to observedAtMs,
        )

        return callOkResult("syncCollegeAreaSteps", data)
    }
}