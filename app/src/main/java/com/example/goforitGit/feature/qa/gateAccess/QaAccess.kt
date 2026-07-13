package com.example.goforitGit.feature.qa

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

/**
 * Central gate for the temporary QA tools.
 *
 * The QA entry point is shown only when BOTH the Firebase email and UID match
 * the dedicated QA account. Every QA Activity also checks this again before it
 * renders, so hiding the home-screen card is not the only protection.
 */
object QaAccess {
    const val QA_EMAIL = "goforit.qa@test.com"
    const val QA_UID = "VozEYhZqQMVDaTXy7iNfrQ4vsJ13"

    fun isAuthorized(user: FirebaseUser? = FirebaseAuth.getInstance().currentUser): Boolean {
        return user?.uid == QA_UID &&
                user.email?.equals(QA_EMAIL, ignoreCase = true) == true
    }
}
