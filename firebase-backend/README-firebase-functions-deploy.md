# Firebase Cloud Functions (TypeScript) — Deploy Guide (Windows + Android Studio Terminal)

This guide explains how to **deploy** your updated `functions/src/index.ts` to Firebase Cloud Functions.
> Note: You **do not upload** `index.ts` through the Firebase Console UI — you deploy it using the Firebase CLI.

---

## Prerequisites (one-time setup)

### 1) Install Node.js (includes npm)
1. Install **Node.js LTS**
2. Verify in **PowerShell**:
```powershell
node -v
npm -v
```

### 2) Install Firebase CLI
```powershell
npm i -g firebase-tools
firebase -V
```

### 3) Login to Firebase
```powershell
firebase login
```

### 4) Make sure you are in the correct project folder
our android project contains a folder like this:
```
firebase-backend/
  firebase.json
  functions/
    package.json
    src/index.ts
```

---

## Deploy workflow (EVERY TIME `index.ts` IS CHANGED!!!!!!!!)

### Step 1 — Edit `index.ts`
Edit and save:
```
firebase-backend/functions/src/index.ts
```

### Step 2 — Build (TypeScript → JavaScript)
Open **Android Studio → Terminal** and run:

```powershell
cd C:\Users\YOURUSERNAME\AndroidStudioProjects\GoForItGit\firebase-backend\functions
npm install
npm run build
```

> `npm install` is safe to run multiple times (it only updates dependencies if needed).

### Step 3 — Deploy to Firebase

#### Deploy **all** functions
```powershell
cd ..
firebase deploy --only functions
```

#### Deploy **only one function** (faster)
Example (recommended when you change only one callable):
```powershell
firebase deploy --only functions:recordBonusVisit
```

---

## Verify deployment

1. Firebase Console → **Build → Functions**
2. Check your function shows an updated **“Last deployed”** time
3. Open the function → check **Logs** if something seems wrong

---

## Troubleshooting

### A) Lint errors block deployment
You might see something like:
`Running command: npm --prefix "$RESOURCE_DIR" run lint`

**Fix option 1 (recommended): auto-fix lint**
```powershell
cd functions
npm run lint -- --fix
cd ..
firebase deploy --only functions
```

**Fix option 2 (PoC-friendly): temporarily skip lint in predeploy**
In `firebase-backend/firebase.json`, remove the lint line from `functions.predeploy` (keep build).

---

### B) `npm` is not recognized
That means Node.js is not installed or not in PATH.

Fix:
1. Install Node.js LTS
2. Close and reopen terminal / Android Studio
3. Verify:
```powershell
node -v
npm -v
```

---

### C) TypeScript / ESLint version mismatch warnings
If you see TypeScript version warnings, the deployment can still succeed, but if it fails:
- Use the versions that your template expects (pin TypeScript in `functions/package.json` if needed)
- Then run:
```powershell
cd functions
npm install
npm run build
```

---

## Staying at $0 billing (recommended)

When deploying Functions, container images may accumulate in **Artifact Registry**.
To prevent storage buildup:
- Create a **cleanup policy** for the `us-central1` Artifact Registry repository (keep **1 day** is fine).
- This helps you stay within the free storage tier for a small PoC.

---

## Quick command summary

From `firebase-backend/functions`:
```powershell
npm install
npm run build
```

From `firebase-backend`:
```powershell
firebase deploy --only functions
# or just one:
firebase deploy --only functions:recordBonusVisit
```
