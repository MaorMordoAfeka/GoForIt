package com.example.goforitGit.core.data.FirebaseData

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.goforitGit.navigation.MainActivity
import com.example.goforitGit.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class firebaseMessagingService : FirebaseMessagingService() {

    private val CHANNEL_ID = "reminders"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Activity reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun canPostNotifications(): Boolean {
        // Android 12 and below: no runtime notification permission exists
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        // Android 13+: user can deny POST_NOTIFICATIONS
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "Reminder"
        val body = message.notification?.body ?: "Time to move!"
        showLocalNotification(title, body)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Re-register the new token on the server (if user is logged in)
        val uid = FirebaseServerApi.currentUser()?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            FirebaseServerApi.registerFcmTokenResult()
        }
    }

    private fun showLocalNotification(title: String, body: String) {
        ensureChannel(this)

        // check permission before notify
        if (!canPostNotifications()) {
            // No permission: can't show system notification.
            // Optionally: store it somewhere / show in-app next time.
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val generatedId = (System.currentTimeMillis() % 100000).toInt()

        // Extra safety: explicitly handle possible SecurityException
        try {
            NotificationManagerCompat.from(this).notify(generatedId, notification)
        } catch (e: SecurityException) {
            // Permission was revoked mid-run / OEM behavior — ignore or log.
        }
    }
}