// functions/src/index.ts
import * as admin from "firebase-admin";
import * as crypto from "crypto";
import { DateTime, IANAZone } from "luxon";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import * as functionsV1 from "firebase-functions/v1";

admin.initializeApp();

const db = admin.firestore();
const messaging = admin.messaging();

const FUNCTIONS_REGION = "us-central1";
const DEFAULT_TZ = "Asia/Jerusalem";

const DEFAULT_QUIET_START_HOUR = 22;
const DEFAULT_QUIET_END_HOUR = 8;
const ALL_INTERVALS = [0, 1, 2, 3, 4, 5] as const;

// QA-only BLE acceptance-test configuration.
// This path never awards points and is available only to users whose Firebase
// Auth token contains the custom claim `qaTester: true`.
const QA_BONUS_STATION_ID = "bonus station";
const QA_RUN_ID_PATTERN = /^[A-Za-z0-9_-]{3,80}$/;

// Campus-step bonus.
// Base activity is worth 1 point per 100 steps (see calcStepPoints), i.e. ~0.01
// points/step. The old model awarded a FLAT 10 points for every qualified step
// inside the college polygon, making a single campus step worth as much as
// 1,000 ordinary steps and rendering the daily ranking meaningless.
//
// New model: a qualified campus step earns a *bonus* of 1 point per
// COLLEGE_AREA_STEPS_PER_BONUS_POINT steps, on top of the normal step value.
// With 100, a campus step is worth ~2x a normal step. Lower the divisor to make
// campus time more rewarding (e.g. 50 -> ~3x), raise it to make it gentler.
const COLLEGE_AREA_STEPS_PER_BONUS_POINT = 100;

// Personal Challenges — server-authoritative configuration
//
// All targets, percentages and rewards live ONLY on the server. The Android
// client never computes or trusts these values; it renders what the server
// returns. Progress is always derived from trusted server data:
//   - valid campus steps  -> users/{uid}/daily/{dayKey}.collegeAreaQualifiedSteps
//   - verified BLE visit  -> users/{uid}/bonus_visits/{dayKey}
// -----------------------------------------------------------------------------

const PC_CHALLENGE_TYPES = ["raise_baseline", "study_break_boost", "campus_explorer"] as const;
type PcChallengeType = (typeof PC_CHALLENGE_TYPES)[number];

const PC_DIFFICULTIES = ["easy", "medium", "hard"] as const;
type PcDifficulty = (typeof PC_DIFFICULTIES)[number];

// Raise Your Baseline: percentage above the 7-day baseline. Reward scales with
// difficulty (Easy 200, Medium 225, Hard 250); the percentage sets how far above
// baseline the target sits.
const PC_BASELINE_TIERS: Record<PcDifficulty, { percent: number; reward: number }> = {
  easy: { percent: 0.1, reward: 200 },
  medium: { percent: 0.2, reward: 225 },
  hard: { percent: 0.3, reward: 250 },
};

// Baseline needs at least this many usable past days of verified campus activity.
const PC_BASELINE_MIN_DAYS = 3;
const PC_BASELINE_LOOKBACK_DAYS = 7;

// Study-Break Boost.
const PC_STUDY_BREAK_TARGET_STEPS = 800;
const PC_STUDY_BREAK_REWARD = 200;
const PC_STUDY_BREAK_MIN_DAYS = 3;
const PC_STUDY_BREAK_LOOKBACK_DAYS = 7;

// Campus Explorer.
const PC_CAMPUS_TARGET_STEPS = 1200;
const PC_CAMPUS_STATION_GOAL = 1;
const PC_CAMPUS_REWARD = 200;

// -----------------------------------------------------------------------------

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

function requireUid(request: { auth?: { uid?: string } | null }): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Login required.");
  return uid;
}

function normalizeTimezone(tzRaw: unknown): string {
  const tz = typeof tzRaw === "string" ? tzRaw.trim() : "";
  if (tz.length === 0) return DEFAULT_TZ;
  return IANAZone.isValidZone(tz) ? tz : DEFAULT_TZ;
}

function toDayKey(dt: DateTime): string {
  return dt.toISODate() ?? dt.toFormat("yyyy-MM-dd");
}

function validateDayKey(dayKey: unknown): string {
  if (typeof dayKey !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(dayKey)) {
    throw new HttpsError("invalid-argument", "dayKey must be YYYY-MM-DD");
  }
  return dayKey;
}

function validateInterval(x: unknown, name: string): number {
  if (typeof x !== "number" || !Number.isInteger(x) || x < 0 || x > 5) {
    throw new HttpsError("invalid-argument", `${name} must be 0..5`);
  }
  return x;
}

function validateHour(x: unknown, name: string): number {
  if (typeof x !== "number" || !Number.isInteger(x) || x < 0 || x > 23) {
    throw new HttpsError("invalid-argument", `${name} must be 0..23`);
  }
  return x;
}

function normalizeHour(x: unknown, fallback: number): number {
  return typeof x === "number" && Number.isInteger(x) && x >= 0 && x <= 23 ? x : fallback;
}

function validateFaculty(x: unknown): string {
  const faculty = typeof x === "string" ? x.trim() : "";
  if (faculty.length > 80) {
    throw new HttpsError("invalid-argument", "faculty must be 0..80 chars.");
  }
  return faculty;
}

/**
 * Returns a validated QA run ID, or null when the caller requested the normal
 * production reward flow. Keeping this optional preserves every existing
 * recordBonusVisit client call.
 */
function validateOptionalQaRunId(value: unknown): string | null {
  if (value == null) return null;

  if (typeof value !== "string") {
    throw new HttpsError("invalid-argument", "qaRunId must be a string.");
  }

  const qaRunId = value.trim();
  if (!QA_RUN_ID_PATTERN.test(qaRunId)) {
    throw new HttpsError(
      "invalid-argument",
      "qaRunId must be 3..80 characters using only letters, numbers, underscores, or hyphens."
    );
  }

  return qaRunId;
}

function isAlreadyExistsError(error: unknown): boolean {
  const code = (error as { code?: unknown } | null)?.code;
  return (
    code === 6 ||
    code === "6" ||
    code === "already-exists" ||
    code === "ALREADY_EXISTS"
  );
}

function validateUsername(x: unknown): string {
  const username = typeof x === "string" ? x.trim() : "";
  if (username.length < 3 || username.length > 24) {
    throw new HttpsError("invalid-argument", "username must be 3..24 chars.");
  }
  // Allowed: letters, digits, spaces, and . _ - '
  // Must start and end with a letter or digit (no leading/trailing spaces).
  // Examples that pass: "naor zion", "Mary-Jane", "O'Brien", "john.doe"
  if (!/^[A-Za-z0-9][A-Za-z0-9 _.'\-]{1,22}[A-Za-z0-9]$/.test(username)) {
    throw new HttpsError(
      "invalid-argument",
      "username may contain letters, numbers, spaces, and . _ - '"
    );
  }
  return username;
}

function normalizeUsername(x: unknown): string {
  const username = typeof x === "string" ? x.trim() : "";
  // Collapse any run of internal whitespace to a single space so
  // "Maor  Mordo" and "Maor Mordo" reserve the same uniqueness key.
  return username.toLowerCase().replace(/\s+/g, " ");
}

function validateEmailAddress(x: unknown): string {
  const email = typeof x === "string" ? x.trim() : "";
  if (email.length === 0) {
    throw new HttpsError("invalid-argument", "email is required.");
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new HttpsError("invalid-argument", "email is invalid.");
  }
  return email;
}

function validateProfileImageUrl(x: unknown): string {
  const url = typeof x === "string" ? x.trim() : "";
  if (url.length === 0) return "";
  if (url.length > 2000) {
    throw new HttpsError("invalid-argument", "profileImageUrl is too long.");
  }
  if (!url.startsWith("https://")) {
    throw new HttpsError("invalid-argument", "profileImageUrl must be an https URL.");
  }
  return url;
}

function validateSteps(x: unknown): number {
  if (typeof x !== "number" || !Number.isInteger(x) || x < 0 || x > 300000) {
    throw new HttpsError("invalid-argument", "stepsTotal must be non-negative int (reasonable range).");
  }
  return x;
}

function validateQualifiedStepsTotal(x: unknown): number {
  if (typeof x !== "number" || !Number.isInteger(x) || x < 0 || x > 300000) {
    throw new HttpsError(
      "invalid-argument",
      "qualifiedStepsTotal must be non-negative int (reasonable range)."
    );
  }
  return x;
}

function clampSix(arr: unknown): number[] {
  const base = Array.isArray(arr) ? arr : [];
  const out = new Array<number>(6).fill(0);
  for (let i = 0; i < 6; i++) {
    const v = (base as unknown[])[i];
    out[i] = typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0;
  }
  return out;
}

function calcStepPoints(totalSteps: number): number {
  return Math.floor(totalSteps / 100);
}

/**
 * Bonus points earned for a CUMULATIVE number of qualified campus steps.
 *
 * Computing the bonus from the cumulative total (rather than per-delta) keeps
 * uploads idempotent: re-submitting the same total yields the same bonus, and
 * integer rounding never drifts across many small syncs.
 */
function calcCollegeAreaBonus(qualifiedSteps: number): number {
  if (!Number.isFinite(qualifiedSteps) || qualifiedSteps <= 0) return 0;
  return Math.floor(qualifiedSteps / COLLEGE_AREA_STEPS_PER_BONUS_POINT);
}

function intervalStart(dayKey: string, intervalIndex: number, tz: string): DateTime {
  return DateTime.fromISO(dayKey, { zone: tz }).startOf("day").plus({ hours: intervalIndex * 4 });
}

function isUploaded(mask: number, intervalIndex: number): boolean {
  return (mask & (1 << intervalIndex)) !== 0;
}

function intervalReminderHour(intervalIndex: number): number {
  return (intervalIndex * 4) % 24;
}

function isHourInsideQuietHours(hour: number, quietStartHour: number, quietEndHour: number): boolean {
  if (quietStartHour === quietEndHour) return false;

  if (quietStartHour < quietEndHour) {
    return hour >= quietStartHour && hour < quietEndHour;
  }

  return hour >= quietStartHour || hour < quietEndHour;
}

function isReminderEligibleInterval(
  intervalIndex: number,
  quietStartHour: number,
  quietEndHour: number
): boolean {
  const reminderHour = intervalReminderHour(intervalIndex);
  return !isHourInsideQuietHours(reminderHour, quietStartHour, quietEndHour);
}

function allowedReminderIntervals(quietStartHour: number, quietEndHour: number): number[] {
  return ALL_INTERVALS.filter((i) => isReminderEligibleInterval(i, quietStartHour, quietEndHour));
}

function validReminderCandidates(
  uploadedMask: number,
  quietStartHour: number,
  quietEndHour: number
): number[] {
  return ALL_INTERVALS.filter(
    (i) => isUploaded(uploadedMask, i) && isReminderEligibleInterval(i, quietStartHour, quietEndHour)
  );
}

function normalizePreferredInterval(
  value: unknown,
  quietStartHour: number,
  quietEndHour: number
): number | null {
  if (!Number.isInteger(value)) return null;
  const interval = value as number;
  if (interval < 0 || interval > 5) return null;
  return isReminderEligibleInterval(interval, quietStartHour, quietEndHour) ? interval : null;
}

function firstAllowedReminderInterval(quietStartHour: number, quietEndHour: number): number {
  const allowed = allowedReminderIntervals(quietStartHour, quietEndHour);
  return allowed.length > 0 ? allowed[0] : 2;
}

function pickBestInterval(stepsByInterval: number[], candidates: number[]): number {
  let best = candidates[0];
  for (let i = 1; i < candidates.length; i++) {
    const cur = candidates[i];
    if (stepsByInterval[cur] > stepsByInterval[best]) best = cur;
  }
  return best;
}

function pickWorstInterval(stepsByInterval: number[], candidates: number[]): number {
  let worst = candidates[0];
  for (let i = 1; i < candidates.length; i++) {
    const cur = candidates[i];
    if (stepsByInterval[cur] < stepsByInterval[worst]) worst = cur;
  }
  return worst;
}

function nextAwakeBoundary(dt: DateTime, quietStartHour: number, quietEndHour: number): DateTime {
  const normalized = dt.set({ second: 0, millisecond: 0 });

  if (!isHourInsideQuietHours(normalized.hour, quietStartHour, quietEndHour)) {
    return normalized;
  }

  if (quietStartHour === quietEndHour) {
    return normalized;
  }

  if (quietStartHour < quietEndHour) {
    let wake = normalized.set({
      hour: quietEndHour,
      minute: 5,
      second: 0,
      millisecond: 0,
    });

    if (wake.toMillis() <= normalized.toMillis()) {
      wake = wake.plus({ days: 1 });
    }

    return wake;
  }

  if (normalized.hour >= quietStartHour) {
    return normalized
      .plus({ days: 1 })
      .startOf("day")
      .set({ hour: quietEndHour, minute: 5, second: 0, millisecond: 0 });
  }

  return normalized
    .startOf("day")
    .set({ hour: quietEndHour, minute: 5, second: 0, millisecond: 0 });
}

function computeSendAt(
  dayKey: string,
  intervalIndex: number,
  tz: string,
  quietStartHour: number,
  quietEndHour: number
): admin.firestore.Timestamp {
  const raw = intervalStart(dayKey, intervalIndex, tz)
    .plus({ minutes: 5 })
    .set({ second: 0, millisecond: 0 });

  const scheduled = nextAwakeBoundary(raw, quietStartHour, quietEndHour);
  return admin.firestore.Timestamp.fromDate(scheduled.toJSDate());
}

function isTokenInvalid(code: string): boolean {
  return (
    code.includes("registration-token-not-registered") ||
    code.includes("invalid-registration-token") ||
    code.includes("messaging/registration-token-not-registered") ||
    code.includes("messaging/invalid-registration-token")
  );
}

type LeaderboardRow = {
  uid: string;
  totalPoints: number;
  totalSteps: number;
  bonusPoints: number;

  // Public profile fields that are safe to show on the leaderboard
  // and on the read-only competitor profile screen.
  username: string;
  profileImageUrl: string;
  faculty: string;
};

function compareLeaderboardRows(a: LeaderboardRow, b: LeaderboardRow): number {
  if (b.totalPoints !== a.totalPoints) return b.totalPoints - a.totalPoints;
  if (b.totalSteps !== a.totalSteps) return b.totalSteps - a.totalSteps;
  if (b.bonusPoints !== a.bonusPoints) return b.bonusPoints - a.bonusPoints;
  return a.uid.localeCompare(b.uid);
}

// -----------------------------------------------------------------------------
// Faculty standings (per-day aggregation of the individual leaderboard)
// -----------------------------------------------------------------------------

type FacultyStanding = {
  faculty: string;
  rank: number;
  totalPoints: number;
  totalSteps: number;
  bonusPoints: number;
  memberCount: number;
  averagePoints: number;
};

const GENERAL_FACULTY = "General";

function normalizeFacultyName(faculty: string): string {
  const trimmed = typeof faculty === "string" ? faculty.trim() : "";
  return trimmed.length > 0 ? trimmed : GENERAL_FACULTY;
}

/** Firestore doc IDs may not contain "/" and may not be "." or "..". */
function facultyDocId(faculty: string): string {
  const safe = normalizeFacultyName(faculty).replace(/\//g, "_");
  return safe === "." || safe === ".." ? GENERAL_FACULTY : safe;
}

/**
 * Rolls the individual leaderboard up into one row per faculty.
 * Faculties are ranked by total points (tie-break: average points, then steps),
 * and both the total and the per-member average are stored so the client can
 * present a fair picture regardless of faculty size.
 */
function aggregateFacultyStandings(leaderboard: LeaderboardRow[]): FacultyStanding[] {
  const byFaculty = new Map<string, FacultyStanding>();

  for (const row of leaderboard) {
    const faculty = normalizeFacultyName(row.faculty);
    const cur =
      byFaculty.get(faculty) ??
      {
        faculty,
        rank: 0,
        totalPoints: 0,
        totalSteps: 0,
        bonusPoints: 0,
        memberCount: 0,
        averagePoints: 0,
      };

    cur.totalPoints += row.totalPoints;
    cur.totalSteps += row.totalSteps;
    cur.bonusPoints += row.bonusPoints;
    cur.memberCount += 1;
    byFaculty.set(faculty, cur);
  }

  const standings = Array.from(byFaculty.values());
  for (const s of standings) {
    s.averagePoints = s.memberCount > 0 ? Math.round(s.totalPoints / s.memberCount) : 0;
  }

  standings.sort((a, b) => {
    if (b.totalPoints !== a.totalPoints) return b.totalPoints - a.totalPoints;
    if (b.averagePoints !== a.averagePoints) return b.averagePoints - a.averagePoints;
    if (b.totalSteps !== a.totalSteps) return b.totalSteps - a.totalSteps;
    return a.faculty.localeCompare(b.faculty);
  });

  standings.forEach((s, idx) => (s.rank = idx + 1));
  return standings;
}

async function writeFacultyStandingsForDay(
  dayKey: string,
  leaderboard: LeaderboardRow[]
): Promise<void> {
  const standings = aggregateFacultyStandings(leaderboard);
  const col = db.collection("leaderboards_daily").doc(dayKey).collection("faculties");
  const existingSnap = await col.get();

  const existingIds = new Set(existingSnap.docs.map((d) => d.id));
  const wantedIds = new Set(standings.map((s) => facultyDocId(s.faculty)));

  const ops: Array<(batch: admin.firestore.WriteBatch) => void> = [];

  for (const doc of existingSnap.docs) {
    if (!wantedIds.has(doc.id)) {
      ops.push((batch) => batch.delete(doc.ref));
    }
  }

  for (const s of standings) {
    const id = facultyDocId(s.faculty);
    ops.push((batch) => {
      const payload: Record<string, unknown> = {
        faculty: s.faculty,
        dayKey,
        rank: s.rank,
        totalPoints: s.totalPoints,
        totalSteps: s.totalSteps,
        bonusPoints: s.bonusPoints,
        memberCount: s.memberCount,
        averagePoints: s.averagePoints,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      };

      if (!existingIds.has(id)) {
        payload.createdAt = admin.firestore.FieldValue.serverTimestamp();
      }

      batch.set(col.doc(id), payload, { merge: true });
    });
  }

  const chunkSize = 400;
  for (let i = 0; i < ops.length; i += chunkSize) {
    const batch = db.batch();
    for (const op of ops.slice(i, i + chunkSize)) {
      op(batch);
    }
    await batch.commit();
  }
}

async function writeLeaderboardForDay(dayKey: string, leaderboard: LeaderboardRow[]): Promise<void> {
  const entriesCol = db.collection("leaderboards_daily").doc(dayKey).collection("entries");
  const existingSnap = await entriesCol.get();

  const existingIds = new Set(existingSnap.docs.map((d) => d.id));
  const wantedIds = new Set(leaderboard.map((e) => e.uid));

  const ops: Array<(batch: admin.firestore.WriteBatch) => void> = [];

  for (const doc of existingSnap.docs) {
    if (!wantedIds.has(doc.id)) {
      ops.push((batch) => batch.delete(doc.ref));
    }
  }

  leaderboard.forEach((e, idx) => {
    ops.push((batch) => {
      const payload: Record<string, unknown> = {
        uid: e.uid,
        dayKey,
        totalPoints: e.totalPoints,
        totalSteps: e.totalSteps,
        bonusPoints: e.bonusPoints,
        rank: idx + 1,

        // Public profile snapshot.
        // This intentionally excludes private fields such as email, timezone,
        // quiet hours, reminder preferences, and notification settings.
        username: e.username,
        profileImageUrl: e.profileImageUrl,
        faculty: e.faculty,

        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      };

      if (!existingIds.has(e.uid)) {
        payload.createdAt = admin.firestore.FieldValue.serverTimestamp();
      }

      batch.set(entriesCol.doc(e.uid), payload, { merge: true });
    });
  });

  const chunkSize = 400;
  for (let i = 0; i < ops.length; i += chunkSize) {
    const batch = db.batch();
    for (const op of ops.slice(i, i + chunkSize)) {
      op(batch);
    }
    await batch.commit();
  }

  // Keep the per-faculty ranking in sync with the individual entries we just wrote.
  await writeFacultyStandingsForDay(dayKey, leaderboard);
}

async function recalculateLeaderboardForDay(dayKey: string): Promise<void> {
  const usersSnap = await db.collection("users").get();
  const leaderboard: LeaderboardRow[] = [];

  for (const u of usersSnap.docs) {
    const uid = u.id;
    const userData = u.data() ?? {};

    const username = typeof userData.username === "string" ? userData.username : "";
    const profileImageUrl =
      typeof userData.profileImageUrl === "string" ? userData.profileImageUrl : "";
    const faculty = typeof userData.faculty === "string" ? userData.faculty : "";

    const dailySnap = await db.doc(`users/${uid}/daily/${dayKey}`).get();
    if (!dailySnap.exists) continue;

    const daily = dailySnap.data() ?? {};
    const stepsByInterval = clampSix(daily.stepsByInterval);
    const totalSteps = stepsByInterval.reduce((a, b) => a + b, 0);
    const bonusPoints = Number.isInteger(daily.bonusPoints) ? (daily.bonusPoints as number) : 0;
    const totalPoints = calcStepPoints(totalSteps) + bonusPoints;

    leaderboard.push({
      uid,
      totalPoints,
      totalSteps,
      bonusPoints,
      username,
      profileImageUrl,
      faculty,
    });
  }

  leaderboard.sort(compareLeaderboardRows);
  await writeLeaderboardForDay(dayKey, leaderboard);
}

// -----------------------------------------------------------------------------
// User schema / defaults
// -----------------------------------------------------------------------------

type UserDocData = {
  uid: string;
  createdAt: admin.firestore.FieldValue | admin.firestore.Timestamp;
  updatedAt: admin.firestore.FieldValue | admin.firestore.Timestamp;
  username: string;
  usernameNormalized: string;
  email: string;
  profileImageUrl: string;
  timezone: string;
  lowActivityNudgeEnabled: boolean;
  preferredActiveInterval: number | null;
  preferredInactiveInterval: number | null;
  quietHoursStartHour: number;
  quietHoursEndHour: number;
  faculty: string;
  cumulativeTotalPoints: number;
  cumulativeTotalSteps: number;
  cumulativeBonusPoints: number;
};

function buildUserDefaults(
  uid: string,
  email = ""
): Omit<UserDocData, "createdAt" | "updatedAt"> {
  return {
    uid,
    username: "",
    usernameNormalized: "",
    email,
    profileImageUrl: "",
    timezone: DEFAULT_TZ,
    lowActivityNudgeEnabled: true,
    preferredActiveInterval: null,
    preferredInactiveInterval: null,
    quietHoursStartHour: DEFAULT_QUIET_START_HOUR,
    quietHoursEndHour: DEFAULT_QUIET_END_HOUR,
    faculty: "",
    cumulativeTotalPoints: 0,
    cumulativeTotalSteps: 0,
    cumulativeBonusPoints: 0,
  };
}

async function ensureUserDoc(uid: string): Promise<void> {
  const userRef = db.doc(`users/${uid}`);

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const data = (snap.data() ?? {}) as Record<string, unknown>;

    const storedUsername = typeof data.username === "string" ? data.username : "";
    const normalizedStoredUsername = normalizeUsername(storedUsername);

    const patch: Record<string, unknown> = {
      uid,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    if (!snap.exists || data.createdAt == null) {
      patch.createdAt = admin.firestore.FieldValue.serverTimestamp();
    }

    if (typeof data.username !== "string") patch.username = "";
    if (
      typeof data.usernameNormalized !== "string" ||
      data.usernameNormalized !== normalizedStoredUsername
    ) {
      patch.usernameNormalized = normalizedStoredUsername;
    }
    if (typeof data.email !== "string") patch.email = "";
    if (typeof data.profileImageUrl !== "string") patch.profileImageUrl = "";

    if (typeof data.timezone !== "string") patch.timezone = DEFAULT_TZ;
    if (typeof data.lowActivityNudgeEnabled !== "boolean") patch.lowActivityNudgeEnabled = true;

    const quietHoursStartHour = normalizeHour(data.quietHoursStartHour, DEFAULT_QUIET_START_HOUR);
    const quietHoursEndHour = normalizeHour(data.quietHoursEndHour, DEFAULT_QUIET_END_HOUR);

    if (data.quietHoursStartHour !== quietHoursStartHour) {
      patch.quietHoursStartHour = quietHoursStartHour;
    }
    if (data.quietHoursEndHour !== quietHoursEndHour) {
      patch.quietHoursEndHour = quietHoursEndHour;
    }

    if (!Number.isInteger(data.preferredActiveInterval) && data.preferredActiveInterval !== null) {
      patch.preferredActiveInterval = null;
    }
    if (!Number.isInteger(data.preferredInactiveInterval) && data.preferredInactiveInterval !== null) {
      patch.preferredInactiveInterval = null;
    }

    if (typeof data.faculty !== "string") patch.faculty = "";

    if (typeof data.cumulativeTotalPoints !== "number") patch.cumulativeTotalPoints = 0;
    if (typeof data.cumulativeTotalSteps !== "number") patch.cumulativeTotalSteps = 0;
    if (typeof data.cumulativeBonusPoints !== "number") patch.cumulativeBonusPoints = 0;

    tx.set(userRef, patch, { merge: true });
  });
}

async function getUserProfile(uid: string): Promise<{
  username: string;
  email: string;
  profileImageUrl: string;
  timezone: string;
  lowActivityNudgeEnabled: boolean;
  preferredActiveInterval: number | null;
  preferredInactiveInterval: number | null;
  quietHoursStartHour: number;
  quietHoursEndHour: number;
  faculty: string;
  cumulativeTotalPoints: number;
  cumulativeTotalSteps: number;
  cumulativeBonusPoints: number;
}> {
  const snap = await db.doc(`users/${uid}`).get();
  const data = snap.data() ?? {};

  const username = typeof data.username === "string" ? data.username : "";
  const email = typeof data.email === "string" ? data.email : "";
  const profileImageUrl = typeof data.profileImageUrl === "string" ? data.profileImageUrl : "";

  const timezone = normalizeTimezone(data.timezone);
  const lowActivityNudgeEnabled =
    typeof data.lowActivityNudgeEnabled === "boolean" ? data.lowActivityNudgeEnabled : true;

  const quietHoursStartHour = normalizeHour(data.quietHoursStartHour, DEFAULT_QUIET_START_HOUR);
  const quietHoursEndHour = normalizeHour(data.quietHoursEndHour, DEFAULT_QUIET_END_HOUR);

  const preferredActiveInterval = normalizePreferredInterval(
    data.preferredActiveInterval,
    quietHoursStartHour,
    quietHoursEndHour
  );

  const preferredInactiveInterval = normalizePreferredInterval(
    data.preferredInactiveInterval,
    quietHoursStartHour,
    quietHoursEndHour
  );

  const faculty = typeof data.faculty === "string" ? data.faculty : "";

  const cumulativeTotalPoints =
    typeof data.cumulativeTotalPoints === "number" ? data.cumulativeTotalPoints : 0;
  const cumulativeTotalSteps =
    typeof data.cumulativeTotalSteps === "number" ? data.cumulativeTotalSteps : 0;
  const cumulativeBonusPoints =
    typeof data.cumulativeBonusPoints === "number" ? data.cumulativeBonusPoints : 0;

  return {
    username,
    email,
    profileImageUrl,
    timezone,
    lowActivityNudgeEnabled,
    preferredActiveInterval,
    preferredInactiveInterval,
    quietHoursStartHour,
    quietHoursEndHour,
    faculty,
    cumulativeTotalPoints,
    cumulativeTotalSteps,
    cumulativeBonusPoints,
  };
}

async function updatePublicProfileSnapshotInRecentLeaderboards(
  uid: string,
  publicFields: { username: string; profileImageUrl: string; faculty: string },
  daysBack = 14
): Promise<void> {
  const now = DateTime.now().setZone(DEFAULT_TZ);
  const refs: admin.firestore.DocumentReference[] = [];

  for (let i = 0; i <= daysBack; i++) {
    const dayKey = toDayKey(now.minus({ days: i }));
    refs.push(db.doc(`leaderboards_daily/${dayKey}/entries/${uid}`));
  }

  const snaps = await db.getAll(...refs);
  const batch = db.batch();
  let writes = 0;

  for (const snap of snaps) {
    if (!snap.exists) continue;

    batch.set(
      snap.ref,
      {
        username: publicFields.username,
        profileImageUrl: publicFields.profileImageUrl,
        faculty: publicFields.faculty,
        publicProfileUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
    writes++;
  }

  if (writes > 0) {
    await batch.commit();
  }
}

// -----------------------------------------------------------------------------
// Auth trigger
// -----------------------------------------------------------------------------

export const onAuthUserCreate = functionsV1
  .region(FUNCTIONS_REGION)
  .auth.user()
  .onCreate(async (user) => {
    const uid = user.uid;
    const userRef = db.doc(`users/${uid}`);
    const defaults = buildUserDefaults(uid, user.email ?? "");

    await userRef.set(
      {
        ...defaults,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
  });

// -----------------------------------------------------------------------------
// Callable: registerFcmToken
// -----------------------------------------------------------------------------

export const registerFcmToken = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  await ensureUserDoc(uid);

  const token = (request.data?.token ?? null) as unknown;
  if (typeof token !== "string" || token.trim().length < 20) {
    throw new HttpsError("invalid-argument", "token is required.");
  }

  await db.doc(`users/${uid}/fcm_tokens/${token}`).set(
    {
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      platform: "android",
    },
    { merge: true }
  );

  return { ok: true };
});

// -----------------------------------------------------------------------------
// Multi-device concurrent login detection (2FA + login blocking)
// -----------------------------------------------------------------------------
//
// One "trusted device" per user at a time, tracked in the server-only doc
// users/{uid}/security/deviceTrust (see firestore.rules — clients cannot
// write this directly, only through the callables below). A user's first
// login ever, or a repeat login from the same device, trusts silently and
// changes nothing about today's login experience. A login attempt from a
// second device is blocked client-side until a 6-digit code — pushed via FCM
// to the already-trusted device, reusing the existing fcm_tokens/messaging
// pipeline — is confirmed here.

const DEVICE_VERIFICATION_TTL_MINUTES = 5;
const DEVICE_VERIFICATION_MAX_ATTEMPTS = 5;

function requireDeviceId(raw: unknown): string {
  if (typeof raw !== "string" || raw.trim().length < 8 || raw.trim().length > 128) {
    throw new HttpsError("invalid-argument", "deviceId is required.");
  }
  return raw.trim();
}

function normalizeDeviceName(raw: unknown): string {
  if (typeof raw !== "string" || raw.trim().length === 0) return "Unknown device";
  return raw.trim().slice(0, 80);
}

type DeviceTrustData = {
  trustedDeviceId: string | null;
  trustedDeviceName: string | null;
  trustedDeviceSince: admin.firestore.FieldValue | admin.firestore.Timestamp | null;
};

// -----------------------------------------------------------------------------
// Callable: checkDeviceTrust
// -----------------------------------------------------------------------------

export const checkDeviceTrust = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);
  await ensureUserDoc(uid);

  const deviceId = requireDeviceId(request.data?.deviceId);
  const deviceName = normalizeDeviceName(request.data?.deviceName);

  const trustRef = db.doc(`users/${uid}/security/deviceTrust`);
  const trustSnap = await trustRef.get();
  const trustData = (trustSnap.data() ?? {}) as Partial<DeviceTrustData>;
  const trustedDeviceId =
    typeof trustData.trustedDeviceId === "string" ? trustData.trustedDeviceId : null;

  if (trustedDeviceId === null || trustedDeviceId === deviceId) {
    await trustRef.set(
      {
        trustedDeviceId: deviceId,
        trustedDeviceName: deviceName,
        trustedDeviceSince: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
    return { ok: true, status: "trusted" };
  }

  // A different device already holds this account's trust: require a code
  // sent to that device before letting this one in.
  const code = crypto.randomInt(100000, 1000000).toString();
  const expiresAt = admin.firestore.Timestamp.fromMillis(
    Date.now() + DEVICE_VERIFICATION_TTL_MINUTES * 60_000
  );
  const verificationRef = db.collection(`users/${uid}/login_verifications`).doc();

  await verificationRef.set({
    code,
    requestingDeviceId: deviceId,
    requestingDeviceName: deviceName,
    status: "pending",
    attempts: 0,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    expiresAt,
  });

  const tokensSnap = await db.collection(`users/${uid}/fcm_tokens`).get();
  const tokens = tokensSnap.docs.map((d) => d.id);

  if (tokens.length > 0) {
    try {
      const res = await messaging.sendEachForMulticast({
        tokens,
        notification: {
          title: "New sign-in attempt",
          body: `"${deviceName}" is trying to sign in. Code: ${code}. Not you? Ignore this.`,
        },
        data: {
          type: "LOGIN_VERIFICATION",
          code,
          requestingDeviceName: deviceName,
        },
      });

      const toDelete: string[] = [];
      res.responses.forEach((r, idx) => {
        if (!r.success) {
          const errCode = (r.error as { code?: string } | undefined)?.code ?? "";
          if (isTokenInvalid(errCode)) toDelete.push(tokens[idx]);
        }
      });

      if (toDelete.length > 0) {
        const delBatch = db.batch();
        toDelete.forEach((t) => delBatch.delete(db.doc(`users/${uid}/fcm_tokens/${t}`)));
        await delBatch.commit();
      }
    } catch (err) {
      logger.error(`checkDeviceTrust: failed to push verification code for ${uid}`, err);
    }
  }

  return {
    ok: true,
    status: "verification_required",
    verificationId: verificationRef.id,
    expiresAtMs: expiresAt.toMillis(),
    hasTrustedDeviceToken: tokens.length > 0,
  };
});

// -----------------------------------------------------------------------------
// Callable: confirmLoginVerification
// -----------------------------------------------------------------------------

export const confirmLoginVerification = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  const verificationIdRaw = request.data?.verificationId;
  if (typeof verificationIdRaw !== "string" || verificationIdRaw.trim().length === 0) {
    throw new HttpsError("invalid-argument", "verificationId is required.");
  }
  const verificationId = verificationIdRaw.trim();

  const codeInput = request.data?.code;
  if (typeof codeInput !== "string" || !/^\d{6}$/.test(codeInput.trim())) {
    throw new HttpsError("invalid-argument", "code must be a 6-digit number.");
  }
  const code = codeInput.trim();

  const verificationRef = db.doc(`users/${uid}/login_verifications/${verificationId}`);

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(verificationRef);
    if (!snap.exists) {
      throw new HttpsError("not-found", "Verification request not found.");
    }

    const data = snap.data() as Record<string, unknown>;
    const status = typeof data.status === "string" ? data.status : "pending";
    const attempts = typeof data.attempts === "number" ? data.attempts : 0;
    const expiresAt = data.expiresAt as admin.firestore.Timestamp | undefined;
    const storedCode = typeof data.code === "string" ? data.code : "";

    if (status !== "pending") {
      return { ok: true, status: "expired" };
    }
    if (!expiresAt || expiresAt.toMillis() < Date.now()) {
      tx.set(verificationRef, { status: "expired" }, { merge: true });
      return { ok: true, status: "expired" };
    }
    if (attempts >= DEVICE_VERIFICATION_MAX_ATTEMPTS) {
      tx.set(verificationRef, { status: "expired" }, { merge: true });
      return { ok: true, status: "expired" };
    }

    if (code !== storedCode) {
      const attemptsUsed = attempts + 1;
      tx.set(verificationRef, { attempts: attemptsUsed }, { merge: true });
      return {
        ok: true,
        status: "invalid_code",
        attemptsRemaining: Math.max(0, DEVICE_VERIFICATION_MAX_ATTEMPTS - attemptsUsed),
      };
    }

    const requestingDeviceId =
      typeof data.requestingDeviceId === "string" ? data.requestingDeviceId : "";
    const requestingDeviceName =
      typeof data.requestingDeviceName === "string" ? data.requestingDeviceName : "Unknown device";

    tx.set(
      db.doc(`users/${uid}/security/deviceTrust`),
      {
        trustedDeviceId: requestingDeviceId,
        trustedDeviceName: requestingDeviceName,
        trustedDeviceSince: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
    tx.set(verificationRef, { status: "confirmed" }, { merge: true });

    return { ok: true, status: "trusted" };
  });
});

// -----------------------------------------------------------------------------
// Callable: checkDeviceStillTrusted
// -----------------------------------------------------------------------------

export const checkDeviceStillTrusted = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);
  const deviceId = requireDeviceId(request.data?.deviceId);

  const trustSnap = await db.doc(`users/${uid}/security/deviceTrust`).get();
  const trustData = (trustSnap.data() ?? {}) as Partial<DeviceTrustData>;
  const trustedDeviceId =
    typeof trustData.trustedDeviceId === "string" ? trustData.trustedDeviceId : null;

  const trusted = trustedDeviceId === null || trustedDeviceId === deviceId;
  return { ok: true, trusted };
});

// -----------------------------------------------------------------------------
// Callable: updateQuietHours
// -----------------------------------------------------------------------------

export const updateQuietHours = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  await ensureUserDoc(uid);

  const quietHoursStartHour = validateHour(request.data?.quietHoursStartHour, "quietHoursStartHour");
  const quietHoursEndHour = validateHour(request.data?.quietHoursEndHour, "quietHoursEndHour");

  await db.doc(`users/${uid}`).set(
    {
      quietHoursStartHour,
      quietHoursEndHour,
      preferredActiveInterval: null,
      preferredInactiveInterval: null,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
    { merge: true }
  );

  return {
    ok: true,
    quietHoursStartHour,
    quietHoursEndHour,
  };
});

// -----------------------------------------------------------------------------
// Callable: getMyProfile
// -----------------------------------------------------------------------------

export const getMyProfile = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  await ensureUserDoc(uid);

  const profile = await getUserProfile(uid);

  return {
    ok: true,
    ...profile,
  };
});

// -----------------------------------------------------------------------------
// Callable: getPublicUserProfile
// -----------------------------------------------------------------------------

export const getPublicUserProfile = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  requireUid(request);

  const targetUidRaw = request.data?.uid as unknown;
  const targetUid = typeof targetUidRaw === "string" ? targetUidRaw.trim() : "";

  if (targetUid.length === 0 || targetUid.length > 128) {
    throw new HttpsError("invalid-argument", "uid is required.");
  }

  const snap = await db.doc(`users/${targetUid}`).get();
  if (!snap.exists) {
    throw new HttpsError("not-found", "Public profile was not found.");
  }

  const data = snap.data() ?? {};

  return {
    ok: true,
    uid: targetUid,

    // Public fields only. Do not add email, timezone, quiet hours, reminder
    // preferences, FCM tokens, or any private account data here.
    username: typeof data.username === "string" ? data.username : "",
    profileImageUrl: typeof data.profileImageUrl === "string" ? data.profileImageUrl : "",
    faculty: typeof data.faculty === "string" ? data.faculty : "",
  };
});

// -----------------------------------------------------------------------------
// Callable: updateMyProfile
// -----------------------------------------------------------------------------

export const updateMyProfile = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  await ensureUserDoc(uid);

  const username = validateUsername(request.data?.username);
  const usernameNormalized = normalizeUsername(username);
  const email = validateEmailAddress(request.data?.email);
  const profileImageUrl = validateProfileImageUrl(request.data?.profileImageUrl);

  const timezone = normalizeTimezone(request.data?.timezone);
  const faculty = validateFaculty(request.data?.faculty);

  const lowActivityNudgeEnabledRaw = request.data?.lowActivityNudgeEnabled as unknown;
  if (typeof lowActivityNudgeEnabledRaw !== "boolean") {
    throw new HttpsError("invalid-argument", "lowActivityNudgeEnabled must be boolean.");
  }

  const quietHoursStartHour = validateHour(
    request.data?.quietHoursStartHour,
    "quietHoursStartHour"
  );

  const quietHoursEndHour = validateHour(
    request.data?.quietHoursEndHour,
    "quietHoursEndHour"
  );

  const current = await getUserProfile(uid);

  const quietHoursChanged =
    current.quietHoursStartHour !== quietHoursStartHour ||
    current.quietHoursEndHour !== quietHoursEndHour;

  if (email !== current.email) {
    await admin.auth().updateUser(uid, { email });
  }

  const userRef = db.doc(`users/${uid}`);
  const newUsernameRef = db.doc(`usernames/${usernameNormalized}`);

  const oldUsernameNormalized = normalizeUsername(current.username);
  const oldUsernameRef =
    oldUsernameNormalized.length > 0
      ? db.doc(`usernames/${oldUsernameNormalized}`)
      : null;

  await db.runTransaction(async (tx) => {
    // IMPORTANT:
    // In Firestore transactions, all reads must happen before all writes.

    const newUsernameSnap = await tx.get(newUsernameRef);

    const oldUsernameSnap =
      oldUsernameRef && oldUsernameNormalized !== usernameNormalized
        ? await tx.get(oldUsernameRef)
        : null;

    if (newUsernameSnap.exists) {
      const ownerUid = newUsernameSnap.get("uid");

      if (ownerUid !== uid) {
        throw new HttpsError("already-exists", "This username is already taken.");
      }
    }

    const patch: Record<string, unknown> = {
      username,
      usernameNormalized,
      email,
      profileImageUrl,
      timezone,
      faculty,
      lowActivityNudgeEnabled: lowActivityNudgeEnabledRaw,
      quietHoursStartHour,
      quietHoursEndHour,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    if (quietHoursChanged) {
      patch.preferredActiveInterval = null;
      patch.preferredInactiveInterval = null;
    }

    tx.set(userRef, patch, { merge: true });

    tx.set(
      newUsernameRef,
      {
        uid,
        username,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    if (
      oldUsernameRef &&
      oldUsernameSnap?.exists &&
      oldUsernameSnap.get("uid") === uid &&
      oldUsernameNormalized !== usernameNormalized
    ) {
      tx.delete(oldUsernameRef);
    }
  });

  const updated = await getUserProfile(uid);

  try {
    await updatePublicProfileSnapshotInRecentLeaderboards(uid, {
      username: updated.username,
      profileImageUrl: updated.profileImageUrl,
      faculty: updated.faculty,
    });
  } catch (err) {
    logger.error(`updateMyProfile: failed to refresh public leaderboard snapshots for ${uid}`, err);
  }

  return {
    ok: true,
    ...updated,
  };
});

// -----------------------------------------------------------------------------
// Callable: uploadStepInterval
// -----------------------------------------------------------------------------

export const uploadStepInterval = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  await ensureUserDoc(uid);

  const dayKey = validateDayKey(request.data?.dayKey);
  const intervalIndex = validateInterval(request.data?.intervalIndex, "intervalIndex");
  const stepsTotal = validateSteps(request.data?.stepsTotal);

  const uploadIntervalIndex =
    request.data?.uploadIntervalIndex == null
      ? intervalIndex
      : validateInterval(request.data?.uploadIntervalIndex, "uploadIntervalIndex");

  const attributedIntervalIndex =
    request.data?.attributedIntervalIndex == null
      ? intervalIndex
      : validateInterval(request.data?.attributedIntervalIndex, "attributedIntervalIndex");

  const profile = await getUserProfile(uid);

  const sessionId = `${dayKey}_${attributedIntervalIndex}`;
  const sessionRef = db.doc(`users/${uid}/step_sessions/${sessionId}`);
  const dailyRef = db.doc(`users/${uid}/daily/${dayKey}`);
  const userRef = db.doc(`users/${uid}`);

  const start = intervalStart(dayKey, attributedIntervalIndex, profile.timezone);
  const end = start.plus({ hours: 4 });

  await db.runTransaction(async (tx) => {
    const dailySnap = await tx.get(dailyRef);
    const daily = dailySnap.data() ?? {};

    if (dailySnap.exists && daily.isFinalized === true) {
      throw new HttpsError("failed-precondition", `Day ${dayKey} is finalized; uploads are blocked.`);
    }

    // --- Compute OLD daily totals before mutation ---
    const oldStepsByInterval = clampSix(daily.stepsByInterval);
    const oldTotalSteps = oldStepsByInterval.reduce((a, b) => a + b, 0);
    const oldBonusPoints = Number.isInteger(daily.bonusPoints) ? (daily.bonusPoints as number) : 0;
    const oldDailyPoints = calcStepPoints(oldTotalSteps) + oldBonusPoints;

    // --- Mutate and compute NEW daily totals ---
    const stepsByInterval = [...oldStepsByInterval];
    stepsByInterval[attributedIntervalIndex] = stepsTotal;

    const sumIntervals = stepsByInterval.reduce((a, b) => a + b, 0);
    const newTotalSteps = sumIntervals;
    const newDailyPoints = calcStepPoints(newTotalSteps) + oldBonusPoints;

    const prevMask = Number.isInteger(daily.uploadedMask) ? (daily.uploadedMask as number) : 0;
    const newMask = prevMask | (1 << attributedIntervalIndex);

    tx.set(
      sessionRef,
      {
        uid,
        dayKey,
        sessionId,
        intervalIndex,
        attributedIntervalIndex,
        uploadIntervalIndex,
        startAt: admin.firestore.Timestamp.fromDate(start.toJSDate()),
        endAt: admin.firestore.Timestamp.fromDate(end.toJSDate()),
        stepsTotal,
        uploadedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    tx.set(
      dailyRef,
      {
        uid,
        dayKey,
        stepsByInterval,
        sumIntervals,
        totalSteps: newTotalSteps,
        uploadedMask: newMask,
        didBonus: typeof daily.didBonus === "boolean" ? daily.didBonus : false,
        bonusPoints: oldBonusPoints,
        isFinalized: false,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    // --- Increment cumulative totals on user doc by the delta ---
    const pointsDelta = newDailyPoints - oldDailyPoints;
    const stepsDelta = newTotalSteps - oldTotalSteps;

    if (pointsDelta !== 0 || stepsDelta !== 0) {
      tx.set(
        userRef,
        {
          cumulativeTotalPoints: admin.firestore.FieldValue.increment(pointsDelta),
          cumulativeTotalSteps: admin.firestore.FieldValue.increment(stepsDelta),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
    }
  });

  let leaderboardRecalculated = false;
  try {
    await recalculateLeaderboardForDay(dayKey);
    leaderboardRecalculated = true;
  } catch (err) {
    logger.error(`uploadStepInterval: leaderboard recalculation failed for ${dayKey}`, err);
  }

  // Raise Your Baseline and Study-Break Boost depend on normal step uploads,
  // not only campus-geofence syncing.
  try {
    await evaluatePersonalChallenge(uid, dayKey);
  } catch (err) {
    logger.error(`uploadStepInterval: personal-challenge evaluation failed for ${dayKey}`, err);
  }

  return { ok: true, leaderboardRecalculated };
});

// -----------------------------------------------------------------------------
// Callable: recordBonusVisit
// -----------------------------------------------------------------------------

export const recordBonusVisit = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  const stationIdRaw = request.data?.stationId as unknown;
  if (typeof stationIdRaw !== "string" || stationIdRaw.trim().length === 0) {
    throw new HttpsError("invalid-argument", "stationId is required.");
  }
  const stationId = stationIdRaw.trim();
  const qaRunId = validateOptionalQaRunId(request.data?.qaRunId);
  const visitedAtMsRaw = request.data?.visitedAtMs as unknown;

  // ---------------------------------------------------------------------------
  // QA-only BLE acceptance-test path
  // ---------------------------------------------------------------------------
  // This branch is intentionally before the production reward transaction.
  // It records one immutable evidence document and returns immediately, so it
  // cannot change daily points, cumulative totals, personal challenges, or the
  // leaderboard. A normal call without qaRunId continues through the unchanged
  // production logic below.
  if (qaRunId != null) {
    const isQaTester = request.auth?.token?.qaTester === true;
    if (!isQaTester) {
      throw new HttpsError(
        "permission-denied",
        "QA bonus logging is allowed only for the dedicated QA account."
      );
    }

    if (stationId !== QA_BONUS_STATION_ID) {
      throw new HttpsError(
        "invalid-argument",
        `The QA BLE test accepts only stationId '${QA_BONUS_STATION_ID}'.`
      );
    }

    // Verify that the same station configuration used by production exists.
    const stationSnap = await db.doc(`bonus_stations/${stationId}`).get();
    if (!stationSnap.exists) {
      throw new HttpsError("not-found", "bonus station not found.");
    }

    const clientVisitedAtMs =
      typeof visitedAtMsRaw === "number" &&
      Number.isInteger(visitedAtMsRaw) &&
      visitedAtMsRaw > 0
        ? visitedAtMsRaw
        : Date.now();

    const qaRunRef = db
      .collection("qa_bonus_station_runs")
      .doc(uid)
      .collection("runs")
      .doc(qaRunId);

    try {
      // create() is deliberate: a reused qaRunId cannot overwrite evidence or
      // be counted as another successful physical run.
      await qaRunRef.create({
        uid,
        qaRunId,
        stationId,
        status: "accepted",
        source: "android_ble_acceptance_test",
        clientVisitedAtMs,
        clientVisitedAt: admin.firestore.Timestamp.fromMillis(clientVisitedAtMs),
        serverRecordedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

      logger.info(`recordBonusVisit QA run accepted: ${uid}/${qaRunId}`);

      return {
        ok: true,
        qaMode: true,
        qaRunId,
        stationId,
      };
    } catch (error) {
      if (isAlreadyExistsError(error)) {
        logger.warn(`recordBonusVisit QA run reused: ${uid}/${qaRunId}`);
        return {
          ok: false,
          qaMode: true,
          qaRunId,
          stationId,
          reason: "duplicate_qa_run",
        };
      }

      logger.error(`recordBonusVisit QA write failed: ${uid}/${qaRunId}`, error);
      throw new HttpsError("internal", "Failed to store the QA BLE test result.");
    }
  }

  // ---------------------------------------------------------------------------
  // Existing production reward flow — unchanged
  // ---------------------------------------------------------------------------
  await ensureUserDoc(uid);
  const profile = await getUserProfile(uid);

  const visitedDt =
    typeof visitedAtMsRaw === "number" && Number.isInteger(visitedAtMsRaw)
      ? DateTime.fromMillis(visitedAtMsRaw, { zone: profile.timezone })
      : DateTime.now().setZone(profile.timezone);

  const dayKey = toDayKey(visitedDt);

  const stationSnap = await db.doc(`bonus_stations/${stationId}`).get();
  if (!stationSnap.exists) {
    throw new HttpsError("not-found", "bonus station not found.");
  }

  const pointsValue = Number.isInteger(stationSnap.data()?.pointsValue)
    ? (stationSnap.data()?.pointsValue as number)
    : 0;

  const visitRef = db.doc(`users/${uid}/bonus_visits/${dayKey}`);
  const dailyRef = db.doc(`users/${uid}/daily/${dayKey}`);
  const userRef = db.doc(`users/${uid}`);

  let awarded = false;

  await db.runTransaction(async (tx) => {
    const [visitSnap, dailySnap] = await Promise.all([tx.get(visitRef), tx.get(dailyRef)]);
    const daily = dailySnap.data() ?? {};
    const didBonus = typeof daily.didBonus === "boolean" ? (daily.didBonus as boolean) : false;

    if (visitSnap.exists || didBonus) {
      awarded = false;
      return;
    }

    tx.set(visitRef, {
      visitId: dayKey,
      uid,
      dayKey,
      stationId,
      visitedAt: admin.firestore.Timestamp.fromDate(visitedDt.toJSDate()),
      awardedPoints: pointsValue,
      isAwarded: true,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    const bonusPointsPrev = Number.isInteger(daily.bonusPoints) ? (daily.bonusPoints as number) : 0;

    tx.set(
      dailyRef,
      {
        uid,
        dayKey,
        didBonus: true,
        bonusPoints: bonusPointsPrev + pointsValue,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    // --- Increment cumulative bonus & total points on user doc ---
    if (pointsValue > 0) {
      tx.set(
        userRef,
        {
          cumulativeTotalPoints: admin.firestore.FieldValue.increment(pointsValue),
          cumulativeBonusPoints: admin.firestore.FieldValue.increment(pointsValue),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
    }

    awarded = true;
  });

  if (awarded) {
    try {
      await recalculateLeaderboardForDay(dayKey);
    } catch (err) {
      logger.error(`recordBonusVisit: leaderboard recalculation failed for ${dayKey}`, err);
    }
  }

  // A verified station visit may satisfy an active Campus Explorer challenge.
  try {
    await evaluatePersonalChallenge(uid, dayKey);
  } catch (err) {
    logger.error(`recordBonusVisit: personal-challenge evaluation failed for ${dayKey}`, err);
  }
  return { ok: awarded, dayKey, awardedPoints: awarded ? pointsValue : 0 };
});

// -----------------------------------------------------------------------------
// Callable: syncCollegeAreaSteps
// -----------------------------------------------------------------------------

export const syncCollegeAreaSteps = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  await ensureUserDoc(uid);

  const dayKey = validateDayKey(request.data?.dayKey);
  const qualifiedStepsTotal = validateQualifiedStepsTotal(request.data?.qualifiedStepsTotal);

  const observedAtMsRaw = request.data?.observedAtMs as unknown;
  const observedAtMs =
    typeof observedAtMsRaw === "number" && Number.isInteger(observedAtMsRaw) && observedAtMsRaw > 0
      ? observedAtMsRaw
      : Date.now();

  const dailyRef = db.doc(`users/${uid}/daily/${dayKey}`);
  const userRef = db.doc(`users/${uid}`);

  let appliedDelta = 0;
  let acceptedQualifiedSteps = 0;
  let awardedBonusPoints = 0;

  await db.runTransaction(async (tx) => {
    const dailySnap = await tx.get(dailyRef);
    const daily = dailySnap.data() ?? {};

    if (dailySnap.exists && daily.isFinalized === true) {
      throw new HttpsError("failed-precondition", `Day ${dayKey} is finalized; uploads are blocked.`);
    }

    const prevQualifiedSteps =
      Number.isInteger(daily.collegeAreaQualifiedSteps)
        ? (daily.collegeAreaQualifiedSteps as number)
        : 0;

    acceptedQualifiedSteps = Math.max(prevQualifiedSteps, qualifiedStepsTotal);
    appliedDelta = acceptedQualifiedSteps - prevQualifiedSteps;

    const prevBonusPoints =
      Number.isInteger(daily.bonusPoints) ? (daily.bonusPoints as number) : 0;

    // Diff the cumulative campus bonus for the old vs. new qualified-step totals
    // to get the exact integer delta to apply. This stays correct even when an
    // individual sync does not cross a whole-point boundary.
    const collegeAreaBonusPoints = calcCollegeAreaBonus(acceptedQualifiedSteps);
    const prevCollegeAreaBonusPoints = calcCollegeAreaBonus(prevQualifiedSteps);

    const bonusDelta = collegeAreaBonusPoints - prevCollegeAreaBonusPoints;
    awardedBonusPoints = bonusDelta;

    tx.set(
      dailyRef,
      {
        uid,
        dayKey,
        collegeAreaQualifiedSteps: acceptedQualifiedSteps,
        collegeAreaBonusPoints,
        bonusPoints: prevBonusPoints + bonusDelta,
        collegeAreaLastObservedAt: admin.firestore.Timestamp.fromMillis(observedAtMs),
        collegeAreaLastSyncedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    // --- Increment cumulative bonus & total points on user doc ---
    if (bonusDelta > 0) {
      tx.set(
        userRef,
        {
          cumulativeTotalPoints: admin.firestore.FieldValue.increment(bonusDelta),
          cumulativeBonusPoints: admin.firestore.FieldValue.increment(bonusDelta),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
    }
  });

  let leaderboardRecalculated = false;

  if (appliedDelta > 0) {
    try {
      await recalculateLeaderboardForDay(dayKey);
      leaderboardRecalculated = true;
    } catch (err) {
      logger.error(`syncCollegeAreaSteps: leaderboard recalculation failed for ${dayKey}`, err);
    }
  }


  // Progress any active personal challenge from the freshly-updated campus steps.
  try {
    await evaluatePersonalChallenge(uid, dayKey);
  } catch (err) {
    logger.error(`syncCollegeAreaSteps: personal-challenge evaluation failed for ${dayKey}`, err);
  }

  return {
    ok: true,
    dayKey,
    acceptedQualifiedSteps,
    appliedDelta,
    awardedPoints: awardedBonusPoints,
    leaderboardRecalculated,
  };
});

// -----------------------------------------------------------------------------
// Personal Challenges — helpers
// -----------------------------------------------------------------------------

function pcIntervalLabel(intervalIndex: number): string {
  const start = (intervalIndex * 4) % 24;
  const end = start + 4;
  const pad = (h: number) => String(h).padStart(2, "0");
  return `${pad(start)}:00–${pad(end === 24 ? 24 : end)}:00`;
}

function pcTimestampToMillis(value: unknown): number {
  if (value instanceof admin.firestore.Timestamp) return value.toMillis();
  if (value && typeof (value as { toMillis?: () => number }).toMillis === "function") {
    return (value as { toMillis: () => number }).toMillis();
  }
  return 0;
}

function pcCampusStepsFromDaily(daily: Record<string, unknown> | undefined): number {
  const v = daily?.collegeAreaQualifiedSteps;
  return Number.isInteger(v) && (v as number) >= 0 ? (v as number) : 0;
}

/**
 * Returns the user's canonical valid step total for one day.
 * Includes valid steps both inside and outside the campus perimeter.
 */
function pcTotalValidStepsFromDaily(daily: Record<string, unknown> | undefined): number {
  return clampSix(daily?.stepsByInterval).reduce((sum, steps) => sum + steps, 0);
}

type PcStepScope = "all_valid_steps" | "campus_qualified_steps";

function pcStepScopeFromChallenge(challenge: Record<string, unknown>): PcStepScope {
  // Older active challenges did not store a scope.
  // Keep them campus-only so their measurement rule does not change mid-day.
  return challenge.stepScope === "all_valid_steps"
    ? "all_valid_steps"
    : "campus_qualified_steps";
}

function pcStepsForScope(
  daily: Record<string, unknown> | undefined,
  scope: PcStepScope
): number {
  return scope === "all_valid_steps"
    ? pcTotalValidStepsFromDaily(daily)
    : pcCampusStepsFromDaily(daily);
}

/**
 * Average of verified campus steps over the last N completed days (excluding
 * today). A day is "usable" when it has a positive campus-qualified step count.
 * Returns null when there are fewer than the required number of usable days.
 */
async function pcComputeBaseline(
  uid: string,
  dayKey: string,
  tz: string
): Promise<{ baseline: number; usableDays: number } | null> {
  const today = DateTime.fromISO(dayKey, { zone: tz });
  const refs: admin.firestore.DocumentReference[] = [];
  for (let i = 1; i <= PC_BASELINE_LOOKBACK_DAYS; i++) {
    const key = toDayKey(today.minus({ days: i }));
    refs.push(db.doc(`users/${uid}/daily/${key}`));
  }

  const snaps = await db.getAll(...refs);
  const usable: number[] = [];
  for (const snap of snaps) {
    if (!snap.exists) continue;
    const steps = pcTotalValidStepsFromDaily(snap.data());
    if (steps > 0) usable.push(steps);
  }

  if (usable.length < PC_BASELINE_MIN_DAYS) return null;

  const sum = usable.reduce((a, b) => a + b, 0);
  return { baseline: Math.round(sum / usable.length), usableDays: usable.length };
}

/**
 * Chooses the user's least-active 4-hour window for Study-Break Boost.
 *
 * Priority:
 *  1) The learned preferredInactiveInterval, if it is outside quiet hours.
 *  2) Otherwise the lowest-average allowed interval over the last N days,
 *     requiring at least PC_STUDY_BREAK_MIN_DAYS usable days.
 *
 * Only intervals outside the user's quiet-hours window are eligible.
 */
async function pcComputeStudyBreakInterval(
  uid: string,
  profile: { preferredInactiveInterval: number | null; quietHoursStartHour: number; quietHoursEndHour: number },
  dayKey: string,
  tz: string
): Promise<{ intervalIndex: number } | null> {
  const allowed = allowedReminderIntervals(profile.quietHoursStartHour, profile.quietHoursEndHour);
  if (allowed.length === 0) return null;

  if (
    profile.preferredInactiveInterval != null &&
    allowed.includes(profile.preferredInactiveInterval)
  ) {
    return { intervalIndex: profile.preferredInactiveInterval };
  }

  const today = DateTime.fromISO(dayKey, { zone: tz });
  const refs: admin.firestore.DocumentReference[] = [];
  for (let i = 1; i <= PC_STUDY_BREAK_LOOKBACK_DAYS; i++) {
    const key = toDayKey(today.minus({ days: i }));
    refs.push(db.doc(`users/${uid}/daily/${key}`));
  }

  const snaps = await db.getAll(...refs);
  const sums = new Array<number>(6).fill(0);
  let usableDays = 0;

  for (const snap of snaps) {
    if (!snap.exists) continue;
    const stepsByInterval = clampSix(snap.data()?.stepsByInterval);
    if (stepsByInterval.reduce((a, b) => a + b, 0) <= 0) continue;
    usableDays++;
    for (let i = 0; i < 6; i++) sums[i] += stepsByInterval[i];
  }

  if (usableDays < PC_STUDY_BREAK_MIN_DAYS) return null;

  let chosen = allowed[0];
  for (const i of allowed) {
    if (sums[i] < sums[chosen]) chosen = i;
  }
  return { intervalIndex: chosen };
}

type PcOffer = {
  available: boolean;
  reason: string | null;
  [extra: string]: unknown;
};

/**
 * Computes the availability + frozen parameters for all three challenge offers.
 * Used by getMyPersonalChallenges (display) and acceptPersonalChallenge (re-check).
 */
async function pcComputeOffers(
  uid: string,
  profile: {
    timezone: string;
    preferredInactiveInterval: number | null;
    quietHoursStartHour: number;
    quietHoursEndHour: number;
  },
  dayKey: string,
  nowDt: DateTime
): Promise<{
  raise_baseline: PcOffer;
  study_break_boost: PcOffer;
  campus_explorer: PcOffer;
}> {
  const tz = profile.timezone;

  // --- Raise Your Baseline ---
  const baselineResult = await pcComputeBaseline(uid, dayKey, tz);
  let raiseBaseline: PcOffer;
  if (baselineResult == null) {
    raiseBaseline = {
      available: false,
      reason: "Keep walking for a few more days to unlock your personal baseline.",
      baselineSteps: 0,
      difficulties: {},
    };
  } else {
    const difficulties: Record<string, { percent: number; targetSteps: number; reward: number }> = {};
    for (const d of PC_DIFFICULTIES) {
      const tier = PC_BASELINE_TIERS[d];
      difficulties[d] = {
        percent: tier.percent,
        targetSteps: Math.round(baselineResult.baseline * (1 + tier.percent)),
        reward: tier.reward,
      };
    }
    raiseBaseline = {
      available: true,
      reason: null,
      baselineSteps: baselineResult.baseline,
      difficulties,
    };
  }

  // --- Study-Break Boost ---
  const interval = await pcComputeStudyBreakInterval(uid, profile, dayKey, tz);
  let studyBreak: PcOffer;
  if (interval == null) {
    studyBreak = {
      available: false,
      reason: "Unlocks after 3 days of verified activity.",
      selectedIntervalIndex: null,
      selectedIntervalLabel: null,
      goalSteps: PC_STUDY_BREAK_TARGET_STEPS,
      reward: PC_STUDY_BREAK_REWARD,
    };
  } else {
    const windowEndHour = (interval.intervalIndex * 4) % 24 + 4;
    const windowEnded = nowDt.hour >= windowEndHour;
    studyBreak = {
      available: !windowEnded,
      reason: windowEnded
        ? "Your personal movement window has already passed. Try this challenge tomorrow."
        : null,
      selectedIntervalIndex: interval.intervalIndex,
      selectedIntervalLabel: pcIntervalLabel(interval.intervalIndex),
      goalSteps: PC_STUDY_BREAK_TARGET_STEPS,
      reward: PC_STUDY_BREAK_REWARD,
    };
  }

  // --- Campus Explorer ---
  // No bonus-station visit is required; Campus Explorer is 1,200 verified
  // campus steps only, so it is always offered (still one challenge per day).
  const campusExplorer: PcOffer = {
    available: true,
    reason: null,
    goalSteps: PC_CAMPUS_TARGET_STEPS,
    stationGoal: PC_CAMPUS_STATION_GOAL,
    reward: PC_CAMPUS_REWARD,
  };

  return {
    raise_baseline: raiseBaseline,
    study_break_boost: studyBreak,
    campus_explorer: campusExplorer,
  };
}

/**
 * Serializes the stored daily challenge document into a client-safe object.
 */
function pcSerializeActive(data: Record<string, unknown>): Record<string, unknown> {
  // Study-Break Boost is bound to its 4-hour window: expose the window bounds
  // so the client can count down to the real deadline instead of midnight.
  let windowStartMs: number | null = null;
  let windowEndMs: number | null = null;
  if (
    data.challengeType === "study_break_boost" &&
    Number.isInteger(data.selectedIntervalIndex) &&
    typeof data.dayKey === "string" &&
    typeof data.timezone === "string"
  ) {
    const ws = intervalStart(data.dayKey, data.selectedIntervalIndex as number, data.timezone);
    windowStartMs = ws.toMillis();
    windowEndMs = ws.plus({ hours: 4 }).toMillis();
  }
  return {
    challengeType: typeof data.challengeType === "string" ? data.challengeType : null,
    difficulty: typeof data.difficulty === "string" ? data.difficulty : null,
    status: typeof data.status === "string" ? data.status : "active",
    rewardPoints: Number.isInteger(data.rewardPoints) ? data.rewardPoints : 0,
    baselineSteps: Number.isInteger(data.baselineSteps) ? data.baselineSteps : 0,
    targetSteps: Number.isInteger(data.targetSteps) ? data.targetSteps : 0,
    progressSteps: Number.isInteger(data.progressSteps) ? data.progressSteps : 0,
    selectedIntervalIndex: Number.isInteger(data.selectedIntervalIndex)
      ? data.selectedIntervalIndex
      : null,
    selectedIntervalLabel:
      typeof data.selectedIntervalLabel === "string" ? data.selectedIntervalLabel : null,
    stationGoal: PC_CAMPUS_STATION_GOAL,
    stationVisitQualified: data.stationVisitQualified === true,
    challengeRewardGranted: data.challengeRewardGranted === true,
    acceptedAtMs: pcTimestampToMillis(data.acceptedAt),
    completedAtMs: pcTimestampToMillis(data.completedAt),
    windowStartMs,
    windowEndMs,
  };
}

/**
 * evaluatePersonalChallenge
 *
 * Recomputes progress for the active daily challenge from trusted server data
 * and, when every requirement is satisfied, grants the reward EXACTLY ONCE
 * through a Firestore transaction guarded by `challengeRewardGranted`.
 *
 * The reward is added to the same daily bonus accumulator used by BLE station
 * visits and campus-area steps, so it flows into daily points, cumulative
 * points and the leaderboard through the existing trusted score path.
 */
async function evaluatePersonalChallenge(uid: string, dayKey: string): Promise<void> {
  const challengeRef = db.doc(`users/${uid}/personal_challenges/${dayKey}`);
  const dailyRef = db.doc(`users/${uid}/daily/${dayKey}`);
  const visitRef = db.doc(`users/${uid}/bonus_visits/${dayKey}`);
  const userRef = db.doc(`users/${uid}`);

  let didGrant = false;

  await db.runTransaction(async (tx) => {
    const [cSnap, dSnap, vSnap] = await Promise.all([
      tx.get(challengeRef),
      tx.get(dailyRef),
      tx.get(visitRef),
    ]);

    if (!cSnap.exists) return;
    const c = cSnap.data() ?? {};
    if (c.status !== "active") return; // only active challenges are evaluated
    if (c.challengeRewardGranted === true) return; // reward already granted

    const tz = typeof c.timezone === "string" ? c.timezone : DEFAULT_TZ;
    const daily = dSnap.data() ?? {};
    const currentCampusSteps = pcCampusStepsFromDaily(daily);

    const stepScope = pcStepScopeFromChallenge(c);
    const currentScopedSteps = pcStepsForScope(daily, stepScope);

    const stepsAtAcceptance = Number.isInteger(c.stepsAtAcceptance)
      ? (c.stepsAtAcceptance as number)
      : 0;
    const acceptedAtMs = pcTimestampToMillis(c.acceptedAt);
    const nowDt = DateTime.now().setZone(tz);

    const patch: Record<string, unknown> = {
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    let requirementsMet = false;

    if (c.challengeType === "raise_baseline") {
      const target = Number.isInteger(c.targetSteps) ? (c.targetSteps as number) : 0;
      const progress = Math.max(0, currentScopedSteps - stepsAtAcceptance);
      patch.progressSteps = Math.min(progress, target);
      requirementsMet = target > 0 && progress >= target;
    } else if (c.challengeType === "study_break_boost") {
        const idx = Number.isInteger(c.selectedIntervalIndex)
          ? (c.selectedIntervalIndex as number)
          : 0;

        const winStart = intervalStart(dayKey, idx, tz);
        const winEnd = winStart.plus({ hours: 4 });
        const intervalSteps = clampSix(daily.stepsByInterval)[idx];

        let progress: number;

        if (stepScope === "all_valid_steps") {
          // New challenges save the interval's current total at acceptance.
          // Only steps added afterwards inside this interval count.
          const intervalStepsAtAcceptance = Number.isInteger(c.intervalStepsAtAcceptance)
            ? (c.intervalStepsAtAcceptance as number)
            : intervalSteps;

          progress = nowDt < winStart
            ? 0
            : Math.max(0, intervalSteps - intervalStepsAtAcceptance);

          patch.progressSteps = Math.min(progress, PC_STUDY_BREAK_TARGET_STEPS);
        } else {
          // Preserve the old campus-only behavior for challenges created before this update.
          const insideWindow = nowDt >= winStart && nowDt < winEnd;

          let windowStart = Number.isInteger(c.studyBreakWindowStartSteps)
            ? (c.studyBreakWindowStartSteps as number)
            : null;

          if (windowStart == null && insideWindow) {
            windowStart = currentCampusSteps;
            patch.studyBreakWindowStartSteps = windowStart;
          }

          progress = Number.isInteger(c.progressSteps) ? (c.progressSteps as number) : 0;

          if (windowStart != null && insideWindow) {
            progress = Math.max(0, currentCampusSteps - windowStart);
            patch.progressSteps = Math.min(progress, PC_STUDY_BREAK_TARGET_STEPS);
          }
        }

        requirementsMet = progress >= PC_STUDY_BREAK_TARGET_STEPS;

        if (!requirementsMet && nowDt >= winEnd) {
          patch.status = "expired";
          tx.set(challengeRef, patch, { merge: true });
          return;
        }
    } else if (c.challengeType === "campus_explorer") {
      const progress = Math.max(0, currentCampusSteps - stepsAtAcceptance);
      patch.progressSteps = Math.min(progress, PC_CAMPUS_TARGET_STEPS);

      let stationQualified = false;
      if (vSnap.exists) {
        const visitedMs = pcTimestampToMillis(vSnap.data()?.visitedAt);
        // Only a station visit AFTER acceptance qualifies for this challenge.
        stationQualified = visitedMs >= acceptedAtMs && acceptedAtMs > 0;
        if (stationQualified) {
          const sid = vSnap.data()?.stationId;
          patch.qualifiedStationId = typeof sid === "string" ? sid : null;
        }
      }
      patch.stationVisitQualified = stationQualified;
      // Station visit no longer required: 1,200 verified campus steps is enough.
      requirementsMet = progress >= PC_CAMPUS_TARGET_STEPS;
    } else {
      return;
    }

    if (requirementsMet) {
      const rewardPoints = Number.isInteger(c.rewardPoints) ? (c.rewardPoints as number) : 0;

      patch.status = "completed";
      patch.completedAt = admin.firestore.FieldValue.serverTimestamp();
      patch.challengeRewardGranted = true;

      if (rewardPoints > 0) {
        // Same trusted daily-bonus accumulator used by BLE + campus-area rewards.
        tx.set(
          dailyRef,
          {
            uid,
            dayKey,
            bonusPoints: admin.firestore.FieldValue.increment(rewardPoints),
            challengeBonusPoints: admin.firestore.FieldValue.increment(rewardPoints),
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          { merge: true }
        );

        tx.set(
          userRef,
          {
            cumulativeTotalPoints: admin.firestore.FieldValue.increment(rewardPoints),
            cumulativeBonusPoints: admin.firestore.FieldValue.increment(rewardPoints),
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          { merge: true }
        );
      }

      didGrant = true;
    }

    tx.set(challengeRef, patch, { merge: true });
  });

  if (didGrant) {
    try {
      await recalculateLeaderboardForDay(dayKey);
    } catch (err) {
      logger.error(`evaluatePersonalChallenge: leaderboard recalculation failed for ${dayKey}`, err);
    }
  }
}

// -----------------------------------------------------------------------------
// Callable: getMyPersonalChallenges
// -----------------------------------------------------------------------------

export const getMyPersonalChallenges = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);
  await ensureUserDoc(uid);

  const profile = await getUserProfile(uid);
  const tz = profile.timezone;
  const nowDt = DateTime.now().setZone(tz);
  const dayKey = toDayKey(nowDt);

  // Refresh progress / completion from trusted data before returning state.
  try {
    await evaluatePersonalChallenge(uid, dayKey);
  } catch (err) {
    logger.error(`getMyPersonalChallenges: evaluate failed for ${uid}/${dayKey}`, err);
  }

  const challengeSnap = await db.doc(`users/${uid}/personal_challenges/${dayKey}`).get();
  const hasChallenge = challengeSnap.exists;
  const active = hasChallenge ? pcSerializeActive(challengeSnap.data() ?? {}) : null;

  // Offers are only meaningful when the user has not yet chosen a challenge.
  const offers = hasChallenge
    ? null
    : await pcComputeOffers(uid, profile, dayKey, nowDt);

  const nextMidnightMs = nowDt.plus({ days: 1 }).startOf("day").toMillis();

  return {
    ok: true,
    dayKey,
    timezone: tz,
    serverNowMs: nowDt.toMillis(),
    midnightMs: nextMidnightMs,
    active,
    offers,
  };
});

// -----------------------------------------------------------------------------
// Callable: acceptPersonalChallenge
// -----------------------------------------------------------------------------

export const acceptPersonalChallenge = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);
  await ensureUserDoc(uid);

  const challengeTypeRaw = request.data?.challengeType as unknown;
  const challengeType =
    typeof challengeTypeRaw === "string" ? (challengeTypeRaw.trim() as PcChallengeType) : "";
  if (!PC_CHALLENGE_TYPES.includes(challengeType as PcChallengeType)) {
    throw new HttpsError("invalid-argument", "Unknown challengeType.");
  }

  let difficulty: PcDifficulty | null = null;
  if (challengeType === "raise_baseline") {
    const dRaw = request.data?.difficulty as unknown;
    const d = typeof dRaw === "string" ? (dRaw.trim() as PcDifficulty) : "";
    if (!PC_DIFFICULTIES.includes(d as PcDifficulty)) {
      throw new HttpsError("invalid-argument", "difficulty must be easy | medium | hard.");
    }
    difficulty = d as PcDifficulty;
  }

  const profile = await getUserProfile(uid);
  const tz = profile.timezone;
  const nowDt = DateTime.now().setZone(tz);
  const dayKey = toDayKey(nowDt);

  // Re-validate availability with authoritative server data (outside the txn:
  // baselines and intervals are historical and stable within the race window).
  const offers = await pcComputeOffers(uid, profile, dayKey, nowDt);
  const offer = offers[challengeType as PcChallengeType];
  if (!offer.available) {
    throw new HttpsError(
      "failed-precondition",
      typeof offer.reason === "string" ? offer.reason : "This challenge is not available today."
    );
  }

  // Freeze the parameters for the chosen challenge.
  const frozen: Record<string, unknown> = {
    uid,
    dayKey,
    timezone: tz,
    challengeType,
    difficulty,
    status: "active",
    progressSteps: 0,
    stationVisitQualified: false,
    qualifiedStationId: null,
    baselineSteps: 0,
    targetSteps: 0,
    rewardPoints: 0,
    selectedIntervalIndex: null,
    selectedIntervalLabel: null,
    stepScope:
      challengeType === "campus_explorer"
        ? "campus_qualified_steps"
        : "all_valid_steps",

    intervalStepsAtAcceptance: null,
    challengeRewardGranted: false,
  };

  if (challengeType === "raise_baseline") {
    const diffs = offer.difficulties as Record<
      string,
      { percent: number; targetSteps: number; reward: number }
    >;
    const picked = diffs[difficulty as string];
    frozen.baselineSteps = offer.baselineSteps as number;
    frozen.targetSteps = picked.targetSteps;
    frozen.rewardPoints = picked.reward;
  } else if (challengeType === "study_break_boost") {
    frozen.selectedIntervalIndex = offer.selectedIntervalIndex as number;
    frozen.selectedIntervalLabel = offer.selectedIntervalLabel as string;
    frozen.targetSteps = PC_STUDY_BREAK_TARGET_STEPS;
    frozen.rewardPoints = PC_STUDY_BREAK_REWARD;
  } else if (challengeType === "campus_explorer") {
    frozen.targetSteps = PC_CAMPUS_TARGET_STEPS;
    frozen.rewardPoints = PC_CAMPUS_REWARD;
  }

  const challengeRef = db.doc(`users/${uid}/personal_challenges/${dayKey}`);
  const dailyRef = db.doc(`users/${uid}/daily/${dayKey}`);

  await db.runTransaction(async (tx) => {
    const [existingSnap, dailySnap] = await Promise.all([
      tx.get(challengeRef),
      tx.get(dailyRef),
    ]);

    // One challenge per day: any existing doc means a choice was already made.
    if (existingSnap.exists) {
      const existing = existingSnap.data() ?? {};
      if (existing.status === "active" || existing.status === "completed") {
        throw new HttpsError(
          "failed-precondition",
          "You have already selected a challenge for today."
        );
      }
      if (existing.status === "expired") {
        throw new HttpsError(
          "failed-precondition",
          "Today’s challenge slot has already been used."
        );
      }
    }

    const dailyAtAcceptance = dailySnap.data();
    const frozenScope = frozen.stepScope as PcStepScope;

    frozen.stepsAtAcceptance = pcStepsForScope(dailyAtAcceptance, frozenScope);

    if (challengeType === "study_break_boost") {
      const selectedIntervalIndex = frozen.selectedIntervalIndex as number;

      frozen.intervalStepsAtAcceptance =
        clampSix(dailyAtAcceptance?.stepsByInterval)[selectedIntervalIndex];
    }
    frozen.acceptedAt = admin.firestore.FieldValue.serverTimestamp();
    frozen.createdAt = admin.firestore.FieldValue.serverTimestamp();
    frozen.updatedAt = admin.firestore.FieldValue.serverTimestamp();

    tx.set(challengeRef, frozen, { merge: true });
  });

  // Evaluate once so any already-satisfied requirement is reflected immediately.
  try {
    await evaluatePersonalChallenge(uid, dayKey);
  } catch (err) {
    logger.error(`acceptPersonalChallenge: evaluate failed for ${uid}/${dayKey}`, err);
  }

  const finalSnap = await challengeRef.get();

  return {
    ok: true,
    dayKey,
    timezone: tz,
    serverNowMs: nowDt.toMillis(),
    midnightMs: nowDt.plus({ days: 1 }).startOf("day").toMillis(),
    active: pcSerializeActive(finalSnap.data() ?? {}),
    offers: null,
  };
});

// -----------------------------------------------------------------------------
// Scheduled: finalizeDay
// -----------------------------------------------------------------------------

export const finalizeDay = onSchedule(
  { region: FUNCTIONS_REGION, schedule: "30 0 * * *", timeZone: DEFAULT_TZ },
  async () => {
    const now = DateTime.now().setZone(DEFAULT_TZ);
    const dayKey = toDayKey(now.minus({ days: 1 }));
    const nextDayKey = toDayKey(now);

    logger.info(`finalizeDay: finalizing ${dayKey}, scheduling reminders for ${nextDayKey}`);

    const usersSnap = await db.collection("users").get();
    const leaderboard: LeaderboardRow[] = [];

    for (const u of usersSnap.docs) {
      const uid = u.id;
      const profile = await getUserProfile(uid);

      const dailyRef = db.doc(`users/${uid}/daily/${dayKey}`);
      const dailySnap = await dailyRef.get();
      if (!dailySnap.exists) continue;

      const daily = dailySnap.data() ?? {};
      const stepsByInterval = clampSix(daily.stepsByInterval);
      const sumIntervals = stepsByInterval.reduce((a, b) => a + b, 0);

      const totalSteps = sumIntervals;
      const bonusPoints = Number.isInteger(daily.bonusPoints) ? (daily.bonusPoints as number) : 0;
      const didBonus = typeof daily.didBonus === "boolean" ? (daily.didBonus as boolean) : false;

      const uploadedMask = Number.isInteger(daily.uploadedMask) ? (daily.uploadedMask as number) : 0;
      const isComplete = uploadedMask === 0b111111;

      const candidateIntervals = validReminderCandidates(
        uploadedMask,
        profile.quietHoursStartHour,
        profile.quietHoursEndHour
      );

      const bestInterval =
        candidateIntervals.length > 0 ? pickBestInterval(stepsByInterval, candidateIntervals) : null;

      const worstInterval =
        candidateIntervals.length > 0 ? pickWorstInterval(stepsByInterval, candidateIntervals) : null;

      await db.doc(`users/${uid}/daily_summaries/${dayKey}`).set(
        {
          uid,
          dayKey,
          bestInterval,
          worstInterval,
          candidateIntervals,
          quietHoursStartHour: profile.quietHoursStartHour,
          quietHoursEndHour: profile.quietHoursEndHour,
          stepsByInterval,
          sumIntervals,
          totalSteps,
          didBonus,
          bonusPoints,
          uploadedMask,
          isComplete,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      await dailyRef.set(
        {
          stepsByInterval,
          sumIntervals,
          totalSteps,
          isFinalized: true,
          finalizedAt: admin.firestore.FieldValue.serverTimestamp(),
          isComplete,
        },
        { merge: true }
      );

      const fallbackInterval = firstAllowedReminderInterval(
        profile.quietHoursStartHour,
        profile.quietHoursEndHour
      );

      const active = profile.preferredActiveInterval ?? bestInterval ?? fallbackInterval;
      const inactive = profile.preferredInactiveInterval ?? worstInterval ?? fallbackInterval;
      const chosen = profile.lowActivityNudgeEnabled ? inactive : active;

      const selectionSource = profile.lowActivityNudgeEnabled
        ? profile.preferredInactiveInterval != null
          ? "preferredInactiveInterval"
          : worstInterval != null
            ? "worstUploadedAwakeInterval"
            : "fallbackFirstAllowedInterval"
        : profile.preferredActiveInterval != null
          ? "preferredActiveInterval"
          : bestInterval != null
            ? "bestUploadedAwakeInterval"
            : "fallbackFirstAllowedInterval";

      const sendAt = computeSendAt(
        nextDayKey,
        chosen,
        profile.timezone,
        profile.quietHoursStartHour,
        profile.quietHoursEndHour
      );

      const jobId = `${uid}_${nextDayKey}`;

      await db.doc(`notification_jobs/${jobId}`).set(
        {
          uid,
          dayKey: nextDayKey,
          intervalIndex: chosen,
          candidateIntervals,
          quietHoursStartHour: profile.quietHoursStartHour,
          quietHoursEndHour: profile.quietHoursEndHour,
          selectionSource,
          sendAt,
          type: "REMINDER",
          status: "PENDING",
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          sentAt: null,
          lastError: null,
        },
        { merge: true }
      );

      const totalPoints = calcStepPoints(totalSteps) + bonusPoints;
      leaderboard.push({
        uid,
        totalPoints,
        totalSteps,
        bonusPoints,
        username: profile.username,
        profileImageUrl: profile.profileImageUrl,
        faculty: profile.faculty,
      });
    }

    leaderboard.sort(compareLeaderboardRows);
    await writeLeaderboardForDay(dayKey, leaderboard);
  }
);

// -----------------------------------------------------------------------------
// Scheduled: dispatchNotificationJobs
// 24/7 polling, because quiet-hours logic already decides per-user awake windows
// -----------------------------------------------------------------------------

export const dispatchNotificationJobs = onSchedule(
  { region: FUNCTIONS_REGION, schedule: "*/15 * * * *", timeZone: DEFAULT_TZ },
  async () => {
    const nowTs = admin.firestore.Timestamp.now();

    const jobsSnap = await db
      .collection("notification_jobs")
      .where("status", "==", "PENDING")
      .where("sendAt", "<=", nowTs)
      .limit(50)
      .get();

    if (jobsSnap.empty) return;

    for (const job of jobsSnap.docs) {
      const data = job.data();
      const uid = typeof data.uid === "string" ? data.uid : "";

      if (!uid) {
        await job.ref.set(
          {
            status: "FAILED",
            lastError: "Missing uid",
            sentAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          { merge: true }
        );
        continue;
      }

      try {
        const tokensSnap = await db.collection(`users/${uid}/fcm_tokens`).get();
        const tokens = tokensSnap.docs.map((d) => d.id);

        if (tokens.length === 0) {
          await job.ref.set(
            {
              status: "NO_TOKENS",
              sentAt: admin.firestore.FieldValue.serverTimestamp(),
            },
            { merge: true }
          );
          continue;
        }

        const res = await messaging.sendEachForMulticast({
          tokens,
          notification: {
            title: "Go for it!",
            body: "Time for some activity 💪",
          },
          data: {
            type: "REMINDER",
            dayKey: typeof data.dayKey === "string" ? data.dayKey : "",
            intervalIndex: typeof data.intervalIndex === "number" ? String(data.intervalIndex) : "",
          },
        });

        const toDelete: string[] = [];
        res.responses.forEach((r, idx) => {
          if (!r.success) {
            const code = (r.error as { code?: string } | undefined)?.code ?? "";
            if (isTokenInvalid(code)) toDelete.push(tokens[idx]);
          }
        });

        if (toDelete.length > 0) {
          const delBatch = db.batch();
          toDelete.forEach((t) => delBatch.delete(db.doc(`users/${uid}/fcm_tokens/${t}`)));
          await delBatch.commit();
        }

        await job.ref.set(
          {
            status: "SENT",
            sentAt: admin.firestore.FieldValue.serverTimestamp(),
            lastError: null,
          },
          { merge: true }
        );
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        await job.ref.set(
          {
            status: "FAILED",
            lastError: msg,
            sentAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          { merge: true }
        );
      }
    }
  }
);

// -----------------------------------------------------------------------------
// Scheduled: finalizeMonth
// -----------------------------------------------------------------------------

export const finalizeMonth = onSchedule(
  { region: FUNCTIONS_REGION, schedule: "20 0 1 * *", timeZone: DEFAULT_TZ },
  async () => {
    const now = DateTime.now().setZone(DEFAULT_TZ);
    const lastMonthStart = now.minus({ months: 1 }).startOf("month");
    const lastMonthEnd = lastMonthStart.endOf("month");

    const startKey = toDayKey(lastMonthStart);
    const endKey = toDayKey(lastMonthEnd);

    logger.info(`finalizeMonth: ${startKey} -> ${endKey}`);

    const usersSnap = await db.collection("users").get();

    for (const u of usersSnap.docs) {
      const uid = u.id;
      const profile = await getUserProfile(uid);

      const bestHist = [0, 0, 0, 0, 0, 0];
      const worstHist = [0, 0, 0, 0, 0, 0];

      const sumsSnap = await db
        .collection(`users/${uid}/daily_summaries`)
        .where("dayKey", ">=", startKey)
        .where("dayKey", "<=", endKey)
        .get();

      for (const d of sumsSnap.docs) {
        const s = d.data();

        const b = normalizePreferredInterval(
          s.bestInterval,
          profile.quietHoursStartHour,
          profile.quietHoursEndHour
        );

        const w = normalizePreferredInterval(
          s.worstInterval,
          profile.quietHoursStartHour,
          profile.quietHoursEndHour
        );

        if (b != null) bestHist[b]++;
        if (w != null) worstHist[w]++;
      }

      const mode = (hist: number[]): number | null => {
        let chosen: number | null = null;
        for (let i = 0; i < 6; i++) {
          if (hist[i] <= 0) continue;
          if (chosen == null || hist[i] > hist[chosen]) chosen = i;
        }
        return chosen;
      };

      if (sumsSnap.size >= 5) {
        await db.doc(`users/${uid}`).set(
          {
            preferredActiveInterval: mode(bestHist),
            preferredInactiveInterval: mode(worstHist),
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          { merge: true }
        );
      }
    }
  }
);