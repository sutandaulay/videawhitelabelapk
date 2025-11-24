package com.idnoffice.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity


class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FcmTokenHelper.getToken()

        setContentView(R.layout.activity_splash)
        supportActionBar?.hide()

        val tenant = PreferenceHelper.getTenantCode(this)
        val registered = PreferenceHelper.getRegisteredNumber(this)

        waitForFcmToken { token ->

            if (tenant != null && registered != null && token != null) {

                val deviceType = "Android ${android.os.Build.BRAND} ${android.os.Build.MODEL}"

                LoginHelper.onLogin(
                    this,
                    tenant,
                    registered,
                    token,
                    deviceType,
                    object : LoginHelper.LoginCallback {
                        override fun onLoading(isLoading: Boolean) {

                        }

                        override fun onError(message: String) {
                            startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
                            finish()
                        }

                        override fun onSuccess() {
                            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                            finish()
                        }
                    }
                )

                return@waitForFcmToken
            }

            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun waitForFcmToken(onReady: (String?) -> Unit) {
        var retries = 0
        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                val token = FcmTokenHelper.latestToken

                if (token != null || retries >= 10) {
                    onReady(token)
                    return
                }

                retries++
                handler.postDelayed(this, 300)
            }
        }

        handler.post(runnable)
    }
}
