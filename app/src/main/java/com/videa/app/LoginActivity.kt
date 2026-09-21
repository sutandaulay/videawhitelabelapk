package com.videa.app

import android.content.Intent
import com.videa.app.R
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.videa.app.LoginHelper.LoginCallback
import com.videa.app.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupLoginButton()
        playAnimations()
    }

    private fun playAnimations() {
        binding.topSection.alpha = 0f
        binding.topSection.translationY = -50f
        binding.loginCard.alpha = 0f
        binding.loginCard.translationY = 50f
        binding.videaClassLink.alpha = 0f

        binding.topSection.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(200)
            .start()

        binding.loginCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(400)
            .start()

        binding.videaClassLink.animate()
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(800)
            .start()
    }

    private fun setupUI() {
        binding.helpClick.setOnClickListener {
            openUrl("https://wa.me/6285880255326?text=Halo, saya butuh bantuan mengenai aplikasi white-label VideaClass.")
        }

        binding.videaClassLink.setOnClickListener {
            openUrl("https://videaclass.com")
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }


    private fun setupLoginButton() {
        binding.btnLogin.setOnClickListener {
            val tenantCode = BuildConfig.TENANT_CODE
            val registeredNumber = binding.inputRegisteredNumber.text.toString()
            val password = binding.inputPassword.text.toString()
            val role = when (binding.roleToggleGroup.checkedButtonId) {
                R.id.btnRoleGuru -> "Guru"
                R.id.btnRoleOrtu -> "Orang Tua"
                else -> "Siswa"
            }
            val fcmToken = FcmTokenHelper.latestToken
            val deviceType = "Android ${Build.BRAND} ${Build.MODEL}"

            if (tenantCode.isBlank() || registeredNumber.isBlank() || password.isBlank()) {
                showToast("Masukan ID dan Password")
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
                password,
                role,
                fcmToken,
                deviceType,
                object : LoginCallback {
                    override fun onLoading(isLoading: Boolean) {
                        setButtonLoading(isLoading)
                    }

                    override fun onError(message: String) {
                        showToast(message)
                    }

                    override fun onSuccess() {
                        // SIMPAN DATA LOGIN AGAR BISA LOGIN OTOMATIS BERIKUTNYA
                        PreferenceHelper.saveLoginData(
                            this@LoginActivity,
                            tenantCode,
                            registeredNumber,
                            password,
                            role
                        )

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
            text = if (isLoading) "Mohon Tunggu..." else "Masuk"
        }
        binding.inputRegisteredNumber.isEnabled = !isLoading
        binding.inputPassword.isEnabled = !isLoading
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}