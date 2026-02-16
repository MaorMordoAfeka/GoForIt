package com.example.goforitGit.server_side_module

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.goforitGit.MainActivity
import com.example.goforitGit.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class firebaseMessagingService : FirebaseMessagingService() {

    private val CHANNEL_ID = "reminders"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Activity reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // When app is in foreground, you must show it yourself for visibility.
        val title = message.notification?.title ?: "Reminder"
        val body = message.notification?.body ?: "Time to move!"

        showLocalNotification(title, body)
    }

    override fun onNewToken(token: String) {
        // Token can rotate.
        // For PoC: you can ignore here and just call registerFcmTokenResult()
        // on app start after login.
        // For production: re-register to server here.
    }

    private fun showLocalNotification(title: String, body: String) {
        ensureChannel(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this,  CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val generatedId = System.currentTimeMillis() % 100000
        NotificationManagerCompat.from(this).notify((generatedId).toInt(), notification)
    }
}
