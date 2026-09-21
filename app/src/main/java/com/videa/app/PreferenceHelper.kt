package com.videa.app

import android.content.Context
import android.content.SharedPreferences

object PreferenceHelper {

    private const val PREF_NAME = "user_pref"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun saveLoginData(context: Context, tenantCode: String?, registeredNumber: String?, password: String?, role: String?) {
        val editor = prefs(context).edit()
        editor.putString("tenant_code", tenantCode)
        editor.putString("registered_number", registeredNumber)
        editor.putString("password", password)
        editor.putString("role", role)
        editor.apply()
    }

    @JvmStatic
    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("biometric_enabled", enabled).apply()
    }

    @JvmStatic
    fun isBiometricEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("biometric_enabled", true)
    }

    @JvmStatic
    fun getRole(context: Context): String? {
        return prefs(context).getString("role", "Siswa")
    }

    @JvmStatic
    fun getTenantCode(context: Context): String? {
        return prefs(context).getString("tenant_code", null)
    }

    @JvmStatic
    fun getRegisteredNumber(context: Context): String? {
        return prefs(context).getString("registered_number", null)
    }

    @JvmStatic
    fun getPassword(context: Context): String? {
        return prefs(context).getString("password", null)
    }

    @JvmStatic
    fun saveExitPin(context: Context, pin: String) {
        prefs(context).edit().putString("exam_exit_pin", pin).apply()
    }

    @JvmStatic
    fun getExitPin(context: Context): String {
        return prefs(context).getString("exam_exit_pin", "654321123456") ?: "654321123456"
    }

    @JvmStatic
    fun clear(context: Context) {
        val editor = prefs(context).edit()
        editor.clear()
        editor.apply()
    }
}