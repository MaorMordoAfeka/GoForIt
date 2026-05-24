// functions/src/index.ts
import * as admin from "firebase-admin";
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
const COLLEGE_AREA_BONUS_POINTS_PER_STEP = 10;

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
  faculty: string;
};

function compareLeaderboardRows(a: LeaderboardRow, b: LeaderboardRow): number {
  if (b.totalPoints !== a.totalPoints) return b.totalPoints - a.totalPoints;
  if (b.totalSteps !== a.totalSteps) return b.totalSteps - a.totalSteps;
  if (b.bonusPoints !== a.bonusPoints) return b.bonusPoints - a.bonusPoints;
  return a.uid.localeCompare(b.uid);
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
}

async function recalculateLeaderboardForDay(dayKey: string): Promise<void> {
  const usersSnap = await db.collection("users").get();
  const leaderboard: LeaderboardRow[] = [];

  for (const u of usersSnap.docs) {
    const uid = u.id;
    const userData = u.data() ?? {};
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

  return { ok: true, leaderboardRecalculated };
});

// -----------------------------------------------------------------------------
// Callable: recordBonusVisit
// -----------------------------------------------------------------------------

export const recordBonusVisit = onCall({ region: FUNCTIONS_REGION }, async (request) => {
  const uid = requireUid(request);

  await ensureUserDoc(uid);

  const stationIdRaw = request.data?.stationId as unknown;
  if (typeof stationIdRaw !== "string" || stationIdRaw.trim().length === 0) {
    throw new HttpsError("invalid-argument", "stationId is required.");
  }
  const stationId = stationIdRaw.trim();

  const profile = await getUserProfile(uid);
  const visitedAtMsRaw = request.data?.visitedAtMs as unknown;

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

    const collegeAreaBonusPoints =
      acceptedQualifiedSteps * COLLEGE_AREA_BONUS_POINTS_PER_STEP;

    const bonusDelta = appliedDelta * COLLEGE_AREA_BONUS_POINTS_PER_STEP;

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

  return {
    ok: true,
    dayKey,
    acceptedQualifiedSteps,
    appliedDelta,
    awardedPoints: appliedDelta * COLLEGE_AREA_BONUS_POINTS_PER_STEP,
    leaderboardRecalculated,
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
      leaderboard.push({ uid, totalPoints, totalSteps, bonusPoints, faculty: profile.faculty });
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