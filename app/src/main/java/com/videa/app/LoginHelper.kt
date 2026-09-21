package com.videa.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

object LoginHelper {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()

    interface LoginCallback {
        fun onLoading(isLoading: Boolean)
        fun onError(message: String)
        fun onSuccess()
    }

    @JvmStatic
    fun onLogin(
        context: Context,
        tenantCode: String,
        registeredNumber: String,
        password: String,
        role: String,
        fcmToken: String,
        deviceType: String,
        callback: LoginCallback
    ) {
        try {
            // ENDPOINT UTAMA LOGIN: sama persis dengan yang dipakai form web (/api/auth/sign).
            // Endpoint ini generik untuk semua role (Siswa/Guru/Ortu) -- backend mendeteksi
            // sendiri jenis user dari registered_number yang dikirim, TIDAK butuh field "role".
            val authUrlString = BuildConfig.AUTH_API_URL
            if (authUrlString.isBlank()) {
                callback.onError("AUTH_API_URL belum diset di env.properties")
                return
            }

            val httpUrl = authUrlString.toHttpUrlOrNull()
            if (httpUrl == null) {
                callback.onError("AUTH_API_URL tidak valid: $authUrlString")
                return
            }

            val finalUrl = httpUrl.newBuilder()
                .addQueryParameter("registered_number", registeredNumber)
                .addQueryParameter("password", password)
                .addQueryParameter("include_videapay", "true")
                .build()

            Log.d("LoginHelper", "Auth Request URL: $finalUrl")

            val authRequest = Request.Builder()
                .url(finalUrl)
                .get()
                .build()

            callback.onLoading(true)

            client.newCall(authRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("LoginHelper", "Auth Network Error: ${e.message}")
                    postToUI {
                        callback.onLoading(false)
                        callback.onError("Network Error")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    val responseBody = response.body?.string() ?: ""
                    Log.d("LoginHelper", "Auth Response Code: $code | Body: $responseBody")

                    if (!response.isSuccessful) {
                        postToUI {
                            callback.onLoading(false)
                            callback.onError("Gagal ($code): $responseBody")
                        }
                        return
                    }

                    // Login berhasil -> lanjut daftarkan FCM token & fetch exit PIN (khusus siswa)
                    registerFcmToken(fcmToken, deviceType, tenantCode, registeredNumber)
                    if (role.equals("Siswa", ignoreCase = true)) {
                        fetchExitPinFromServer(context, tenantCode)
                    }

                    postToUI {
                        callback.onLoading(false)
                        PreferenceHelper.saveLoginData(context, tenantCode, registeredNumber, password, role)
                        callback.onSuccess()
                    }
                }
            })
        } catch (e: Exception) {
            callback.onError("Unexpected Error: ${e.message}")
        }
    }

    /**
     * Daftar FCM token secara terpisah dari proses login, memakai endpoint lama (/api/v1/fcm-token).
     * Dijalankan fire-and-forget: kalau gagal, cuma dicatat di log, tidak mengganggu alur login.
     */
    private fun registerFcmToken(
        fcmToken: String,
        deviceType: String,
        tenantCode: String,
        registeredNumber: String
    ) {
        try {
            val json = JSONObject()
            json.put("tenant_code", tenantCode)
            json.put("registered_number", registeredNumber)
            json.put("fcm_token", fcmToken)
            json.put("device_type", deviceType)

            val body = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url(BuildConfig.FCM_API_URL)
                .post(body)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w("LoginHelper", "FCM token gagal didaftarkan: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    Log.d("LoginHelper", "FCM Register Response Code: ${response.code}")
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.w("LoginHelper", "Gagal menyusun request FCM token: ${e.message}")
        }
    }

    private fun fetchExitPinFromServer(context: Context, tenantCode: String) {
        try {
            val authUrlString = BuildConfig.AUTH_API_URL
            val httpUrl = authUrlString.toHttpUrlOrNull() ?: return
            val pinUrl = httpUrl.newBuilder()
                .encodedPath("/api/v1/exit-pin")
                .addQueryParameter("tenant_code", tenantCode)
                .build()

            val request = Request.Builder()
                .url(pinUrl)
                .get()
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w("LoginHelper", "Gagal fetch exit PIN: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: ""
                        if (response.isSuccessful && body.isNotBlank()) {
                            val json = JSONObject(body)
                            val pin = json.optString("exit_pin", "")
                            if (pin.isNotBlank()) {
                                PreferenceHelper.saveExitPin(context.applicationContext, pin)
                                Log.d("LoginHelper", "Berhasil mendapatkan dan menyimpan Exit PIN dari server: $pin")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("LoginHelper", "Parsing exit PIN error: ${e.message}")
                    } finally {
                        response.close()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w("LoginHelper", "Gagal menyusun request exit PIN: ${e.message}")
        }
    }

    private fun postToUI(runnable: Runnable) {
        Handler(Looper.getMainLooper()).post(runnable)
    }
}