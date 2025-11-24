package com.idnoffice.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {

        String title = "Notifikasi";
        String body  = "Anda punya pesan baru";

        if (!remoteMessage.getData().isEmpty()) {
            if (remoteMessage.getData().get("title") != null)
                title = remoteMessage.getData().get("title");

            if (remoteMessage.getData().get("body") != null)
                body = remoteMessage.getData().get("body");
        }

        showNotification(title, body);
    }

    private void showNotification(String title, String body) {

        String channelId = "bade_channel_v4";

        Uri defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "VideaClass Notification",
                    NotificationManager.IMPORTANCE_HIGH
            );

            long[] vibration = {0, 300, 200, 300};
            channel.enableVibration(true);
            channel.setVibrationPattern(vibration);

            channel.setSound(defaultSound, null);

            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.logo)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setSound(defaultSound)
                        .setVibrate(new long[]{0, 300, 200, 300});

        manager.notify((int) System.currentTimeMillis(), builder.build());
    }
}
