package com.idnoffice.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.idnoffice.app.LoginHelper.LoginCallback
import com.idnoffice.app.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupLoginButton()
    }

    private fun setupUI() {
        binding.logoImage.load("https://smpinovasidalamnegeri.videaclass.com/favicon/300.png") {
            crossfade(true)
        }

        binding.loginCard.apply {
            startAnimation(AnimationUtils.loadAnimation(this@LoginActivity, R.anim.fade_in))
            startAnimation(AnimationUtils.loadAnimation(this@LoginActivity, R.anim.slide_up))
        }
    }

    private fun setupLoginButton() {
        binding.btnLogin.setOnClickListener {
            val tenantCode = BuildConfig.TENANT_CODE
            val registeredNumber = binding.inputRegisteredNumber.text.toString()
            val fcmToken = FcmTokenHelper.latestToken
            val deviceType = "Android ${Build.BRAND} ${Build.MODEL}"

            if (tenantCode.isBlank() || registeredNumber.isBlank()) {
                showToast("Lengkapi semua input")
                return@setOnClickListener
            }

            if (fcmToken == null) {
                showToast("Mengambil token... coba lagi")
                return@setOnClickListener
            }

            setButtonLoading(true)
            LoginHelper.onLogin(
                this@LoginActivity,
                tenantCode,
                registeredNumber,
                fcmToken,
                deviceType,
                object : LoginCallback {
                    override fun onLoading(isLoading: Boolean) {
                        setButtonLoading(isLoading)
                    }

                    override fun onError(message: String?) {
                        showToast(message!!)
                    }

                    override fun onSuccess() {
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    }
                }
            )
        }
    }

    private fun setButtonLoading(isLoading: Boolean) {
        binding.btnLogin.apply {
            isEnabled = !isLoading
            text = if (isLoading) "Loading..." else "Login"
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}