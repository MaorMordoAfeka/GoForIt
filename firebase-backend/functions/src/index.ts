import * as admin from "firebase-admin";
import { DateTime } from "luxon";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";

admin.initializeApp();

const db = admin.firestore();
const messaging = admin.messaging();

const DEFAULT_TZ = "Asia/Jerusalem";

type CallableAuth = { uid: string };
type CallableRequest<TData> = {
  data: TData;
  auth?: CallableAuth | null;
};

type UserProfile = {
  timezone: string;
  lowActivityNudgeEnabled: boolean;
  preferredActiveInterval: number | null;
  preferredInactiveInterval: number | null;
  faculty: string;
};

// -------------------- helpers --------------------

function assertAuth<T>(req: CallableRequest<T>): string {
  const uid = req.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Login required.");
  return uid;
}

function toDayKey(dt: DateTime): string {
  // Luxon toISODate() is typed as string | null (invalid DateTime can return null).
  // This guarantees a YYYY-MM-DD string.
  return dt.toISODate() ?? dt.toFormat("yyyy-MM-dd");
}

function validDayKey(dayKey: unknown): string {
  if (typeof dayKey !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(dayKey)) {
    throw new HttpsError("invalid-argument", "dayKey must be YYYY-MM-DD");
  }
  return dayKey;
}

function validInterval(x: unknown): number {
  if (typeof x !== "number" || !Number.isInteger(x) || x < 0 || x > 5) {
    throw new HttpsError("invalid-argument", "intervalIndex must be 0..5");
  }
  return x;
}

function safeSteps(x: unknown): number {
  if (typeof x !== "number" || !Number.isInteger(x) || x < 0 || x > 200000) {
    throw new HttpsError(
      "invalid-argument",
      "stepsTotal must be a non-negative int (reasonable range).",
    );
  }
  return x;
}

function intervalStart(dayKey: string, intervalIndex: number, tz: string): DateTime {
  const base = DateTime.fromISO(dayKey, { zone: tz }).startOf("day");
  return base.plus({ hours: intervalIndex * 4 });
}

function intervalEnd(dayKey: string, intervalIndex: number, tz: string): DateTime {
  return intervalStart(dayKey, intervalIndex, tz).plus({ hours: 4 });
}

function computeSendAt(dayKey: string, intervalIndex: number, tz: string): admin.firestore.Timestamp {
  let dt = intervalStart(dayKey, intervalIndex, tz).plus({ minutes: 5 });

  // Do not send between 00:00–06:00
  if (dt.hour < 6) {
    dt = dt.set({ hour: 6, minute: 5, second: 0, millisecond: 0 });
  }

  return admin.firestore.Timestamp.fromDate(dt.toJSDate());
}

async function getUserProfile(uid: string): Promise<UserProfile> {
  const snap = await db.doc(`users/${uid}`).get();
  const data = snap.data() ?? {};

  const tzRaw = data.timezone;
  const timezone =
    typeof tzRaw === "string" && tzRaw.trim().length > 0 ? tzRaw.trim() : DEFAULT_TZ;

  const lowActivityNudgeEnabled =
    typeof data.lowActivityNudgeEnabled === "boolean" ? data.lowActivityNudgeEnabled : true;

  const preferredActiveInterval =
    Number.isInteger(data.preferredActiveInterval) ? (data.preferredActiveInterval as number) : null;

  const preferredInactiveInterval =
    Number.isInteger(data.preferredInactiveInterval)
      ? (data.preferredInactiveInterval as number)
      : null;

  const faculty = typeof data.faculty === "string" ? data.faculty : "";

  return {
    timezone,
    lowActivityNudgeEnabled,
    preferredActiveInterval,
    preferredInactiveInterval,
    faculty,
  };
}

function calcStepPoints(totalSteps: number): number {
  // Example: 1 point per 100 steps (adjust as needed)
  return Math.floor(totalSteps / 100);
}

// -------------------- callable: register FCM token --------------------

export const registerFcmToken = onCall(async (request) => {
  const req = request as CallableRequest<{ token?: unknown }>;
  const uid = assertAuth(req);

  const token = req.data?.token;
  if (typeof token !== "string" || token.length < 20) {
    throw new HttpsError("invalid-argument", "token is required.");
  }

  await db.doc(`users/${uid}/fcm_tokens/${token}`).set(
    {
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      platform: "android",
    },
    { merge: true },
  );

  return { ok: true };
});

// -------------------- callable: upload 4-hour bucket steps --------------------

export const uploadStepInterval = onCall(async (request) => {
  const req = request as CallableRequest<{
    dayKey?: unknown;
    intervalIndex?: unknown;
    stepsTotal?: unknown;
    uploadIntervalIndex?: unknown;
    attributedIntervalIndex?: unknown;
  }>;

  const uid = assertAuth(req);

  const dayKey = validDayKey(req.data?.dayKey);
  const intervalIndex = validInterval(req.data?.intervalIndex);
  const stepsTotal = safeSteps(req.data?.stepsTotal);

  const uploadIntervalIndex = validInterval(req.data?.uploadIntervalIndex ?? intervalIndex);
  const attributedIntervalIndex = validInterval(req.data?.attributedIntervalIndex ?? intervalIndex);

  const profile = await getUserProfile(uid);

  const sessionId = `${dayKey}_${intervalIndex}`;
  const sessionRef = db.doc(`users/${uid}/step_sessions/${sessionId}`);
  const dailyRef = db.doc(`users/${uid}/daily/${dayKey}`);

  const start = intervalStart(dayKey, intervalIndex, profile.timezone);
  const end = intervalEnd(dayKey, intervalIndex, profile.timezone);

  await db.runTransaction(async (tx) => {
    const prevSessionSnap = await tx.get(sessionRef);
    const prevSteps = prevSessionSnap.exists
      ? (prevSessionSnap.data()?.stepsTotal as number | undefined) ?? 0
      : 0;

    // delta to keep daily totals correct if client retries / overwrites
    let delta = stepsTotal - prevSteps;
    if (delta < 0) delta = stepsTotal;

    tx.set(
      sessionRef,
      {
        sessionId,
        uid,
        dayKey,
        intervalIndex,
        startAt: admin.firestore.Timestamp.fromDate(start.toJSDate()),
        endAt: admin.firestore.Timestamp.fromDate(end.toJSDate()),
        stepsTotal,
        uploadIntervalIndex,
        attributedIntervalIndex,
        uploadedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

    const dailySnap = await tx.get(dailyRef);
    const daily = dailySnap.data() ?? {};

    const stepsByInterval: number[] = Array.isArray(daily.stepsByInterval)
      ? (daily.stepsByInterval as number[])
      : [0, 0, 0, 0, 0, 0];

    while (stepsByInterval.length < 6) stepsByInterval.push(0);

    stepsByInterval[intervalIndex] = (stepsByInterval[intervalIndex] ?? 0) + delta;

    const totalSteps = (Number.isInteger(daily.totalSteps) ? (daily.totalSteps as number) : 0) + delta;

    tx.set(
      dailyRef,
      {
        stepsByInterval,
        totalSteps,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        didBonus: typeof daily.didBonus === "boolean" ? daily.didBonus : false,
        bonusPoints: Number.isInteger(daily.bonusPoints) ? daily.bonusPoints : 0,
      },
      { merge: true },
    );
  });

  return { ok: true };
});

// -------------------- callable: record bonus visit --------------------

export const recordBonusVisit = onCall(async (request) => {
  const req = request as CallableRequest<{ stationId?: unknown; visitedAtMs?: unknown }>;
  const uid = assertAuth(req);

  const stationId = req.data?.stationId;
  if (typeof stationId !== "string" || stationId.trim().length === 0) {
    throw new HttpsError("invalid-argument", "stationId is required.");
  }

  const visitedAtMsRaw = req.data?.visitedAtMs;
  if (typeof visitedAtMsRaw !== "number" || !Number.isInteger(visitedAtMsRaw)) {
    throw new HttpsError("invalid-argument", "visitedAtMs is required.");
  }

  const profile = await getUserProfile(uid);
  const visitedDt = DateTime.fromMillis(visitedAtMsRaw, { zone: profile.timezone });
  const dayKey = toDayKey(visitedDt);

  const stationSnap = await db.doc(`bonus_stations/${stationId}`).get();
  if (!stationSnap.exists) {
    throw new HttpsError("not-found", "bonus station not found.");
  }

  const pointsValue = Number.isInteger(stationSnap.data()?.pointsValue)
    ? (stationSnap.data()?.pointsValue as number)
    : 0;

  // ONLY ONE BONUS PER DAY: doc id is dayKey (deterministic)
  const visitRef = db.doc(`users/${uid}/bonus_visits/${dayKey}`);
  const dailyRef = db.doc(`users/${uid}/daily/${dayKey}`);

  let alreadyClaimed = false;

  await db.runTransaction(async (tx) => {
    const [dailySnap, visitSnap] = await Promise.all([tx.get(dailyRef), tx.get(visitRef)]);
    const daily = dailySnap.data() ?? {};

    const didBonus = typeof daily.didBonus === "boolean" ? daily.didBonus : false;

    // If already claimed (flag or existing visit doc) => do nothing
    if (didBonus || visitSnap.exists) {
      alreadyClaimed = true;
      return;
    }

    // Create the single-per-day visit record
    tx.set(visitRef, {
      visitId: visitRef.id, // == dayKey
      uid,
      stationId,
      visitedAt: admin.firestore.Timestamp.fromDate(visitedDt.toJSDate()),
      awardedPoints: pointsValue,
      isAwarded: true,
      dayKey,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    // Update daily aggregate exactly once
    const prevBonus = Number.isInteger(daily.bonusPoints) ? (daily.bonusPoints as number) : 0;

    tx.set(
      dailyRef,
      {
        didBonus: true,
        bonusPoints: prevBonus + pointsValue,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  });

  if (alreadyClaimed) {
    return { ok: false, dayKey, awardedPoints: 0, reason: "ALREADY_CLAIMED" };
  }

  return { ok: true, dayKey, awardedPoints: pointsValue };
});


// -------------------- scheduled: end-of-day finalize --------------------

export const finalizeDay = onSchedule(
  { schedule: "55 23 * * *", timeZone: DEFAULT_TZ },
  async () => {
    const now = DateTime.now().setZone(DEFAULT_TZ);
    const todayKey = toDayKey(now);

    logger.info(`finalizeDay: ${todayKey}`);

    const usersSnap = await db.collection("users").get();

    const leaderboard: Array<{
      uid: string;
      totalPoints: number;
      totalSteps: number;
      bonusPoints: number;
      faculty: string;
    }> = [];

    for (const u of usersSnap.docs) {
      const uid = u.id;
      const profile = await getUserProfile(uid);

      const dailyRef = db.doc(`users/${uid}/daily/${todayKey}`);
      const dailySnap = await dailyRef.get();
      if (!dailySnap.exists) continue;

      const daily = dailySnap.data() ?? {};
      const stepsByInterval: number[] = Array.isArray(daily.stepsByInterval)
        ? (daily.stepsByInterval as number[])
        : [0, 0, 0, 0, 0, 0];

      while (stepsByInterval.length < 6) stepsByInterval.push(0);

      const totalSteps = Number.isInteger(daily.totalSteps) ? (daily.totalSteps as number) : 0;
      const bonusPoints = Number.isInteger(daily.bonusPoints) ? (daily.bonusPoints as number) : 0;
      const didBonus = typeof daily.didBonus === "boolean" ? daily.didBonus : false;

      // best + worst interval
      let bestInterval = 0;
      let worstInterval = 0;
      for (let i = 1; i < 6; i++) {
        if ((stepsByInterval[i] ?? 0) > (stepsByInterval[bestInterval] ?? 0)) bestInterval = i;
        if ((stepsByInterval[i] ?? 0) < (stepsByInterval[worstInterval] ?? 0)) worstInterval = i;
      }

      await db.doc(`users/${uid}/daily_summaries/${todayKey}`).set(
        {
          bestInterval,
          worstInterval,
          stepsByInterval,
          totalSteps,
          didBonus,
          bonusPoints,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true },
      );

      // Choose tomorrow interval:
      // active = learned preferredActiveInterval else today's bestInterval
      // inactive = learned preferredInactiveInterval else today's worstInterval
      // If lowActivityNudgeEnabled => earliest among (active, inactive), else active.
      const active = profile.preferredActiveInterval ?? bestInterval;
      const inactive = profile.preferredInactiveInterval ?? worstInterval;

      let chosen = active;
      if (profile.lowActivityNudgeEnabled) {
        chosen = Math.min(active, inactive);
      }

      const nextDayKey = toDayKey(now.plus({ days: 1 }));
      const sendAt = computeSendAt(nextDayKey, chosen, profile.timezone);

      const jobId = `${uid}_${nextDayKey}`;
      await db.doc(`notification_jobs/${jobId}`).set(
        {
          uid,
          dayKey: nextDayKey,
          intervalIndex: chosen,
          sendAt,
          type: "REMINDER",
          status: "PENDING",
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          sentAt: null,
          lastError: null,
        },
        { merge: true },
      );

      const totalPoints = calcStepPoints(totalSteps) + bonusPoints;
      leaderboard.push({ uid, totalPoints, totalSteps, bonusPoints, faculty: profile.faculty });
    }

    leaderboard.sort((a, b) => b.totalPoints - a.totalPoints);

    const batch = db.batch();
    leaderboard.forEach((e, idx) => {
      const ref = db.doc(`leaderboards_daily/${todayKey}/entries/${e.uid}`);
      batch.set(
        ref,
        {
          uid: e.uid,
          totalPoints: e.totalPoints,
          totalSteps: e.totalSteps,
          bonusPoints: e.bonusPoints,
          rank: idx + 1,
          faculty: e.faculty,
        },
        { merge: true },
      );
    });

    await batch.commit();
  },
);

// -------------------- scheduled: dispatch notification jobs --------------------

export const dispatchNotificationJobs = onSchedule(
  { schedule: "*/15 6-23 * * *", timeZone: DEFAULT_TZ },
  async () => {
    const nowTs = admin.firestore.Timestamp.now();

    const jobsSnap = await db
      .collection("notification_jobs")
      .where("status", "==", "PENDING")
      .where("sendAt", "<=", nowTs)
      .limit(50)
      .get();

    for (const job of jobsSnap.docs) {
      const data = job.data();
      const uid = typeof data.uid === "string" ? data.uid : "";

      if (!uid) {
        await job.ref.set(
          {
            status: "FAILED",
            lastError: "Missing uid in job doc",
            sentAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          { merge: true },
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
            { merge: true },
          );
          continue;
        }

        const intervalIndex = typeof data.intervalIndex === "number" ? String(data.intervalIndex) : "";
        const dayKey = typeof data.dayKey === "string" ? data.dayKey : "";

        const res = await messaging.sendEachForMulticast({
          tokens,
          notification: {
            title: "Go for it!",
            body: "Time for some activity 💪",
          },
          data: {
            type: "REMINDER",
            intervalIndex,
            dayKey,
          },
        });

        const toDelete: string[] = [];
        res.responses.forEach((r, idx) => {
          if (!r.success) {
            const code = (r.error as { code?: string } | undefined)?.code ?? "";
            if (
              code.includes("registration-token-not-registered") ||
              code.includes("invalid-registration-token")
            ) {
              toDelete.push(tokens[idx]);
            }
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
          },
          { merge: true },
        );
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        await job.ref.set(
          {
            status: "FAILED",
            lastError: msg,
            sentAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
      }
    }
  },
);

// -------------------- scheduled: monthly learning --------------------

export const finalizeMonth = onSchedule(
  { schedule: "10 0 1 * *", timeZone: DEFAULT_TZ },
  async () => {
    const now = DateTime.now().setZone(DEFAULT_TZ);
    const lastMonthStart = now.minus({ months: 1 }).startOf("month");
    const lastMonthEnd = lastMonthStart.endOf("month");

    logger.info(
      `finalizeMonth: ${toDayKey(lastMonthStart)} -> ${toDayKey(lastMonthEnd)}`,
    );

    const usersSnap = await db.collection("users").get();

    for (const u of usersSnap.docs) {
      const uid = u.id;
      const profile = await getUserProfile(uid);

      const bestHist = [0, 0, 0, 0, 0, 0];
      const worstHist = [0, 0, 0, 0, 0, 0];

      const sumsSnap = await db
        .collection(`users/${uid}/daily_summaries`)
        .where(
          "createdAt",
          ">=",
          admin.firestore.Timestamp.fromDate(lastMonthStart.toJSDate()),
        )
        .where(
          "createdAt",
          "<=",
          admin.firestore.Timestamp.fromDate(lastMonthEnd.toJSDate()),
        )
        .get();

      sumsSnap.docs.forEach((d) => {
        const s = d.data();
        const b = s.bestInterval;
        const w = s.worstInterval;

        if (Number.isInteger(b) && b >= 0 && b <= 5) bestHist[b]++;
        if (Number.isInteger(w) && w >= 0 && w <= 5) worstHist[w]++;
      });

      const mode = (hist: number[]): number => {
        let m = 0;
        for (let i = 1; i < 6; i++) if (hist[i] > hist[m]) m = i;
        return m;
      };

      // Update only if we have enough days (avoid learning from too little data)
      if (sumsSnap.size >= 5) {
        await db.doc(`users/${uid}`).set(
          {
            preferredActiveInterval: mode(bestHist),
            preferredInactiveInterval: mode(worstHist),
            timezone: profile.timezone,
          },
          { merge: true },
        );
      }
    }
  },
);
