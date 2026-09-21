package com.videa.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

object FcmTokenHelper {
    @JvmStatic
    var latestToken: String? = null

    @JvmStatic
    fun getToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                latestToken = token
                Log.d("FCM_TOKEN", "Token ni bozz: $token")
            }
            .addOnFailureListener { e ->
                Log.e("FCM_TOKEN", "Gagal ambil token", e)
            }
    }
}