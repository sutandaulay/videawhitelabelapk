package com.idnoffice.app;
import android.util.Log;
import com.google.firebase.messaging.FirebaseMessaging;
public class FcmTokenHelper {

    public static String latestToken = null;
    public static void getToken() {
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    latestToken = token;
                    Log.d("FCM_TOKEN", "Token ni bozz: " + token);
                })
                .addOnFailureListener(e -> {
                    Log.e("FCM_TOKEN", "Gagal ambil token", e);
                });
    }
}
