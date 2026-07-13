const { applicationDefault, getApps, initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");

const uid = process.argv[2];

if (!uid) {
    throw new Error("Usage: node scripts/grantQaTester.cjs <QA_USER_UID>");
}

if (getApps().length === 0) {
    initializeApp({
        credential: applicationDefault()
    });
}

async function main() {
    const auth = getAuth();

    const user = await auth.getUser(uid);
    const existingClaims = user.customClaims ?? {};

    await auth.setCustomUserClaims(uid, {
        ...existingClaims,
        qaTester: true
    });

    console.log(`qaTester=true was assigned to UID: ${uid}`);
}

main().catch((error) => {
    console.error(error);
    process.exit(1);
});