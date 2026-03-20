# GoForIt — Firebase Functions Usage (Callable + Scheduled + Trigger)

This README explains **how to use each Firebase Cloud Function** in the GoForIt project.

We currently have **three categories** of functions:

## 1) Callable functions (Android calls these)
- `registerFcmToken`
- `uploadStepInterval`
- `recordBonusVisit`
- `syncCollegeAreaSteps`
- `getMyProfile`
- `updateMyProfile`
- `updateQuietHours`

## 2) Scheduled functions (server runs these automatically)
- `finalizeDay` — daily at 00:30 Asia/Jerusalem
- `dispatchNotificationJobs` — every 15 minutes
- `finalizeMonth` — 1st of each month at 00:20

## 3) Auth trigger (server runs automatically)
- `onAuthUserCreate` — creates user doc with defaults on registration

> **Key rule:** Scheduled functions and triggers are **NOT called from Android**. They run automatically on Firebase.

---

# Constants

| Constant | Value            | Notes                           |
|----------|------------------|---------------------------------|
| `FUNCTIONS_REGION` |  `us-central1`   | Must match Android client       |
| `DEFAULT_TZ` | `Asia/Jerusalem` | Fallback timezone               |
| `DEFAULT_QUIET_START_HOUR` | `22`             | Default quiet hours start       |
| `DEFAULT_QUIET_END_HOUR` | `8`              | Default quiet hours end         |
| `COLLEGE_AREA_BONUS_POINTS_PER_STEP` | `10`             | Points per qualified campus step |

---

# User profile schema (Firestore: `users/{uid}`)

| Field | Type | Default |
|-------|------|---------|
| `uid` | string | — |
| `timezone` | string | `"Asia/Jerusalem"` |
| `lowActivityNudgeEnabled` | boolean | `true` |
| `preferredActiveInterval` | number or null | `null` |
| `preferredInactiveInterval` | number or null | `null` |
| `quietHoursStartHour` | 0..23 | `22` |
| `quietHoursEndHour` | 0..23 | `8` |
| `faculty` | string (max 80 chars) | `""` |
| `createdAt` | Timestamp | auto |
| `updatedAt` | Timestamp | auto |

---

# Auth Trigger

## `onAuthUserCreate` (v1 auth trigger)

### Purpose
Automatically creates a user document at `users/{uid}` with default profile fields whenever a new Firebase Auth user is registered.

### How it works
- Triggered by Firebase Auth on every new user creation (Email/Password, etc.)
- Writes default values using `merge: true` (safe if doc partially exists)

### Android usage
You don't call this. It fires automatically when `FirebaseServerApi.register()` succeeds.

### Verify in Firestore
- `users/{uid}` exists with all default fields after registration.

---

# Callable Functions (Android → Server)

## 1) `registerFcmToken`

### Purpose
Registers this device's FCM token under the signed-in user so the backend can send push notifications.

### Parameters
- `token` (string, min 20 chars): the FCM device token

### When to call
- Immediately after login/register
- Safe on every app start (idempotent)

### Server behavior
- Calls `ensureUserDoc(uid)` to guarantee user doc exists
- Writes to `users/{uid}/fcm_tokens/{token}` with `platform: "android"`

### Android usage
```kotlin
lifecycleScope.launch {
    val r = FirebaseServerApi.registerFcmTokenResult()
    r.onSuccess { ok -> Log.d("FCM", "Token registered: $ok") }
     .onFailure { e -> Log.e("FCM", "Token registration failed: ${e.message}", e) }
}
```

### Verify in Firestore
- `users/{uid}/fcm_tokens/{token}` exists

---

## 2) `uploadStepInterval`

### Purpose
Uploads step totals for one 4-hour interval ("sixth") of a day.

### Parameters
- `dayKey` (string): `"YYYY-MM-DD"`
- `intervalIndex` (int): `0..5`
- `stepsTotal` (int): `0..300000`
- `uploadIntervalIndex` (int, optional): defaults to `intervalIndex`
- `attributedIntervalIndex` (int, optional): defaults to `intervalIndex`

### Server behavior
- Calls `ensureUserDoc(uid)` first
- Rejects uploads if the day is already finalized (`isFinalized === true`)
- Writes to `users/{uid}/step_sessions/{dayKey_intervalIndex}`
- Updates `users/{uid}/daily/{dayKey}` with `stepsByInterval`, `sumIntervals`, `totalSteps`, `uploadedMask`
- Uses a bitmask (`uploadedMask`) to track which intervals have been uploaded (bit N = interval N)
- `isComplete` = all 6 bits set (`0b111111`)
- Triggers leaderboard recalculation for that day

### Android usage (upload "now")
```kotlin
lifecycleScope.launch {
    val (dayKey, intervalIndex) = FirebaseServerApi.currentDayKeyAndInterval()
    val stepsInThisInterval = 420

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
```kotlin
lifecycleScope.launch {
    val dayKey = buckets.getTodayKey()
    val stepsByInterval = buckets.getBucketsForToday() // size = 6, slots 0..5

    for (i in stepsByInterval.indices) {
        val r = FirebaseServerApi.uploadStepIntervalResult(dayKey, i, stepsByInterval[i])
        r.onFailure { e -> Log.e("STEPS", "Interval $i failed: ${e.message}", e) }
    }
}
```

### Returns
```json
{ "ok": true, "leaderboardRecalculated": true }
```

### Verify in Firestore
- `users/{uid}/step_sessions/{dayKey_intervalIndex}`
- `users/{uid}/daily/{dayKey}` (aggregate updated)
- `leaderboards_daily/{dayKey}/entries/{uid}` (rank updated)

---

## 3) `recordBonusVisit`

### Purpose
Records a validated bonus station visit (BLE/QR) and awards bonus points.

### Parameters
- `stationId` (string): must exist in `bonus_stations/{stationId}`
- `visitedAtMs` (long, optional): epoch millis, defaults to server now

### Server behavior
- Validates station exists in `bonus_stations/{stationId}` (throws `not-found` if missing)
- Reads `pointsValue` from the station document
- Uses the user's timezone to derive `dayKey` from `visitedAtMs`
- **One bonus per day**: checks both `users/{uid}/bonus_visits/{dayKey}` and `daily.didBonus`
- If not yet awarded: writes visit doc, sets `didBonus: true`, adds `pointsValue` to `bonusPoints`
- Triggers leaderboard recalculation if awarded

### Returns
```json
{ "ok": true, "dayKey": "2026-03-20", "awardedPoints": 50 }
```
or if already claimed:
```json
{ "ok": false, "dayKey": "2026-03-20", "awardedPoints": 0 }
```

### Android usage
```kotlin
lifecycleScope.launch {
    val r = FirebaseServerApi.recordBonusVisitResult(stationId = "S01")
    r.onSuccess { ok ->
        if (ok) Log.d("BONUS", "Bonus awarded")
        else Log.d("BONUS", "Bonus already claimed today")
    }.onFailure { e ->
        Log.e("BONUS", "Bonus failed: ${e.message}", e)
    }
}
```

### Verify in Firestore
- `users/{uid}/bonus_visits/{dayKey}` (one doc per day)
- `users/{uid}/daily/{dayKey}` has `didBonus: true` and `bonusPoints` updated

---

## 4) `syncCollegeAreaSteps`

### Purpose
Syncs college-area qualified steps from Android to the server, awarding bonus points per step.

### Parameters
- `dayKey` (string): `"YYYY-MM-DD"`
- `qualifiedStepsTotal` (int): cumulative qualified steps for the day (0..300000)
- `observedAtMs` (long, optional): epoch millis, defaults to server now

### Server behavior
- Rejects if the day is finalized
- Uses monotonic "high-water mark" — `acceptedQualifiedSteps = max(previous, submitted)`
- Awards `COLLEGE_AREA_BONUS_POINTS_PER_STEP` (10) points per new step delta
- Updates `users/{uid}/daily/{dayKey}` with:
    - `collegeAreaQualifiedSteps`
    - `collegeAreaBonusPoints`
    - `bonusPoints` (incremented by delta × 10)
- Triggers leaderboard recalculation if delta > 0

### Returns
```json
{
  "ok": true,
  "dayKey": "2026-03-20",
  "acceptedQualifiedSteps": 150,
  "appliedDelta": 30,
  "awardedPoints": 300,
  "leaderboardRecalculated": true
}
```

### Android usage
```kotlin
lifecycleScope.launch {
    val dayKey = FirebaseServerApi.currentDayKeyAndInterval().first
    val r = FirebaseServerApi.syncCollegeAreaStepsResult(
        dayKey = dayKey,
        qualifiedStepsTotal = 150
    )
    r.onSuccess { Log.d("COLLEGE", "Synced: $it") }
     .onFailure { Log.e("COLLEGE", "Sync failed: ${it.message}") }
}
```

### Verify in Firestore
- `users/{uid}/daily/{dayKey}` has `collegeAreaQualifiedSteps`, `collegeAreaBonusPoints`

---

## 5) `getMyProfile`

### Purpose
Returns the current user's profile from Firestore.

### Parameters
None (uses auth uid).

### Server behavior
- Calls `ensureUserDoc(uid)` (creates defaults if missing)
- Returns all profile fields

### Returns
```json
{
  "ok": true,
  "timezone": "Asia/Jerusalem",
  "lowActivityNudgeEnabled": true,
  "preferredActiveInterval": null,
  "preferredInactiveInterval": null,
  "quietHoursStartHour": 22,
  "quietHoursEndHour": 8,
  "faculty": ""
}
```

### Android usage
```kotlin
lifecycleScope.launch {
    val r = FirebaseServerApi.getMyProfileResult()
    r.onSuccess { profile -> /* populate UI */ }
     .onFailure { e -> Log.e("PROFILE", "Load failed: ${e.message}", e) }
}
```

---

## 6) `updateMyProfile`

### Purpose
Updates the current user's full profile.

### Parameters
- `timezone` (string): IANA timezone, defaults to `"Asia/Jerusalem"` if empty/invalid
- `faculty` (string): max 80 chars
- `lowActivityNudgeEnabled` (boolean, required)
- `quietHoursStartHour` (int): 0..23
- `quietHoursEndHour` (int): 0..23

### Server behavior
- If quiet hours changed, resets `preferredActiveInterval` and `preferredInactiveInterval` to `null`
- Returns the updated profile after saving

### Returns
Same shape as `getMyProfile`.

### Android usage
```kotlin
lifecycleScope.launch {
    val r = FirebaseServerApi.updateMyProfileResult(profile)
    r.onSuccess { updated -> /* refresh UI */ }
     .onFailure { e -> Log.e("PROFILE", "Update failed: ${e.message}", e) }
}
```

---

## 7) `updateQuietHours`

### Purpose
Updates only the quiet hours window (lightweight alternative to `updateMyProfile`).

### Parameters
- `quietHoursStartHour` (int): 0..23
- `quietHoursEndHour` (int): 0..23

### Server behavior
- Resets `preferredActiveInterval` and `preferredInactiveInterval` to `null` (quiet window changed, learned preferences no longer valid)
- Returns the new values

### Returns
```json
{ "ok": true, "quietHoursStartHour": 23, "quietHoursEndHour": 7 }
```

---

# Scheduled Functions (Server-only)

## 8) `finalizeDay`

**Schedule**: `30 0 * * *` (daily at 00:30 Asia/Jerusalem)

### Purpose
End-of-day processing for yesterday. For each user:
1. Reads `users/{uid}/daily/{dayKey}` and computes final totals
2. Identifies best interval (most steps) and worst interval (fewest steps) from uploaded intervals that fall outside quiet hours
3. Writes `users/{uid}/daily_summaries/{dayKey}` with full stats
4. Marks the daily doc as `isFinalized: true` (blocks further uploads for that day)
5. Creates a `notification_jobs/{uid_nextDayKey}` entry with:
    - Chosen interval based on: user preference > learned best/worst > first allowed
    - `sendAt` calculated to avoid quiet hours
    - `selectionSource` for debugging (e.g., `"preferredActiveInterval"`, `"worstUploadedAwakeInterval"`, `"fallbackFirstAllowedInterval"`)
6. Builds and writes the daily leaderboard to `leaderboards_daily/{dayKey}/entries/`

### Leaderboard scoring
- `stepPoints = floor(totalSteps / 100)`
- `totalPoints = stepPoints + bonusPoints`
- Sorted by: totalPoints desc → totalSteps desc → bonusPoints desc → uid asc

### Android reads
- `users/{uid}/daily_summaries/{dayKey}`
- `leaderboards_daily/{dayKey}/entries/{uid}`
- `notification_jobs/{uid_dayKey}`

---

## 9) `dispatchNotificationJobs`

**Schedule**: `*/15 * * * *` (every 15 minutes, 24/7)

### Purpose
Polls `notification_jobs` where `status == "PENDING"` and `sendAt <= now` (limit 50 per run).

### Per job:
1. Reads `users/{uid}/fcm_tokens` to get device tokens
2. Sends push via `messaging.sendEachForMulticast` with:
    - Title: `"Go for it!"`
    - Body: `"Time for some activity 💪"`
    - Data: `{ type: "REMINDER", dayKey, intervalIndex }`
3. Cleans up invalid/expired tokens from Firestore
4. Updates job status to `SENT`, `NO_TOKENS`, or `FAILED`

### Why 24/7
Quiet-hours logic is already baked into `sendAt` computation during `finalizeDay`. The dispatcher doesn't need its own quiet-hours check.

### Android prerequisites
- Call `registerFcmTokenResult()` after login
- Have `POST_NOTIFICATIONS` permission (Android 13+)
- Have a notification channel configured

---

## 10) `finalizeMonth`

**Schedule**: `20 0 1 * *` (1st of each month at 00:20 Asia/Jerusalem)

### Purpose
Monthly learning/aggregation for the previous month:
1. For each user, reads all `daily_summaries` within the month range
2. Builds histograms of best and worst intervals (filtered by quiet hours)
3. If the user has 5+ days of data: updates `preferredActiveInterval` and `preferredInactiveInterval` on the user doc to the mode (most frequent) interval
4. These learned preferences are then used by `finalizeDay` to schedule smarter reminders

---

# Firestore data model

## Collections and documents

```
users/{uid}                              ← profile + learned preferences
users/{uid}/fcm_tokens/{token}           ← one doc per device
users/{uid}/daily/{dayKey}               ← live daily aggregate (steps, bonus, mask)
users/{uid}/step_sessions/{dayKey_idx}   ← per-interval upload record
users/{uid}/bonus_visits/{dayKey}        ← one per day (BLE station visit)
users/{uid}/daily_summaries/{dayKey}     ← finalized end-of-day summary

bonus_stations/{stationId}               ← station config (pointsValue, etc.)

notification_jobs/{uid_dayKey}           ← reminder scheduling + delivery status

leaderboards_daily/{dayKey}/entries/{uid} ← ranked leaderboard entry
```

## Daily document fields (`users/{uid}/daily/{dayKey}`)

| Field | Type | Notes |
|-------|------|-------|
| `stepsByInterval` | number[6] | Steps per 4-hour slot |
| `sumIntervals` | number | Sum of stepsByInterval |
| `totalSteps` | number | Same as sumIntervals |
| `uploadedMask` | number | Bitmask (bit N = interval N uploaded) |
| `isFinalized` | boolean | Set by `finalizeDay`, blocks further uploads |
| `didBonus` | boolean | BLE station bonus claimed |
| `bonusPoints` | number | Total bonus points (station + college area) |
| `collegeAreaQualifiedSteps` | number | Campus steps high-water mark |
| `collegeAreaBonusPoints` | number | Campus steps × 10 |

---

# Recommended end-to-end call order (Android)

1. Login / register user (Email/Password)
2. `registerFcmTokenResult()`
3. Every 4 hours: `uploadStepIntervalResult(...)`
4. On BLE station validation: `recordBonusVisitResult(...)`
5. On college-area steps: `syncCollegeAreaStepsResult(...)`
6. Profile screen: `getMyProfileResult()` / `updateMyProfileResult(...)`
7. Scheduled functions run automatically on the server

---

# Quick verification checklist (Firestore)

- User doc exists: `users/{uid}` with all default fields
- Tokens exist: `users/{uid}/fcm_tokens/...`
- Daily doc exists: `users/{uid}/daily/{dayKey}`
- Step sessions exist: `users/{uid}/step_sessions/...`
- Bonus visit doc exists (one per day): `users/{uid}/bonus_visits/{dayKey}`
- College area tracked: `users/{uid}/daily/{dayKey}` has `collegeAreaQualifiedSteps`
- Daily summary created after finalization: `users/{uid}/daily_summaries/{dayKey}`
- Leaderboard entries: `leaderboards_daily/{dayKey}/entries/{uid}`
- Notification jobs exist and change status: `notification_jobs/...`