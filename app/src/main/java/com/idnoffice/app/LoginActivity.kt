package com.idnoffice.app

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.idnoffice.app.databinding.ActivityLoginBinding
import coil.load
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {
            val tenantCode = binding.inputTenantCode.text.toString()
            val registeredNumber = binding.inputRegisteredNumber.text.toString()
            val password = binding.inputPassword.text.toString()

            if (tenantCode.isBlank() || registeredNumber.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Lengkapi semua input", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnLogin.isEnabled = false
            binding.btnLogin.text = "Loading..."

            // onLogin(tenantCode, registeredNumber, password)
            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
            finish()
        }

        binding.logoImage.load("https://smpinovasidalamnegeri.videaclass.com/favicon/300.png") {
            crossfade(true)
        }

        // ANIMASI LOGIN CARD
        binding.loginCard.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.fade_in)
        )
        binding.loginCard.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.slide_up)
        )
    }

    private fun onLogin(tenantCode: String, registeredNumber: String, password: String) {

        val client = OkHttpClient()

        val json = """
        {
            "tenant_code": "$tenantCode",
            "registered_number": "$registeredNumber",
            "password": "$password"
        }
    """.trimIndent()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://domain.com/api/login")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = "Login"
                    Toast.makeText(this@LoginActivity, "Gagal terhubung ke server", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()

                runOnUiThread {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = "Login"

                    if (!response.isSuccessful) {
                        Toast.makeText(this@LoginActivity, "Login gagal", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        })
    }

}