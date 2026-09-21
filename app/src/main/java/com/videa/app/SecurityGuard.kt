package com.videa.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import java.io.File
import java.net.NetworkInterface

class SecurityGuard(private val context: Context) {

    companion object {
        private const val TAG = "SecurityGuard"
        
        private val TRUSTED_SYSTEM_PREFIXES = listOf(
            "com.android.", "com.google.android.", "android", "com.transsion.",
            "com.mediatek.", "com.qualcomm.", "com.miui.", "com.xiaomi.",
            "com.samsung.", "com.sec.", "com.oppo.", "com.coloros.",
            "com.vivo.", "com.bbk.", "com.oplus.", "com.heytap.",
            "com.huawei.", "com.hihonor.", "com.realme.", "com.infinix.",
            "com.tecno.", "com.itel.", "com.asus.", "com.lenovo.", "com.motorola."
        )

        private val TRUSTED_ACCESSIBILITY_KEYWORDS = listOf(
            "talkback", "switchaccess", "soundamplifier", "accessibilitymenu",
            "selecttospeak", "voiceaccess", "livetranscribe"
        )

        private val KNOWN_ROOT_APPS = listOf(
            "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.noshufou.android.su", "com.thirdparty.superuser", "com.kingroot.kinguser",
            "com.kingo.root"
        )

        private val ROOT_BINARY_PATHS = arrayOf(
            "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/su",
            "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/xbin/su", "/su/bin/su"
        )

        private val SUSPICIOUS_VIRTUALIZATION_MARKERS = listOf(
            "com.lbe.parallel", "com.excelliance.dualaid", "com.microvirt", "vmos"
        )
    }

    fun getMyKeyboardId(): String {
        val className = "com.videa.keyboard.ExamKeyboardService"
        return "${context.packageName}/$className"
    }

    private fun getCurrentInputMethodId(): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    fun isActuallyRooted(): Boolean {
        val suExecutable = try {
            ROOT_BINARY_PATHS.any { path ->
                val f = File(path)
                f.exists() && f.canExecute()
            }
        } catch (e: Exception) { false }

        val rootAppInstalled = try {
            KNOWN_ROOT_APPS.any { pkg ->
                try {
                    context.packageManager.getPackageInfo(pkg, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) { false }
            }
        } catch (e: Exception) { false }

        return suExecutable || rootAppInstalled
    }

    private fun isTrustedSystemPackage(pkgInfo: PackageInfo): Boolean {
        val appInfo = pkgInfo.applicationInfo ?: return false
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem) return true
        return TRUSTED_SYSTEM_PREFIXES.any { pkgInfo.packageName.startsWith(it) }
    }

    fun checkVpn(): Boolean {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            if (networkInterfaces != null) {
                for (intf in networkInterfaces) {
                    if (!intf.isUp) continue
                    val name = intf.name.lowercase()
                    if (name.contains("tun") || name.contains("ppp") || name.contains("pptp")) {
                        return intf.interfaceAddresses.any { ia ->
                            val addr = ia.address
                            !addr.isLoopbackAddress && (addr.isSiteLocalAddress || addr.hostAddress?.startsWith("10.") == true)
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "VPN Check Error", e) }
        return false
    }

    fun checkAccessibility(packageName: String): List<String> {
        val violations = mutableListOf<String>()
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.forEach { s ->
                val pn = s.resolveInfo.serviceInfo.packageName
                if (pn == packageName) return@forEach
                if (TRUSTED_ACCESSIBILITY_KEYWORDS.any { pn.contains(it, ignoreCase = true) }) return@forEach
                
                val ownerPkgInfo = try { context.packageManager.getPackageInfo(pn, PackageManager.GET_META_DATA) } catch (e: Exception) { null }
                if (ownerPkgInfo != null && isTrustedSystemPackage(ownerPkgInfo)) return@forEach

                val label = try { context.packageManager.getApplicationLabel(s.resolveInfo.serviceInfo.applicationInfo).toString() } catch (e: Exception) { pn }
                violations.add("❌ LAYANAN AKSESIBILITAS AKTIF ($label)")
            }
        } catch (e: Exception) { Log.w(TAG, "Accessibility Check Error", e) }
        return violations
    }

    fun checkOverlays(packageName: String): List<String> {
        val violations = mutableListOf<String>()
        try {
            val appOpsMgr = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val packages = context.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            var foundCount = 0
            for (pkgInfo in packages) {
                val pkgName = pkgInfo.packageName
                if (pkgName == packageName) continue
                if (isTrustedSystemPackage(pkgInfo)) continue
                if (pkgInfo.requestedPermissions?.contains(Manifest.permission.SYSTEM_ALERT_WINDOW) != true) continue

                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOpsMgr.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, pkgInfo.applicationInfo?.uid ?: -1, pkgName)
                } else {
                    @Suppress("DEPRECATION")
                    appOpsMgr.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, pkgInfo.applicationInfo?.uid ?: -1, pkgName)
                }

                if (mode == AppOpsManager.MODE_ALLOWED) {
                    val label = try { context.packageManager.getApplicationLabel(pkgInfo.applicationInfo!!).toString() } catch (e: Exception) { pkgName }
                    violations.add("❌ APLIKASI OVERLAY AKTIF ($label)")
                    foundCount++
                    if (foundCount >= 3) break
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Overlay Check Error", e) }
        return violations
    }

    fun checkVirtualization(filesPath: String): Boolean {
        return SUSPICIOUS_VIRTUALIZATION_MARKERS.any { filesPath.contains(it, ignoreCase = true) }
    }

    fun isKeyboardEnabled(): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val list = imm.enabledInputMethodList
        val myId = getMyKeyboardId()
        return list.any { it.id == myId }
    }

    fun checkKeyboardViolation(): String? {
        val currentIme = getCurrentInputMethodId()
        val myId = getMyKeyboardId()
        
        Log.d(TAG, "Keyboard Check - Aktif: $currentIme | Target: $myId")
        
        if (currentIme != null && currentIme != myId) {
            return "❌ KEYBOARD TIDAK DIIZINKAN — Ganti ke Keyboard Videa sebelum melanjutkan"
        }
        return null
    }
}