package com.example.goforitGit.navigation

import android.app.Activity
import android.content.Intent

/**
 * Opens MainActivity and asks it to show the existing navigation drawer.
 */
object DrawerNavigator {

    const val EXTRA_OPEN_DRAWER =
        "com.example.goforitGit.extra.OPEN_DRAWER"

    fun open(activity: Activity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_DRAWER, true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
    }
}
