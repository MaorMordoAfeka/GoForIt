const admin = require("firebase-admin");

if (admin.apps.length === 0) {
    admin.initializeApp({
        credential: admin.credential.applicationDefault()
    });
}

const db = admin.firestore();

const dayKey = process.argv[2] || "2099-12-31";
const deleteOnly = process.argv.includes("--delete");

const dayRef = db.collection("leaderboards_daily").doc(dayKey);
const entriesRef = dayRef.collection("entries");

async function deleteExistingEntries() {
    const snapshot = await entriesRef.get();
    const documents = snapshot.docs;

    for (let start = 0; start < documents.length; start += 400) {
        const batch = db.batch();

        for (const document of documents.slice(start, start + 400)) {
            batch.delete(document.ref);
        }

        await batch.commit();
    }

    console.log(`Deleted ${documents.length} existing entries for ${dayKey}.`);
}

async function seedQaEntries() {
    await deleteExistingEntries();

    await dayRef.set(
        {
            dayKey,
            isQaData: true,
            description: "Controlled leaderboard QA dataset",
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        },
        { merge: true }
    );

    const faculties = [
        "Software Engineering",
        "Computer Science",
        "Data Science",
        "Electrical Engineering",
        "Information Systems Engineering"
    ];

    const batch = db.batch();

    for (let rank = 1; rank <= 20; rank++) {
        const paddedRank = String(rank).padStart(2, "0");
        const uid = `qa_leaderboard_user_${paddedRank}`;

        const totalSteps = 50_000 - ((rank - 1) * 1_250);
        const bonusPoints = 1_000 - ((rank - 1) * 25);
        const totalPoints = Math.floor(totalSteps / 100) + bonusPoints;

        const entryRef = entriesRef.doc(uid);

        batch.set(entryRef, {
            uid,
            dayKey,
            rank,
            totalPoints,
            totalSteps,
            bonusPoints,
            username: `QA User ${paddedRank}`,

            // Non-empty so the leaderboard does not attempt an additional
            // public-profile Firebase request during the timing test.
            profileImageUrl: "https://example.invalid/qa-avatar.png",

            faculty: faculties[(rank - 1) % faculties.length],
            isQaData: true,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
    }

    await batch.commit();

    console.log(`Created 20 QA leaderboard entries for ${dayKey}.`);
    console.log(
        `Firestore path: leaderboards_daily/${dayKey}/entries`
    );
}

async function deleteQaData() {
    await deleteExistingEntries();
    await dayRef.delete();

    console.log(`Removed QA leaderboard data for ${dayKey}.`);
}

async function main() {
    if (deleteOnly) {
        await deleteQaData();
    } else {
        await seedQaEntries();
    }
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error("Leaderboard QA seeding failed:", error);
        process.exit(1);
    });