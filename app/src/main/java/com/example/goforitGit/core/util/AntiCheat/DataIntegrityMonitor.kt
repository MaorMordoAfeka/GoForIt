package com.example.goforitGit.core.util.AntiCheat

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Local data-tampering detection for the step store.
 * ---------------------------------------------------
 * Catches users who edit the persisted SharedPreferences file outside the app
 * (root tools, ADB backup/restore, third-party prefs editors) to inflate their
 * step count.
 *
 * Technique: keep a keyed HMAC-SHA256 over the canonical concatenation of the
 * step-related preference values. The HMAC key lives in the **Android Keystore**
 * (alias [KEY_ALIAS], `PURPOSE_SIGN | PURPOSE_VERIFY`) — it never leaves
 * hardware-backed storage, so even a rooted user can't read it to forge a tag.
 *
 *  - When [verifyAndResign] runs and the stored tag does not match a freshly
 *    computed one, the data was modified outside our normal write path.
 *  - When the tag file is missing while the data file still exists, the user
 *    deleted the tag to try to bypass the check.
 *
 * `StepCounterZC` is NOT modified — this class only **reads** the same
 * `stepzc_prefs` keys the counter writes.
 */
class DataIntegrityMonitor(context: Context) {

    companion object {
        private const val TAG = "DataIntegrity"

        private const val PREFS     = "anticheat_data"
        private const val KEY_ALIAS = "goforit_stepdata_hmac_v1"

        private const val K_TAG          = "tag"
        private const val K_TAMPER_COUNT = "tampers"
        private const val K_TAMPER_LAST  = "tamperLastMs"
        private const val K_TAMPER_KIND  = "tamperLastKind"

        private const val STEPS_PREFS = "stepzc_prefs"

        /** Keys in `stepzc_prefs` we treat as part of the protected payload. */
        private val WATCHED_KEYS = arrayOf(
            "totalSteps",
            "stepTimesCsv",
            "recentStepsCsv",
            "lastSaveMs",
            "collegeBonusDayKey",
            "collegeQualifiedStepsToday",
            "collegeLastSyncedQualifiedStepsToday"
        )

        private const val NULL_MARKER = "∅" // ∅ — distinct from "" so absence ≠ empty
    }

    enum class State {
        FIRST_RUN,    // no prior tag and no data to protect yet
        OK,           // tag matched
        TAMPERED,     // tag mismatched — data was changed outside the app's write path
        MISSING_TAG,  // step data present, but tag was deleted
        KEY_ERROR     // Keystore / HMAC failure
    }

    data class Report(val state: State, val tamperCount: Int, val message: String)

    private val ctx        = context.applicationContext
    private val prefs      = ctx.getSharedPreferences(PREFS,        Context.MODE_PRIVATE)
    private val stepsPrefs = ctx.getSharedPreferences(STEPS_PREFS,  Context.MODE_PRIVATE)

    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // ---- read-only telemetry ----

    val tamperCount: Int        get() = prefs.getInt(K_TAMPER_COUNT, 0)
    val lastTamperMs: Long      get() = prefs.getLong(K_TAMPER_LAST, 0L)
    val lastTamperKind: String? get() = prefs.getString(K_TAMPER_KIND, null)

    /**
     * Verify the stored tag against the current step data, then refresh it.
     *
     * Call this:
     *  - on app start, before trusting any persisted total;
     *  - before uploading totals to the server;
     *  - on a periodic background check.
     */
    @Synchronized
    fun verifyAndResign(): Report {
        val canonical = buildCanonical()

        val computed: String = try {
            computeTag(canonical)
        } catch (t: Throwable) {
            Log.e(TAG, "HMAC compute failed", t)
            return Report(State.KEY_ERROR, tamperCount, "Key/HMAC error: ${t.message}")
        }

        val storedTag    = prefs.getString(K_TAG, null)
        val priorKey     = keyExists()
        val dataNonEmpty = canonical != emptyCanonical

        val state: State
        val msg: String
        when {
            !priorKey && storedTag == null -> {
                // Brand-new install — accept the current state as the baseline.
                state = State.FIRST_RUN
                msg = "Initial baseline"
            }
            storedTag == null && dataNonEmpty -> {
                state = State.MISSING_TAG
                msg = "Integrity tag missing while step data exists"
                recordTamper(state, msg)
            }
            storedTag == null /* && data empty */ -> {
                state = State.FIRST_RUN
                msg = "No tag yet (empty step data)"
            }
            storedTag == computed -> {
                state = State.OK
                msg = "Tag matches"
            }
            else -> {
                state = State.TAMPERED
                msg = "Integrity tag mismatch"
                recordTamper(state, msg)
            }
        }

        // Re-sign so the next legitimate write keeps the chain unbroken.
        prefs.edit().putString(K_TAG, computed).apply()

        return Report(state, tamperCount, msg)
    }

    /** Re-sign the current step data without verifying. Use to accept current state. */
    @Synchronized
    fun resign() {
        try {
            val tag = computeTag(buildCanonical())
            prefs.edit().putString(K_TAG, tag).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Resign failed", t)
        }
    }

    /**
     * Auto-keep the tag in sync with legitimate in-process writes.
     *
     * Only **silent re-signs** are done here — tamper detection happens via the
     * explicit [verifyAndResign] call. (External edits to the prefs *file* do
     * not fire this listener, so tagging continues to reflect the last in-process
     * state, and the next [verifyAndResign] catches the discrepancy.)
     */
    @Synchronized
    fun startMonitoring() {
        if (listener != null) return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key in WATCHED_KEYS) resign()
        }
        stepsPrefs.registerOnSharedPreferenceChangeListener(l)
        listener = l
    }

    @Synchronized
    fun stopMonitoring() {
        listener?.let { stepsPrefs.unregisterOnSharedPreferenceChangeListener(it) }
        listener = null
    }

    @Synchronized
    fun reset() {
        prefs.edit().clear().apply()
    }

    // -----------------------------------------------------------------------

    /** Stable, ordered serialization of the protected payload. */
    private fun buildCanonical(): String {
        val sb = StringBuilder(256)
        val all = stepsPrefs.all
        for (k in WATCHED_KEYS) {
            sb.append(k).append('=')
            sb.append(
                when (val v = all[k]) {
                    null       -> NULL_MARKER
                    is String  -> v
                    is Int     -> v.toString()
                    is Long    -> v.toString()
                    is Boolean -> v.toString()
                    is Float   -> v.toString()
                    else       -> v.toString()
                }
            )
            sb.append('|')
        }
        return sb.toString()
    }

    private val emptyCanonical: String by lazy {
        val sb = StringBuilder()
        for (k in WATCHED_KEYS) sb.append(k).append('=').append(NULL_MARKER).append('|')
        sb.toString()
    }

    private fun computeTag(canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(loadOrCreateKey())
        val out = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun keyExists(): Boolean {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.containsAlias(KEY_ALIAS)
        } catch (t: Throwable) {
            Log.w(TAG, "keyExists check failed: ${t.message}")
            false
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val gen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            "AndroidKeyStore"
        )
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).build()
        )
        return gen.generateKey()
    }

    private fun recordTamper(kind: State, detail: String) {
        val newCount = tamperCount + 1
        prefs.edit()
            .putInt(K_TAMPER_COUNT,   newCount)
            .putLong(K_TAMPER_LAST,   System.currentTimeMillis())
            .putString(K_TAMPER_KIND, kind.name)
            .apply()
        Log.w(TAG, "data tamper recorded: $kind — $detail (total=$newCount)")
    }
}
