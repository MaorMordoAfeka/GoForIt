# GoForIt 🏃‍♂️

A native Android app (Kotlin) that turns walking around a college campus into a game.  
It counts your steps with a custom accelerometer algorithm, gives bonus points for walking on campus, ranks students and faculties on daily leaderboards,  
hands out personal challenges, and includes a fully offline map and walking-route planner for Israel. All scoring happens on a Firebase backend, so the phone only measures and displays.

Engineering final project by **Maor Mordo & Idan Meir**.  

---

## Contents

- [What it does](#what-it-does)
- [How it fits together](#how-it-fits-together)
- [Step counting](#step-counting)
- [Staying alive in the background](#staying-alive-in-the-background)
- [Uploading steps](#uploading-steps)
- [Points and leaderboards](#points-and-leaderboards)
- [Campus and beacon bonuses](#campus-and-beacon-bonuses)
- [Personal challenges](#personal-challenges)
- [Anti-cheat](#anti-cheat)
- [Offline maps and routes](#offline-maps-and-routes)
- [Backend](#backend)
- [Screens](#screens)
- [QA tools](#qa-tools)
- [Permissions](#permissions)
- [Project structure](#project-structure)
- [Building and running](#building-and-running)
- [Design principles](#design-principles)

---

## What it does

| Feature | Summary |
|---|---|
| **Step counting** | Custom zero-crossing algorithm over accelerometer data, with motion-mode detection (walking, running, cycling, driving, stationary), cadence estimation, and a hardware-sensor fallback for battery-saver mode. |
| **Always-on tracking** | A foreground service plus a heartbeat, a watchdog, a restart worker, and a boot receiver, so tracking survives crashes, reboots, and Android 12–14 background restrictions. |
| **Server-side scoring** | Steps upload in six 4-hour buckets per day. The server computes points (`steps / 100` plus bonuses), freezes daily leaderboards, and aggregates faculty standings. |
| **Campus bonuses** | Steps inside the college polygon are worth 10 bonus points each. Physical BLE beacons (Raspberry Pi 5) award extra points when visited. |
| **Personal challenges** | Server-generated goals (`raise_baseline`, `study_break_boost`, `campus_explorer`). The client only shows them and lets you accept. |
| **Anti-cheat** | Detects clock manipulation and out-of-app edits to the saved step data, and gives each install its own device identity. |
| **Offline maps** | MapLibre map, GraphHopper foot routing, and an H3-indexed POI database — all bundled in the APK. Only place-name autocomplete needs internet. |
| **Notifications** | FCM reminders with per-user quiet hours, sent by a scheduled backend job. |
| **QA console** | A hidden acceptance-test screen, locked to one dedicated tester account. |

---

## How it fits together

```
ANDROID APP (Kotlin)
  Sensors → StepCounterZC → StepBus (StateFlows) → StepRepository → UI
                  ↓
       StepHistoryStore / DailyStepsStore / FourHourBucketsSinceBoot
                  (SharedPreferences)

  StepService (foreground)      → location → CollegeZoneChecker
  BleAdvertScanService (fg)     → bonus-station beacons
  FourHourUploadWorker          → FirebaseServerApi → Cloud Functions
  AntiCheatManager              → time + data integrity checks
  MapAndRoutesActivity          → MbTilesServer + OfflineRouter + H3OpeningScorer
                          │
                          ▼
FIREBASE BACKEND (TypeScript)
  Callables:  register, profile, quiet hours, upload, bonus, challenges
  Scheduled:  finalizeDay (00:30), dispatchNotificationJobs (every 15 min), finalizeMonth
  Firestore:  users/{uid}/daily/{dayKey}
              leaderboards_daily/{dayKey}/entries
              leaderboards_daily/{dayKey}/faculties
              bonus_stations, notification_jobs
```

**Step data path:** `StepService` runs `StepCounterZC`, which publishes to `StepBus` (state flows for steps, cadence, mode, speed, sensor stats). `StepRepository` exposes that as `LiveData`, and the ViewModels and fragments render it.

Package root is `com.example.goforitGit`, organized by feature: `core.service`, `core.data`, `core.util`, `feature.map`, `feature.leaderboard`, `feature.challenges`, `feature.auth`, `feature.profile`, `feature.statistics`, `feature.qa`, `navigation`, `tracking_lifecycle`.

---

## Step counting

`StepCounterZC` (~1,160 lines) is the core of the app — a singleton `SensorEventListener` that detects steps from zero crossings in the vertical component of acceleration.

- A small state machine validates each candidate step by amplitude and period. Samples are kept in `recentStepsCsv` as `timeMs:periodS:vRatio:amp`.
- Motion mode is one of `UNKNOWN`, `STATIONARY`, `STANDING_STILL`, `WALKING`, `RUNNING`, `CYCLING`, `DRIVING`. Steps only count in plausible modes, and mode changes use hysteresis so the label doesn't flicker.
- Cadence (steps per minute) and the actual sensor sampling rate are estimated continuously.
- In battery-saver mode it falls back to the hardware `TYPE_STEP_COUNTER` sensor, with filters that reject suspicious bursts (for example, flipping the phone while standing still).
- The saved total is loaded on the first `getInstance()` call, even if `start()` is never called.

**Where step data is saved** (all `SharedPreferences`, deliberately simple):

| Store | File | What it holds |
|---|---|---|
| `StepHistoryStore` | `stepzc_prefs` | The counter's source of truth: total steps, recent samples, last-save time, campus-bonus sync counters. |
| `DailyStepsStore` | — | One highest-total-per-day value, used for "best day" and weekly stats. |
| `FourHourBucketsSinceBoot` | — | Splits the since-boot sensor count into six 4-hour buckets, handling reboots and day rollover (keeps today and yesterday). |

`StepBus` is a tiny global object of `MutableStateFlow`s that keeps the counter independent of the UI.

---

## Staying alive in the background

Keeping a foreground service running on modern Android was the hardest part of the project, so the design tackles it head-on:

- **`StepService`** — foreground service for step counting and fused location. On Android 14+ it downgrades its service *type* when started from the background without `ACCESS_BACKGROUND_LOCATION`, because `type=LOCATION` is rejected there. Writes a heartbeat every ~30 s through `TrackingHeartbeat`.
- **`TrackingHealthWorker`** — runs every 15 minutes. If the heartbeat is stale, it enqueues the restart worker.
- **`TrackingRestartWorker`** — a one-shot expedited worker that calls `setForeground(...)`. Because it is itself briefly a foreground service, it is allowed to start other foreground services on Android 12+ — the only reliable background restart path. It calls `TrackingServiceManager.ensureTrackingRunning(...)`.
- **`TrackingServiceManager`** — the single place that starts/stops tracking (`StepService` + `BleAdvertScanService`) and schedules the health worker. State lives in `TrackingPrefs`.
- **`BootReceiver`** — on boot or app update, just hands off to `TrackingRestartWorker`. (Direct-boot support was removed on purpose: counting needs an unlocked user anyway.)
- **`Trackingpermissions` / `Onboardingprefs`** — the runtime-permission chain and first-run state used by `MainActivity`.

Worst-case recovery takes one health-worker cycle (~15 minutes). That's acceptable because the hardware counter and `FourHourBucketsSinceBoot` keep accumulating in the meantime.

---

## Uploading steps

The day (`Asia/Jerusalem`) is split into six intervals: `0 = 00–04`, `1 = 04–08`, … `5 = 20–24`.

1. `FourHourUploadScheduler` schedules `FourHourUploadWorker` shortly after each boundary.
2. The worker reads the finished bucket, skips it if already sent (a "sent" prefs file), and calls the `uploadStepInterval` callable with `(dayKey, intervalIndex, stepsTotal, sessionId)`.
3. The `00:05` run also retries *yesterday's* bucket 5, which is missed if the phone was off overnight.
4. The server saves buckets in `users/{uid}/daily/{dayKey}` (`stepsByInterval[6]`, `uploadedMask`, `totalSteps`), recomputes points, and updates cumulative totals and the live leaderboard.

A day is complete when `uploadedMask == 0b111111`.

---

## Points and leaderboards

- **Base points:** `floor(totalSteps / 100)`
- **Daily points:** base points + `bonusPoints` (campus and beacon bonuses)
- **`finalizeDay`** runs at `00:30` daily. It walks all users, freezes yesterday's rankings into `leaderboards_daily/{dayKey}/entries`, builds per-faculty rows (`FacultyStanding`: rank, total points, total steps, bonus points, member count, average points), and schedules the next day's reminders.
- **`finalizeMonth`** produces monthly aggregates.

On the client, `LeaderboardActivity` loads 20 entries at a time, can show any past `dayKey`, and has a faculty tab backed by `FacultyStandingAdapter`. Tapping a competitor opens `CompetitorProfileActivity`, which reads a sanitized profile via `getPublicUserProfile`.

---

## Campus and beacon bonuses

**Campus steps — 10 bonus points each**

`CollegeZoneChecker` loads `assets/college_polygon.json` (GeoJSON, `[lon, lat]` order) and answers point-in-polygon queries by ray casting, with a small boundary tolerance. While `StepService` gets location fixes inside the polygon, qualifying steps pile up in `StepHistoryStore`. The client periodically calls `syncCollegeAreaSteps`, and the server credits `Δsteps × 10` bonus points for that `dayKey`.

**BLE bonus stations**

`BleAdvertScanService` is a foreground scanner looking for Raspberry Pi 5 beacons (manufacturer ID `0xFFFF`, device name `RPi5 Beacon`). It alternates between burst and balanced scan modes, restarts stale scans with a watchdog, and reacts to Bluetooth being turned on or off. On a match it calls `recordBonusVisit(stationId)`; the server checks the station against `bonus_stations/{stationId}` and awards the bonus once per day.

---

## Personal challenges

Defined in `Personalchallengemodels.kt` and shown by `PersonalChallengesActivity`.

Three types: `raise_baseline`, `study_break_boost`, `campus_explorer`.

The screen is deliberately a thin view. Targets, baselines, progress, rewards, and completion are never recalculated on the phone. The only action that changes anything is accepting a challenge (`acceptPersonalChallenge`), which the server validates and freezes. Parsing is defensive: a malformed payload falls back to safe defaults instead of crashing.

Client entry points are `FirebaseServerApi.getMyPersonalChallenges()` and `acceptPersonalChallenge()`.

> **Note:** the challenge callables are called by the client but aren't in the included `index.ts` snapshot — they live in a newer functions revision.

---

## Anti-cheat

`AntiCheatManager` is a facade over two independent detectors. Both only *read* the counter's prefs; neither ever modifies `StepCounterZC`.

**`TimeIntegrityMonitor`** — spots system-clock tampering by comparing the user-changeable wall clock (`System.currentTimeMillis`) against the monotonic `SystemClock.elapsedRealtime` within one boot session. Divergence beyond normal NTP drift means tampering. It also flags a saved `lastSaveMs` that sits in the future, which means the clock was rewound between sessions. States: `OK`, `FIRST_RUN`, `REBOOT`, and tamper states with counters.

**`DataIntegrityMonitor`** — spots edits to `stepzc_prefs` made outside the app (root tools, ADB restore, prefs editors) using an HMAC-SHA256 tag over the step values. The key lives in the Android Keystore (`goforit_stepdata_hmac_v1`, sign/verify only), so even root can't forge a tag. Legitimate in-app writes re-sign automatically via `startMonitoring()`. A missing tag file alongside existing data also counts as tampering.

`snapshot()` returns both reports, so upload paths can hold back totals they don't trust.

`DeviceIdentity` gives each install a random UUID plus a readable device name, used by the server's device-trust flow. Reinstalling produces a fresh identity on purpose.

---

## Offline maps and routes

The `feature.map` package gives a complete offline walking-route experience for Israel.

| Component | Role |
|---|---|
| `MapAndRoutesActivity` | The map screen: start/destination autocomplete, preference sliders, bottom sheet, route layers, 3D buildings, live location. |
| `MbTilesServer` | A small NanoHTTPD server on port 8080 serving vector tiles from `assets/israel.mbtiles` (auto-detects TMS y-flip). |
| `GhGraphInstaller` / `GraphDataInstaller` | Unzip the bundled GraphHopper foot graph into app storage. |
| `OfflineRouter` | Loads the graph and computes offline foot routes. `RoutePrefs` carries the slider settings and extra-distance budget. |
| `PoiDbInstaller` / `PoiRepository` | Install and query the SQLite POI database (H3-indexed, with a lat/lon fallback), bucketed by category. |
| `H3OpeningScorer` | Scores possible detours using Uber H3 grid disks: finds POI-rich neighboring cells inside a bearing cone and distance budget, then inserts the best one into the route. |
| `GeocoderApi` | The only online piece — Komoot Photon autocomplete, biased to Israel, Hebrew names where OSM has them. |
| `IsraelBounds` | Camera and bounds constraints. |
| `RoutePlanner` | Empty placeholder, reserved for future orchestration. |

The sliders let you trade distance for pleasantness: how much you want parks, quiet residential streets, or busy areas, plus how many extra kilometres you're willing to walk for it.

---

## Backend

`functions/src/index.ts` (~1,500 lines, TypeScript, Cloud Functions v2, region `us-central1`, Luxon for time, default timezone `Asia/Jerusalem`).

**Auth trigger:** `onAuthUserCreate` creates the `users/{uid}` document.

**Callable functions**

| Function | Purpose |
|---|---|
| `registerFcmToken` | Save the device's FCM token. |
| `getMyProfile` / `updateMyProfile` | Read and update your own profile (name, faculty, timezone). |
| `getPublicUserProfile` | Sanitized profile for competitor screens. |
| `updateQuietHours` | Set the notification quiet window (default 22:00–08:00). |
| `uploadStepInterval` | Write one 4-hour bucket. Idempotent per `sessionId`; recomputes daily points and updates cumulative totals and the live leaderboard. |
| `recordBonusVisit` | Award a BLE station visit, validated against `bonus_stations`. |
| `syncCollegeAreaSteps` | Credit campus steps at 10 bonus points per newly accepted step. |

**Scheduled jobs**

| Function | Schedule | Purpose |
|---|---|---|
| `finalizeDay` | `30 0 * * *` | Freeze yesterday's leaderboard and faculty standings, schedule reminders. |
| `dispatchNotificationJobs` | `*/15 * * * *` | Send up to 50 due jobs (`PENDING`, `sendAt <= now`) via FCM, honoring quiet hours. |
| `finalizeMonth` | monthly | Monthly aggregation. |

On the client, `FirebaseServerApi` (a Kotlin `object`) wraps email/password auth and every callable, plus helpers for the current `dayKey` and interval index. `firebaseMessagingService` displays incoming reminders on a high-importance `reminders` channel and respects the Android 13+ `POST_NOTIFICATIONS` permission.

---

## Screens

| Screen | What it shows |
|---|---|
| `LoginActivity` / `RegisterActivity` | Email/password auth. |
| `MainActivity` | Navigation-drawer host (Steps, Home, Gallery, Slideshow, Sign out) and owner of the permission onboarding chain. Shows a status chip on the Steps destination. |
| `StepsFragment` + `StepViewModel` | Live step count, cadence, motion mode (`ic_mode_*` icons), speed. |
| `HomeFragment` / `GalleryFragment` / `SlideshowFragment` | Drawer destinations. Home also holds the QA entry card when authorized. |
| `StatisticsActivity` + `HourlyStepsChartView` | Custom-drawn hourly and daily charts from the local stores. |
| `LeaderboardActivity` (+ adapter, entry model) | Daily rankings with paging, day selection, and a faculty tab. |
| `ProfileActivity` / `CompetitorProfileActivity` | Your profile and other users' public profiles. |
| `PersonalChallengesActivity` | Challenge cards with countdowns, status pills, and the accept flow. |
| `QaActivity` | Hidden acceptance-test console. |

Layouts use a `feature_*` / `nav_*` naming convention. Two nav graphs (`mobile_navigation.xml` and `nav_graph*.xml`) drive the drawer and secondary flows.

---

## QA tools

- **`QaAccess`** — a hard gate: the QA UI only appears and only opens when both the Firebase email (`goforit.qa@test.com`) and the UID match the dedicated tester account. Every QA screen re-checks, which blocks entry via an explicit Intent.
- **`QaActivity`** — the in-app acceptance-test console (service state, stores, manual upload triggers, and so on).
- **`grantQaTester.cjs` / `seedLeaderboardQa.cjs`** — Node scripts using Firebase Admin to grant the QA role and seed leaderboard test data.

---

## Permissions

Declared in `AndroidManifest.xml`, scoped by SDK version where relevant.

- **Motion/health:** `ACTIVITY_RECOGNITION`, `BODY_SENSORS` (+ background), `HIGH_SAMPLING_RATE_SENSORS`
- **Location:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`
- **Bluetooth:** `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+); legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` (≤ API 30)
- **Foreground services:** `FOREGROUND_SERVICE` plus the typed variants `LOCATION`, `HEALTH`, `DATA_SYNC`, `CONNECTED_DEVICE`
- **Other:** `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

Registered components: 11 activities, `StepService`, `BleAdvertScanService`, `firebaseMessagingService`, `BootReceiver`, and WorkManager's foreground-service override.

---

## Project structure

```
app/src/main/java/com/example/goforitGit/
├── core/
│   ├── service/                StepService, BleAdvertScanService
│   ├── data/
│   │   ├── StepsData/          StepBus, StepRepository, StepHistoryStore,
│   │   │                       DailyStepsStore
│   │   └── FirebaseData/       FirebaseServerApi, firebaseMessagingService
│   └── util/
│       ├── StepsUtils/         StepCounterZC, CollegeZoneChecker
│       ├── FourHourBuckets/    FourHourBucketsSinceBoot, Scheduler, Worker
│       ├── TrackingLifecycle/  TrackingHeartbeat, TrackingPrefs,
│       │                       TrackingHealthWorker, TrackingServiceManager,
│       │                       Trackingpermissions, Onboardingprefs
│       ├── AntiCheat/          AntiCheatManager, TimeIntegrityMonitor,
│       │                       DataIntegrityMonitor
│       ├── DeviceSecurity/     DeviceIdentity
│       └── Receivers/          BootReceiver
├── tracking_lifecycle/         TrackingRestartWorker
├── feature/
│   ├── auth/ui/                LoginActivity, RegisterActivity
│   ├── map/ (ui + data)        MapAndRoutesActivity, OfflineRouter, MbTilesServer,
│   │                           H3OpeningScorer, PoiRepository, installers,
│   │                           GeocoderApi, IsraelBounds, RoutePlanner
│   ├── leaderboard/ui/         LeaderboardActivity/Adapter/Entry,
│   │                           FacultyStanding(+Adapter), CompetitorProfileActivity
│   ├── challenges/ui/          PersonalChallengesActivity, Personalchallengemodels
│   ├── statistics/ui/          StatisticsActivity, HourlyStepsChartView
│   ├── profile/ui/             ProfileActivity
│   └── qa/ui/                  QaActivity, QaAccess
├── navigation/                 MainActivity
└── ui/                         Home / Gallery / Slideshow / Steps + ViewModels

functions/src/index.ts          All Cloud Functions
tools/                          grantQaTester.cjs, seedLeaderboardQa.cjs
```

---

## Building and running

**You'll need**

- Android Studio with a recent AGP, and Kotlin. The root `build.gradle.kts` applies `com.google.gms.google-services 4.4.4` and `com.chaquo.python 17.0.0` (Chaquopy, used at build time).
- A Firebase project with Email/Password auth, Firestore, Cloud Functions (`us-central1`), and FCM. Put your `google-services.json` in `app/`.
- Node.js for the `functions/` workspace (Functions v2 + `luxon`). Deploy with `firebase deploy --only functions`.

**Bundled assets** (large binaries, not in this source snapshot)

| Asset | Purpose |
|---|---|
| `assets/israel.mbtiles` | Vector tiles for the offline map |
| `assets/gh/israel_foot_graph_1.zip` | GraphHopper foot-routing graph |
| `assets/poi_h3.db` (or a DB under `assets/poi/`) | POI database |
| `assets/college_polygon.json` | Campus geofence |

**Main libraries:** MapLibre GL, GraphHopper core, Uber H3, NanoHTTPD, Play Services Location, WorkManager, Firebase (Auth / Functions / Messaging), Material Components, AndroidX Navigation.

**Bonus-station hardware:** Raspberry Pi 5 units advertising BLE manufacturer data under company ID `0xFFFF` with the device name `RPi5 Beacon`.

**To run:** build and install, register or log in, then walk through the permission onboarding (activity recognition → location → background location → notifications → battery-optimization exemption). Tracking then starts as a persistent foreground service.

---

## Design principles

- **The server decides.** Points, leaderboards, challenge math, and bonus validation all happen in Cloud Functions. The client uploads raw measurements and renders whatever comes back.
- **Uploads are safe to retry.** Session IDs, per-interval sent flags, and the `uploadedMask` bitmask make every upload idempotent and resumable.
- **One restart path.** Boot, watchdog, and crash recovery all go through the same foreground-elevated `TrackingRestartWorker`.
- **Detectors stay out of the way.** Anti-cheat modules only read the counter's data, so the counting code stays independent of them.
- **Offline first.** Tiles, routing graph, and POIs ship inside the APK. Only name autocomplete needs a connection.
