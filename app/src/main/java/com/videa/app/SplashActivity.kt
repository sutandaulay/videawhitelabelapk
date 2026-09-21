package com.videa.app

import android.Manifest
import com.videa.app.R
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executor

class SplashActivity : AppCompatActivity() {

    private val REQUEST_PERMISSIONS_CODE = 1001
    private val MIN_SPLASH_TIME = 15000L
    private var startTime = 0L
    private var isNavigated = false
    private var countdownValue = (MIN_SPLASH_TIME / 1000).toInt()
    private val countdownHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTime = System.currentTimeMillis()

        setContentView(R.layout.activity_splash)
        supportActionBar?.hide()

        FcmTokenHelper.getToken()

        playLuxuryAnimations()
        setupButtons()

        if (checkAndRequestPermissions()) {
            startNavigationTimer()
            startCountdown()
        }
    }

    private fun startCountdown() {
        val tvTimer = findViewById<TextView>(R.id.tv_timer)
        tvTimer.visibility = View.VISIBLE
        val runnable = object : Runnable {
            override fun run() {
                if (isNavigated) return
                if (countdownValue >= 0) {
                    tvTimer.text = getString(R.string.auto_login_timer, countdownValue)
                    countdownValue--
                    countdownHandler.postDelayed(this, 1000)
                }
            }
        }
        countdownHandler.post(runnable)
    }

    private fun setupButtons() {
        findViewById<View>(R.id.circle_accent).setOnClickListener {
            navigateToNext()
        }
        findViewById<View>(R.id.card_ppdb).setOnClickListener {
            navigateToPpdb()
        }
    }

    private fun navigateToPpdb() {
        if (isNavigated) return
        isNavigated = true
        val intent = Intent(this, MainActivity::class.java)
        
        // Menggunakan AppConfig untuk path PPDB
        val appUrl = AppConfig.BASE_URL.removeSuffix("/")
        val targetUrl = if (appUrl.endsWith("/app")) {
            appUrl.substringBeforeLast("/app") + AppConfig.PPDB_PATH
        } else {
            "$appUrl${AppConfig.PPDB_PATH}"
        }
        
        intent.putExtra("actionUrl", targetUrl)
        startActivity(intent)
        finish()
    }

    private fun startNavigationTimer() {
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToNext()
        }, MIN_SPLASH_TIME)
    }

    private fun navigateToNext() {
        if (isNavigated) return

        val tenant = PreferenceHelper.getTenantCode(this)
        val registered = PreferenceHelper.getRegisteredNumber(this)
        val password = PreferenceHelper.getPassword(this)
        val role = PreferenceHelper.getRole(this)

        if (tenant == null || registered == null || password == null || role == null) {
            isNavigated = true
            startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            finish()
            return
        }

        // --- KONSEP BIOMETRIK MODERN DENGAN FALLBACK PIN/POLA HP ---
        val biometricManager = BiometricManager.from(this)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        val canAuth = biometricManager.canAuthenticate(authenticators)
        Log.d("BiometricCheck", "Status canAuthenticate: $canAuth")

        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS && PreferenceHelper.isBiometricEnabled(this)) {
            // Kita set isNavigated = true agar timer tidak memicu navigasi ganda
            isNavigated = true
            showBiometricPrompt(tenant, registered, password, role) {
                // Beri jeda agar dialog biometrik benar-benar hilang dari UI
                Handler(Looper.getMainLooper()).postDelayed({
                    performAutoLogin(tenant, registered, password, role, ignoreFlag = true)
                }, 600)
            }
        } else {
            isNavigated = true
            performAutoLogin(tenant, registered, password, role, ignoreFlag = true)
        }
    }

    private fun showBiometricPrompt(tenant: String, registered: String, password: String, role: String, onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.e("BiometricCheck", "Error: $errorCode - $errString")
                
                // Jika dibatalkan user (Cancel / Negative Button / Back)
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || 
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == 13
                ) {
                    isNavigated = false // Izinkan navigasi ulang jika user ingin klik manual
                    showDisableBiometricDialog(tenant, registered, password, role)
                } else {
                    // Kesalahan sistem lain, paksa login manual
                    startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
                    finish()
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
        })

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verifikasi Identitas")
            .setSubtitle("Gunakan sidik jari atau kunci layar untuk masuk")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or 
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            promptInfoBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            promptInfoBuilder.setNegativeButtonText("Masuk dengan Password")
        }

        try {
            biometricPrompt.authenticate(promptInfoBuilder.build())
        } catch (e: Exception) {
            Log.e("BiometricCheck", "Auth Exception: ${e.message}")
            isNavigated = true
            performAutoLogin(tenant, registered, password, role, ignoreFlag = true)
        }
    }

    private fun showDisableBiometricDialog(tenant: String, reg: String, pass: String, role: String) {
        AlertDialog.Builder(this)
            .setTitle("Opsi Login")
            .setMessage("Apakah Anda ingin mematikan verifikasi sidik jari untuk login berikutnya?")
            .setPositiveButton("Ya, Matikan") { _, _ ->
                PreferenceHelper.setBiometricEnabled(this, false)
                performAutoLogin(tenant, reg, pass, role, ignoreFlag = true)
            }
            .setNegativeButton("Tetap Gunakan") { _, _ ->
                isNavigated = true
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun performAutoLogin(tenant: String, registered: String, password: String, role: String, ignoreFlag: Boolean = false) {
        waitForFcmToken { token ->
            // Jika sudah navigasi dan bukan paksaan (ignoreFlag), hentikan.
            if (isNavigated && !ignoreFlag) return@waitForFcmToken
            isNavigated = true

            if (token != null) {
                val deviceType = "Android ${Build.BRAND} ${Build.MODEL}"
                LoginHelper.onLogin(
                    this, tenant, registered, password, role, token, deviceType,
                    object : LoginHelper.LoginCallback {
                        override fun onLoading(isLoading: Boolean) {}
                        override fun onError(message: String) {
                            // MODE BANDEL: Jangan lempar ke Login manual jika hanya karena internet/server eror.
                            // Biarkan tetap masuk ke MainActivity, script JS akan mencoba login di WebView.
                            Log.w("Splash", "onLogin Error: $message. Melanjutkan ke MainActivity...")
                            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                            finish()
                        }
                        override fun onSuccess() {
                            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                            finish()
                        }
                    }
                )
            } else {
                // Token tidak didapat pun tetap masuk, script JS akan menangani form login nanti.
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun playLuxuryAnimations() {
        val schoolContainer = findViewById<View>(R.id.school_branding_container)
        val textContainer = findViewById<View>(R.id.text_container)
        val actionPanel = findViewById<View>(R.id.action_panel)
        val vendorContainer = findViewById<View>(R.id.vendor_branding_container)
        
        val logoCard = findViewById<View>(R.id.logo_card)
        val textHeading = findViewById<View>(R.id.text_heading)
        val textSub = findViewById<View>(R.id.text_subheading)
        val divider = findViewById<View>(R.id.slogan_divider)
        val cardPpdb = findViewById<View>(R.id.card_ppdb)
        val ppdbIconContainer = findViewById<View>(R.id.ppdb_icon_container)
        val btnLogin = findViewById<View>(R.id.circle_accent)

        // Initial States
        schoolContainer.alpha = 0f
        textContainer.alpha = 0f
        actionPanel.alpha = 0f
        vendorContainer.alpha = 0f
        
        logoCard.scaleX = 0f
        logoCard.scaleY = 0f
        
        textHeading.translationX = -200f
        textSub.alpha = 0f
        divider.scaleX = 0f
        
        cardPpdb.scaleX = 0.5f
        cardPpdb.scaleY = 0.5f
        ppdbIconContainer.rotation = -180f
        
        btnLogin.translationY = 50f

        // 1. Entrance of Branding
        val brandingAnim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(schoolContainer, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(logoCard, View.SCALE_X, 0f, 1f),
                ObjectAnimator.ofFloat(logoCard, View.SCALE_Y, 0f, 1f),
                ObjectAnimator.ofFloat(logoCard, View.ROTATION, -45f, 0f)
            )
            duration = 1200
            interpolator = OvershootInterpolator(1.5f)
        }

        // 2. Slogan Morphing/Entry
        val sloganAnim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(textContainer, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(textHeading, View.TRANSLATION_X, -200f, 0f),
                // Letter Spacing Morph: Dari renggang ke rapat mewah
                ObjectAnimator.ofFloat(textHeading as TextView, "letterSpacing", 0.4f, 0.05f),
                ObjectAnimator.ofFloat(divider, View.SCALE_X, 0f, 1f),
                ObjectAnimator.ofFloat(textSub, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(textSub, View.TRANSLATION_Y, 20f, 0f)
            )
            duration = 1500
            startDelay = 600
            interpolator = DecelerateInterpolator()
        }

        // 3. Action Panel Entrance (Morphing from bottom)
        val actionAnim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(actionPanel, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(cardPpdb, View.SCALE_X, 0.5f, 1f),
                ObjectAnimator.ofFloat(cardPpdb, View.SCALE_Y, 0.5f, 1f),
                ObjectAnimator.ofFloat(ppdbIconContainer, View.ROTATION, -180f, 0f),
                // Arrow Entrance: Slide dari kiri ke posisi
                ObjectAnimator.ofFloat(findViewById<View>(R.id.arrow_ppdb), View.TRANSLATION_X, -50f, 0f),
                ObjectAnimator.ofFloat(btnLogin, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(btnLogin, View.TRANSLATION_Y, 100f, 0f),
                // Corner Radius Morph
                ObjectAnimator.ofInt(btnLogin as MaterialButton, "cornerRadius", 100, 24)
            )
            duration = 1500
            startDelay = 1400
            interpolator = OvershootInterpolator(1.0f)
        }

        // 4. Footer
        val footerAnim = ObjectAnimator.ofFloat(vendorContainer, View.ALPHA, 0f, 1f).apply {
            duration = 1000
            startDelay = 1800
        }

        AnimatorSet().apply {
            playTogether(brandingAnim, sloganAnim, actionAnim, footerAnim)
            start()
        }

        // Pulsing & Pointing Animations
        startPulsing(logoCard, 1.05f)
        startPulsing(findViewById(R.id.school_name), 1.03f)
        startPulsing(cardPpdb, 1.02f)
        startPointing(findViewById(R.id.arrow_ppdb))
        startFading(findViewById(R.id.text_subheading))
    }

    private fun startFading(view: View) {
        ObjectAnimator.ofFloat(view, View.ALPHA, 0.3f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun startPulsing(view: View, scale: Float) {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, scale)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, scale)
        
        ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun startPointing(view: View) {
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, 15f).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun waitForFcmToken(onReady: (String?) -> Unit) {
        var retries = 0
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val token = FcmTokenHelper.latestToken
                // Cek token setiap 200ms, maksimal 30 kali (total 6 detik)
                if (token != null || retries >= 30) {
                    onReady(token)
                    return
                }
                retries++
                handler.postDelayed(this, 200)
            }
        }
        handler.post(runnable)
    }

    private fun checkAndRequestPermissions(): Boolean {
        val needed = mutableListOf<String>()
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ).forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) needed.add(it)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS_CODE)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            startNavigationTimer()
            startCountdown()
        }
    }
}