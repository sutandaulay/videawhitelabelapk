package com.videa.app

import android.app.NotificationChannel
import com.videa.app.R
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        var title: String? = null
        var body: String? = null
        var actionUrl: String? = null

        remoteMessage.notification?.let {
            title = it.title
            body = it.body
        }

        if (remoteMessage.data.isNotEmpty()) {
            val type = remoteMessage.data["type"]
            val tenantCodeMsg = remoteMessage.data["tenant_code"]
            val newPin = remoteMessage.data["exit_pin"]

            if (type == "UPDATE_EXIT_PIN" && !newPin.isNullOrBlank()) {
                val currentTenant = PreferenceHelper.getTenantCode(applicationContext)
                // Jika tenantCodeMsg kosong atau cocok dengan tenant saat ini, simpan PIN
                if (tenantCodeMsg.isNullOrBlank() || tenantCodeMsg == currentTenant) {
                    PreferenceHelper.saveExitPin(applicationContext, newPin)
                    Log.d("FCM", "PIN Keluar berhasil diperbarui via FCM: $newPin")
                }
            }

            remoteMessage.data["title"]?.let { title = it }
            remoteMessage.data["body"]?.let { body = it }
            remoteMessage.data["actionUrl"]?.let { actionUrl = it }
        }

        showNotification(title, body, actionUrl)
    }

    private fun showNotification(title: String?, body: String?, actionUrl: String?) {
        val channelId = "videaclass_channel"
        val soundUri = Uri.parse("android.resource://$packageName/${R.raw.notif_sound}")

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VideaClass Notification",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setSound(soundUri, audioAttributes)
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        }

        val notifId = System.currentTimeMillis().toInt()

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("actionUrl", actionUrl)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 300, 200, 300))

        manager.notify(notifId, builder.build())
    }
}