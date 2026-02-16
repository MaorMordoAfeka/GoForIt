# GoForIt — Firebase Functions Usage (Callable + Scheduled)

This README explains **how to use each Firebase Cloud Function** in the GoForIt project.

we currently have **two categories** of functions:

## 1) Callable functions (Android calls these)
- `registerFcmToken`
- `uploadStepInterval`
- `recordBonusVisit`

## 2) Scheduled functions (server runs these automatically)
- `finalizeDay`
- `dispatchNotificationJobs`
- `finalizeMonth`

> **Key rule:** Scheduled functions are **NOT called from Android**. They run on Firebase’s schedule.

---

# Prerequisites (already done)

## A) Enable Email/Password Authentication
Firebase Console → **Build → Authentication → Sign-in method** → enable **Email/Password**.

## B) Android dependencies (Firebase BoM)
In `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.9.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1") // Task.await()
}
```

## C) Android notifications prerequisites
- Android 13+ needs runtime permission: `POST_NOTIFICATIONS`
- Android 8+ needs a Notification Channel
- Foreground push usually requires showing a notification yourself (FirebaseMessagingService)

---

# Callable Functions (Android → Server)

## 1) `registerFcmToken` (callable)

### Purpose
Registers this device’s **FCM token** under the signed-in user, so the backend can send push notifications to this device.

### When to call
- Immediately **after login/register**
- Also safe on **every app start** (idempotent)

### Android usage (Result<Boolean>)
```kotlin
lifecycleScope.launch {
    val r = FirebaseServerApi.registerFcmTokenResult()
    r.onSuccess { ok -> Log.d("FCM", "Token registered: $ok") }
     .onFailure { e -> Log.e("FCM", "Token registration failed: ${e.message}", e) }
}
```

### Verify in Firestore
- `users/{uid}/fcm_tokens/{token}` exists.

---

## 2) `uploadStepInterval` (callable)

### Purpose
Uploads step totals for one **4-hour interval** (“sixth”) of a day.

### Parameters
- `dayKey`: `"YYYY-MM-DD"` (e.g., `"2026-02-16"`)
- `intervalIndex`: `0..5` (4-hour buckets)
- `stepsTotal`: total steps attributed to that interval
- Optional:
  - `uploadIntervalIndex`: interval when the upload occurred (usually same as intervalIndex)
  - `attributedIntervalIndex`: interval the steps belong to (useful for late/batched attribution)

### Common usage (upload “now”)
```kotlin
lifecycleScope.launch {
    val (dayKey, intervalIndex) = FirebaseServerApi.currentDayKeyAndInterval()
    val stepsInThisInterval = 420 // replace with your computed value

    val r = FirebaseServerApi.uploadStepIntervalResult(
        dayKey = dayKey,
        intervalIndex = intervalIndex,
        stepsTotal = stepsInThisInterval
    )

    r.onSuccess { ok -> Log.d("STEPS", "Uploaded: $ok") }
     .onFailure { e -> Log.e("STEPS", "Upload failed: ${e.message}", e) }
}
```

### Batch upload (multiple intervals)
> Recommended: upload sequentially (avoid parallel writes to the same daily document).
```kotlin
lifecycleScope.launch {
    val dayKey = "2026-02-16"
    val stepsByInterval = listOf(120, 340, 560, 200, 0, 90) // 0..5

    for (i in 0..5) {
        val r = FirebaseServerApi.uploadStepIntervalResult(dayKey, i, stepsByInterval[i])
        r.onFailure { e -> Log.e("STEPS", "Interval $i failed: ${e.message}", e) }
    }
}
```

### Verify in Firestore
- `users/{uid}/step_sessions/{dayKey_intervalIndex}`
- `users/{uid}/daily/{dayKey}` (aggregate updated)

---

## 3) `recordBonusVisit` (callable)

### Purpose
Records a validated bonus station visit (QR/BLE) and updates the daily bonus state.

### Rule (your requirement)
✅ **Only one bonus per day**:
- First call that day: awards bonus → returns `ok=true`
- Any later call same day: no extra award → returns `ok=false`

### Android usage (Result<Boolean>)
```kotlin
lifecycleScope.launch {
    val r = FirebaseServerApi.recordBonusVisitResult(stationId = "S01")
    r.onSuccess { ok ->
        if (ok) Log.d("BONUS", "Bonus awarded ✅")
        else Log.d("BONUS", "Bonus already claimed today")
    }.onFailure { e ->
        Log.e("BONUS", "Bonus failed: ${e.message}", e)
    }
}
```

### Verify in Firestore
- `users/{uid}/bonus_visits/{dayKey}` (one doc per day)
- `users/{uid}/daily/{dayKey}` has `didBonus=true` and bonus points updated

---

# Scheduled Functions (Server-only)

## 4) `finalizeDay` (scheduled)

### Purpose
End-of-day processing. Typical responsibilities:
- finalize daily totals and interval stats
- compute “best sixth” (most active interval)
- update daily summaries / leaderboards
- create reminder jobs for the next day

### How Android uses it
You don’t call it. You can **read its outputs** from Firestore later:
- `users/{uid}/daily_summaries/{dayKey}`
- `leaderboards_daily/{dayKey}/entries/{uid}`
- `notification_jobs/{uid_dayKey}`

---

## 5) `dispatchNotificationJobs` (scheduled)

### Purpose
Runs periodically to deliver notifications:
- finds jobs where `status == PENDING` and `sendAt <= now`
- pulls tokens from `users/{uid}/fcm_tokens`
- sends push via FCM
- marks job `SENT` / `NO_TOKENS` / `FAILED`

### Why you saw “12 invocations/hour”
If scheduled every 5 minutes:
- `60 / 5 = 12` runs per hour

### How Android uses it
Android must:
- call `registerFcmTokenResult()` after login
- have notifications permission/channel configured

To verify delivery, check `notification_jobs/...` status transitions.

---

## 6) `finalizeMonth` (scheduled)

### Purpose
Monthly learning/aggregation:
- aggregates daily “best sixth”
- identifies typical active/inactive intervals
- improves reminder targeting

### How Android uses it
You don’t call it. Optionally read monthly aggregates if your backend stores them.

---

# Recommended end-to-end call order (Android)

1) Login / register user (Email/Password)  
2) Call `registerFcmTokenResult()`  
3) Every 4 hours: call `uploadStepIntervalResult(...)`  
4) On QR/BLE validation: call `recordBonusVisitResult(...)`  
5) Scheduled functions run automatically and will later send notifications

---

# Quick verification checklist (Firestore)

- Tokens exist: `users/{uid}/fcm_tokens/...`
- Daily doc exists: `users/{uid}/daily/{dayKey}`
- Step sessions exist: `users/{uid}/step_sessions/...`
- Bonus visit doc exists (one per day): `users/{uid}/bonus_visits/{dayKey}`
- Notification jobs exist and change status: `notification_jobs/...`
