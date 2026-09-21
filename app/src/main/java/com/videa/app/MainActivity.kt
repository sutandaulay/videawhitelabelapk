package com.videa.app

import android.Manifest
import android.R
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.LinearInterpolator
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.os.Process
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.videa.keyboard.ExamKeyboardService
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var webViewProgress: LinearProgressIndicator
    private lateinit var layoutOffline: View
    private lateinit var btnRetry: Button
    private lateinit var btnSelesai: Button
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var securityGuard: SecurityGuard
    private lateinit var videaWebManager: VideaWebManager
    private lateinit var examManager: ExamManager

    private val isExamMode: Boolean get() = if (::examManager.isInitialized) examManager.isExamActive() else false
    private val isPinned: Boolean get() = if (::examManager.isInitialized) examManager.isPinned() else false

    private lateinit var clipboardManager: ClipboardManager
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isExamMode) {
            clearClipboard()
        }
    }

    private lateinit var layoutSecurityWarning: LinearLayout
    private lateinit var tvSecurityStatus: TextView
    private lateinit var tvSecurityHint: TextView
    private lateinit var tvSecurityDesc: TextView
    private lateinit var pbScanning: ProgressBar
    private lateinit var ivSecurityAlert: ImageView
    private lateinit var svViolations: ScrollView
    private lateinit var btnOpenSettings: Button
    private lateinit var btnStartExam: Button
    private var currentSecurityAction: String? = null
    private var manualViolation: String? = null
    private var lastOverlayObscuredAt: Long = 0L

    private val REQUEST_PERMISSIONS_CODE = 1001

    private val updateFlowLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                Log.w(TAG, "Force update dibatalkan/gagal (resultCode=${result.resultCode}) -- memaksa cek ulang")
                checkForUpdate()
            }
        }

    // ====== File chooser (upload) untuk WebView ======
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uris: Array<Uri>? = if (result.resultCode == RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            } else {
                null
            }
            fileChooserCallback?.onReceiveValue(uris)
            fileChooserCallback = null
            invalidatePendingExamChecks()
        }

    companion object {
        private const val TAG = "ExamMode"

        private const val EXAM_PAUSE_GRACE_MS = 900L
        private const val EXAM_VIOLATION_GRACE_MS = 900L
        private const val INTERNAL_ACTION_SUPPRESS_MS = 3000L

        private const val LOCK_TASK_ACTIVATION_DELAY_MS = 500L
        private const val SECURITY_POLL_INTERVAL_MS = 3000L
        private const val GATE_POLL_INTERVAL_MS = 1200L

        private const val FILE_UPLOAD_PERMISSIONS_CODE = 1002
        private const val EXAM_UPLOAD_SUPPRESS_MS = 90_000L
        private const val OVERLAY_VIOLATION_TTL_MS = 2500L
        private const val EXAM_PIN_TOAST_GRACE_MS = 5000L

        private const val HEAVY_SCAN_MIN_INTERVAL_GATE_MS = 1500L
        private const val HEAVY_SCAN_MIN_INTERVAL_EXAM_MS = 15000L
    }

    private fun getMyKeyboardId(): String {
        return securityGuard.getMyKeyboardId()
    }

    var baseUrl = AppConfig.BASE_URL
    private val pathMilitary = AppConfig.MILITARY_ZONES
    private val pathAssessment = AppConfig.ASSESSMENT_ZONES

    private enum class ProtectedZone { NONE, EXAM, ASSESSMENT }
    private enum class SecurityState { IDLE, GATE_PENDING, ASSESSMENT_ACTIVE, HANDLING_CHEAT }
    private var securityState: SecurityState = SecurityState.IDLE

    private var isAssessmentMode = false
        private set
    private var isScreeningPassed = false

    private var isPendingGate = false
    private var pendingGateZone: ProtectedZone = ProtectedZone.NONE
    private var pendingGateUrl: String? = null
    private var lastKnownAssessmentUrl: String? = null

    private var isHandlingSecurityEvent = false

    private val heavyScanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ExamHeavyScan").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }
    @Volatile private var cachedHeavyViolations: List<String> = emptyList()
    @Volatile private var lastHeavyScanAt: Long = 0L
    @Volatile private var heavyScanInProgress: Boolean = false

    private lateinit var audioManager: AudioManager
    private var isAlarmPlaying = false
    private var mediaPlayer: MediaPlayer? = null
    private var isDialogOpen = false

    private val focusSuppressToken = AtomicInteger(0)
    private var focusCheckSuppressed = false
    private var examCheckGeneration = 0

    private val securityHandler = Handler(Looper.getMainLooper())
    private val securityRunnable = object : Runnable {
        override fun run() {
            if (!isHandlingSecurityEvent) {
                if (isPendingGate) {
                    pollPendingGate()
                } else if (isExamMode) {
                    // Volume is now handled by ExamManager
                    val violations = getViolationsList()
                    if (violations.isNotEmpty()) {
                        reportExamViolation("Keamanan Terganggu: ${violations.first()}")
                    }
                } else if (isAssessmentMode && isScreeningPassed) {
                    val violations = getViolationsList()
                    if (violations.isNotEmpty()) {
                        openGate(webView.url?.takeIf { it != "about:blank" } ?: lastKnownAssessmentUrl, ProtectedZone.ASSESSMENT, violations)
                    }
                }
            }
            val nextDelay = if (isPendingGate) GATE_POLL_INTERVAL_MS else SECURITY_POLL_INTERVAL_MS
            securityHandler.postDelayed(this, nextDelay)
        }
    }

    private fun suppressFocusCheck(durationMs: Long = INTERNAL_ACTION_SUPPRESS_MS) {
        val myToken = focusSuppressToken.incrementAndGet()
        focusCheckSuppressed = true
        securityHandler.postDelayed({
            if (focusSuppressToken.get() == myToken) focusCheckSuppressed = false
        }, durationMs)
    }

    private fun invalidatePendingExamChecks() {
        examCheckGeneration++
    }



    private fun isFullyClearedForContent(): Boolean = isExamMode || (isAssessmentMode && isScreeningPassed)

    private fun isWithinPinToastGrace(): Boolean {
        val activatedAt = examManager.getActivatedAt()
        if (activatedAt == 0L) return false
        return isExamMode && (System.currentTimeMillis() - activatedAt) <= EXAM_PIN_TOAST_GRACE_MS
    }

    private fun reportExamViolation(reason: String) {
        if (!isExamMode || isHandlingSecurityEvent || focusCheckSuppressed) return
        val myGen = ++examCheckGeneration
        securityHandler.postDelayed({
            if (myGen != examCheckGeneration) return@postDelayed
            if (!isExamMode || isHandlingSecurityEvent || focusCheckSuppressed) return@postDelayed

            val violations = getViolationsList()
            val displayViolations = if (violations.isEmpty()) listOf("❌ $reason") else violations

            openGate(
                webView.url?.takeIf { it != "about:blank" } ?: lastKnownAssessmentUrl,
                ProtectedZone.EXAM,
                displayViolations
            )
        }, EXAM_VIOLATION_GRACE_MS)
    }

    private fun reportBackgroundOrFocusEvent(reason: String) {
        if (!isFullyClearedForContent() || isHandlingSecurityEvent || focusCheckSuppressed || isWithinPinToastGrace()) return
        val myGen = ++examCheckGeneration
        securityHandler.postDelayed({
            if (myGen != examCheckGeneration) return@postDelayed
            if (!isFullyClearedForContent() || isHandlingSecurityEvent || focusCheckSuppressed || isWithinPinToastGrace()) return@postDelayed
            handleCheatingDetected(reason)
        }, EXAM_PAUSE_GRACE_MS)
    }

    private fun enterProtectedZone(url: String, zone: ProtectedZone) {
        if (zone == ProtectedZone.EXAM && isExamMode) return
        if (zone == ProtectedZone.ASSESSMENT && isAssessmentMode && isScreeningPassed) return
        if (isPendingGate) return

        if (isExamMode && zone == ProtectedZone.ASSESSMENT) examManager.deactivate()

        if (zone == ProtectedZone.EXAM) {
            // Halaman /room boleh rotasi bebas (portrait & landscape) mengikuti sensor perangkat.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

        openGate(url, zone, getViolationsList(forceSyncHeavy = true))
    }

    private fun openGate(url: String?, zone: ProtectedZone, violations: List<String>) {
        Log.d(TAG, "openGate: zone=$zone url=$url violations=${violations.size}")
        pendingGateUrl = url
        pendingGateZone = zone
        isPendingGate = true
        isScreeningPassed = false
        if (zone == ProtectedZone.ASSESSMENT) isAssessmentMode = true
        securityState = SecurityState.GATE_PENDING

        runOnUiThread {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.visibility = View.GONE

            if (violations.isNotEmpty()) {
                showViolationScreen(violations)
            } else {
                showCleanGateScreen()
            }
        }
    }

    private fun pollPendingGate() {
        val violations = getViolationsList()

        runOnUiThread {
            if (violations.isNotEmpty()) {
                showViolationScreen(violations)
            } else {
                showCleanGateScreen()
            }
        }
    }

    private fun showCleanGateScreen() {
        pbScanning.visibility = View.GONE
        ivSecurityAlert.visibility = View.GONE
        svViolations.visibility = View.GONE
        btnOpenSettings.visibility = View.GONE

        tvSecurityStatus.text = "Pemeriksaan Perangkat!"
        tvSecurityHint.text = "Memastikan keamanan perangkat lulus. Silakan ketuk tombol di bawah untuk mulai masuk ke ujian."
        tvSecurityDesc.text = ""

        btnStartExam.text = "Masuk ke Ujian"
        btnStartExam.isEnabled = true
        btnStartExam.alpha = 1.0f
        btnStartExam.visibility = View.VISIBLE

        layoutSecurityWarning.visibility = View.VISIBLE
        layoutSecurityWarning.bringToFront()
    }

    private fun showViolationScreen(violations: List<String>) {
        pbScanning.visibility = View.GONE
        ivSecurityAlert.visibility = View.VISIBLE
        tvSecurityStatus.text = "Pemeriksaan Keamanan"

        if (violations.any { it.contains("KEYBOARD TIDAK DIIZINKAN") }) {
            tvSecurityHint.text = "Aktifkan & pilih 'Keyboard Videa' sebagai keyboard aktif, lalu ketuk 'Cek Ulang Sekarang'."
        } else {
            tvSecurityHint.text = "Perangkat terdeteksi melanggar. Tutup atau jangan izinkan aplikasi tersebut berjalan, Silahkan Perbaiki ! lalu ketuk 'Cek Ulang Sekarang'."
        }

        if (violations.any { it.contains("PERANGKAT ROOT") }) {
            tvSecurityHint.text = "Perangkat terdeteksi root. Ujian tidak dapat dijalankan di perangkat ini."
        }

        currentSecurityAction = when {
            violations.any { it.contains("KEYBOARD TIDAK DIIZINKAN") } ->
                Settings.ACTION_INPUT_METHOD_SETTINGS
            violations.any { it.contains("MENGAMBANG") } -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            violations.any { it.contains("AKSESIBILITAS") } -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            violations.any { it.contains("VPN") } -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Settings.ACTION_VPN_SETTINGS
                else Settings.ACTION_WIRELESS_SETTINGS
            }
            violations.any { it.contains("WAKTU OTOMATIS") } -> Settings.ACTION_DATE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        svViolations.visibility = View.VISIBLE
        tvSecurityDesc.text = violations.joinToString("\n\n")
        btnOpenSettings.visibility = View.VISIBLE

        btnStartExam.text = "Cek Ulang Sekarang"
        btnStartExam.isEnabled = true
        btnStartExam.alpha = 1.0f
        btnStartExam.visibility = View.VISIBLE

        layoutSecurityWarning.visibility = View.VISIBLE
        layoutSecurityWarning.bringToFront()
    }

    private fun commitEntry(url: String, zone: ProtectedZone) {
        isPendingGate = false
        pendingGateUrl = null
        manualViolation = null

        runOnUiThread {
            layoutSecurityWarning.visibility = View.GONE
            webView.visibility = View.VISIBLE
        }

        when (zone) {
            ProtectedZone.EXAM -> {
                swipeRefreshLayout.isEnabled = false
                examManager.activate()
                webView.loadUrl(url)
            }
            ProtectedZone.ASSESSMENT -> {
                swipeRefreshLayout.isEnabled = true
                isScreeningPassed = true
                isAssessmentMode = true
                lastKnownAssessmentUrl = url
                securityState = SecurityState.ASSESSMENT_ACTIVE
                runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                webView.loadUrl(url)
            }
            ProtectedZone.NONE -> {
                swipeRefreshLayout.isEnabled = true
                webView.loadUrl(url)
            }
        }
    }

    private fun exitProtectedZone() {
        if (isExamMode) examManager.deactivate()
        isAssessmentMode = false
        isScreeningPassed = false
        isPendingGate = false
        pendingGateUrl = null
        manualViolation = null
        securityState = SecurityState.IDLE
        runOnUiThread {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            layoutSecurityWarning.visibility = View.GONE
            btnStartExam.visibility = View.GONE
            swipeRefreshLayout.isEnabled = true
        }
        // Kembali ke portrait untuk halaman selain /room
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun hideSystemUI() {
        // Biarkan system UI selalu muncul (tidak disembunyikan secara paksa)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Biarkan sistem mengatur pas layar dan system bars (status bar & navigation bar tetap tampil)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        supportActionBar?.hide()
        setContentView(com.videa.app.R.layout.activity_main)

        securityGuard = SecurityGuard(this)

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        swipeRefreshLayout = findViewById(com.videa.app.R.id.swipeRefreshLayout)
        webView = findViewById(com.videa.app.R.id.webView)

        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        webViewProgress = findViewById(com.videa.app.R.id.webViewProgress)

        layoutOffline = findViewById(com.videa.app.R.id.layoutOffline)
        btnRetry = findViewById(com.videa.app.R.id.btnRetry)

        btnRetry.setOnClickListener {
            if (currentlyHasInternet()) {
                layoutOffline.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.reload()
            } else {
                Toast.makeText(this, "Internet belum tersedia", Toast.LENGTH_SHORT).show()
            }
        }

        layoutSecurityWarning = findViewById(com.videa.app.R.id.layoutSecurityWarning)
        tvSecurityStatus = findViewById(com.videa.app.R.id.tvSecurityStatus)
        tvSecurityHint = findViewById(com.videa.app.R.id.tvSecurityHint)
        tvSecurityDesc = findViewById(com.videa.app.R.id.tvSecurityDesc)
        pbScanning = findViewById(com.videa.app.R.id.pbScanning)
        ivSecurityAlert = findViewById(com.videa.app.R.id.ivSecurityAlert)
        svViolations = findViewById(com.videa.app.R.id.svViolations)
        btnOpenSettings = findViewById(com.videa.app.R.id.btnOpenSettings)
        btnStartExam = findViewById(com.videa.app.R.id.btnStartExam)

        btnOpenSettings.setOnClickListener {
            val violations = getViolationsList()
            if (violations.any { it.contains("KEYBOARD TIDAK DIIZINKAN") }) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

                if (securityGuard.isKeyboardEnabled()) {
                    // Jika sudah centang ON tapi belum dipilih, langsung munculkan picker
                    imm.showInputMethodPicker()
                } else {
                    // Jika belum centang ON, arahkan ke pengaturan aktivasi
                    try {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        Toast.makeText(this, "Centang 'Keyboard Videa', lalu kembali ke sini.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        imm.showInputMethodPicker()
                    }
                }
            } else {
                currentSecurityAction?.let { action ->
                    try {
                        startActivity(Intent(action))
                    } catch (e: Exception) {
                        Log.w(TAG, "Gagal membuka settings action: $action", e)
                    }
                } ?: startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        btnStartExam.setOnClickListener {
            if (isPendingGate) {
                val violations = getViolationsList(forceSyncHeavy = true)
                if (violations.isNotEmpty()) {
                    // Jika satu-satunya pelanggaran adalah keyboard, bantu munculkan picker-nya
                    if (violations.size == 1 && violations[0].contains("KEYBOARD")) {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    }
                    Toast.makeText(this, "Perangkat belum bersih. Periksa kembali.", Toast.LENGTH_SHORT).show()
                    showViolationScreen(violations)
                } else {
                    val url = pendingGateUrl
                    val zone = pendingGateZone
                    if (url != null) {
                        commitEntry(url, zone)
                    } else {
                        isPendingGate = false
                        layoutSecurityWarning.visibility = View.GONE
                        webView.visibility = View.VISIBLE
                        webView.loadUrl(baseUrl)
                    }
                }
            }
        }

        setupSwipeRefresh()

        videaWebManager = VideaWebManager(this, webView, webViewProgress, layoutOffline, object : VideaWebManager.WebEventListener {
            override fun onUrlChanged(url: String) {
                handleUrlChange(url)
            }

            override fun onExamPinTriggered() {
                requestExamScreening()
            }

            override fun onFileChooseRequest(filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?) {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                suppressFocusCheck(EXAM_UPLOAD_SUPPRESS_MS)
                invalidatePendingExamChecks()

                val intent = try {
                    fileChooserParams?.createIntent()
                } catch (e: Exception) {
                    null
                } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal membuka file chooser", e)
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                }
            }

            override fun onPermissionRequested(request: PermissionRequest) {
                request.grant(request.resources)
            }
        })
        videaWebManager.setup()

        examManager = ExamManager(this, findViewById(com.videa.app.R.id.examHeaderContainer), object : ExamManager.ExamListener {
            override fun onExamActivated() {
                // Clipboard listener is already handled in Activity
            }
            override fun onExamDeactivated() {
            }
            override fun onExamError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    webView.visibility = View.VISIBLE
                    webView.loadUrl(baseUrl)
                }
            }
            override fun onRefreshRequested() {
                if (!isPendingGate) {
                    Toast.makeText(this@MainActivity, "Memuat ulang halaman...", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
            }
            override fun onExitRequested() {
                showExitConfirmationDialog()
            }
        })

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        checkAndRequestPermissions()
        webView.loadUrl(resolveLaunchUrl(intent))

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForUpdate()

        securityHandler.post(securityRunnable)
    }

    private fun resolveLaunchUrl(source: Intent?): String {
        val actionUrl = source?.getStringExtra("actionUrl")
        source?.removeExtra("actionUrl")
        return if (!actionUrl.isNullOrBlank() &&
            (actionUrl.startsWith("http://") || actionUrl.startsWith("https://"))
        ) {
            Log.d(TAG, "Membuka actionUrl dari notifikasi: $actionUrl")
            actionUrl
        } else {
            baseUrl
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent: reset state security & reload baseUrl")
        exitProtectedZone()
        webView.visibility = View.VISIBLE
        webView.stopLoading()
        checkAndRequestPermissions()
        webView.loadUrl(resolveLaunchUrl(intent))
    }

    private fun getViolationsList(forceSyncHeavy: Boolean = false): List<String> {
        val list = mutableListOf<String>()

        if (manualViolation != null &&
            System.currentTimeMillis() - lastOverlayObscuredAt <= OVERLAY_VIOLATION_TTL_MS
        ) {
            list.add(manualViolation!!)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
            list.add("❌ MODE LAYAR TERPISAH AKTIF")
        }

        if (securityGuard.checkVpn()) {
            list.add("❌ VPN TERDETEKSI")
        }

        if (securityGuard.checkVirtualization(filesDir.path)) {
            list.add("❌ APLIKASI CLONE / VIRTUAL SPACE PIHAK KETIGA TERDETEKSI")
        }

        if (securityGuard.isActuallyRooted()) {
            list.add("❌ PERANGKAT ROOT")
        }

        securityGuard.checkKeyboardViolation()?.let { list.add(it) }

        if (forceSyncHeavy) {
            val heavy = computeHeavyViolations()
            cachedHeavyViolations = heavy
            lastHeavyScanAt = System.currentTimeMillis()
            list.addAll(heavy)
        } else {
            list.addAll(cachedHeavyViolations)
            val minInterval = if (isPendingGate) HEAVY_SCAN_MIN_INTERVAL_GATE_MS else HEAVY_SCAN_MIN_INTERVAL_EXAM_MS
            maybeTriggerHeavyScanAsync(minInterval)
        }

        return list
    }

    private fun computeHeavyViolations(): List<String> {
        val heavy = mutableListOf<String>()
        heavy.addAll(securityGuard.checkAccessibility(packageName))

        val skipOverlayScan = isExamMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (!skipOverlayScan) {
            heavy.addAll(securityGuard.checkOverlays(packageName))
        }

        return heavy
    }

    private fun maybeTriggerHeavyScanAsync(minIntervalMs: Long) {
        val now = System.currentTimeMillis()
        if (heavyScanInProgress) return
        if (now - lastHeavyScanAt < minIntervalMs) return

        heavyScanInProgress = true
        heavyScanExecutor.execute {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (e: Exception) {
            }
            val result = try {
                computeHeavyViolations()
            } catch (e: Exception) {
                Log.w(TAG, "Heavy scan gagal, pertahankan cache lama", e)
                cachedHeavyViolations
            }
            cachedHeavyViolations = result
            lastHeavyScanAt = System.currentTimeMillis()
            heavyScanInProgress = false
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isExamMode || isPinned) {
                Toast.makeText(this@MainActivity, "Ujian sedang berlangsung. Tombol Kembali dikunci!", Toast.LENGTH_SHORT).show()
                return
            }
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((isExamMode || isPinned) && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                Toast.makeText(this, "Ujian sedang berlangsung. Tombol Kembali dikunci!", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        if (isExamMode &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                    event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (isExamMode) reportExamViolation("Mode layar terpisah terdeteksi")
    }

    private fun checkForUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            val isUpdateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            val isUpdateStalled = info.updateAvailability() ==
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS

            if (isUpdateAvailable || isUpdateStalled) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        updateFlowLauncher,
                        AppUpdateOptions
                            .newBuilder(AppUpdateType.IMMEDIATE)
                            .build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal memulai force update flow", e)
                }
            }
        }.addOnFailureListener { e ->
            Log.w(TAG, "Gagal cek update dari Play Store", e)
        }
    }



    fun handleUrlChange(url: String?) {
        if (url == null || url == "about:blank") return
        val lowerUrl = url.lowercase()
        val isHome = lowerUrl.endsWith("/com.videa.app") || lowerUrl.endsWith("/com.videa.app/")
        val inMilitaryZone = pathMilitary.any { lowerUrl.contains(it) }
        val inAssessmentZone = !isHome && pathAssessment.any { lowerUrl.contains(it) }

        // Keamanan ujian (lockdown, alarm, dsb) HANYA berlaku untuk role Siswa.
        // Guru & Orang Tua boleh membuka halaman yang sama (mis. menu "P. Ujian")
        // tanpa dianggap masuk ruang ujian.
        val currentRole = PreferenceHelper.getRole(this)
        val isStudentRole = currentRole.equals("Siswa", ignoreCase = true)

        Log.d(TAG, "handleUrlChange: url=$url role=$currentRole inMilitaryZone=$inMilitaryZone inAssessmentZone=$inAssessmentZone")

        when {
            inMilitaryZone && isStudentRole -> {
                swipeRefreshLayout.isEnabled = false
                enterProtectedZone(url, ProtectedZone.EXAM)
            }
            inAssessmentZone && isStudentRole -> {
                swipeRefreshLayout.isEnabled = true
                enterProtectedZone(url, ProtectedZone.ASSESSMENT)
            }
            else -> {
                swipeRefreshLayout.isEnabled = true
                exitProtectedZone()
            }
        }
    }

    fun requestExamScreening() {
        if (isExamMode || isPinned) {
            Log.d(TAG, "requestExamScreening: sudah dalam exam mode, diabaikan")
            return
        }
        val url = webView.url?.takeIf { it != "about:blank" } ?: baseUrl
        Log.d(TAG, "requestExamScreening: membuka gerbang screening untuk $url")
        enterProtectedZone(url, ProtectedZone.EXAM)
    }

    private fun currentlyHasInternet(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }











    private fun showExitConfirmationDialog() {
        suppressFocusCheck(INTERNAL_ACTION_SUPPRESS_MS)
        invalidatePendingExamChecks()
        isDialogOpen = true
        AlertDialog.Builder(this)
            .setTitle("Konfirmasi")
            .setMessage("Yakin ingin keluar?")
            .setPositiveButton("Ya") { _, _ ->
                exitProtectedZone()
                examManager.deactivate()
                webView.visibility = View.VISIBLE
                webView.loadUrl(baseUrl)
                isDialogOpen = false
            }
            .setNegativeButton("Batal") { _, _ -> isDialogOpen = false }
            .setCancelable(false).show()
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener { webView.reload() }
        swipeRefreshLayout.setColorSchemeResources(R.color.holo_blue_bright, R.color.holo_green_light)
        swipeRefreshLayout.isEnabled = !isExamMode && !isDialogOpen
    }





    private fun handleCheatingDetected(reason: String) {
        if ((!isExamMode && !isAssessmentMode) || isHandlingSecurityEvent) return
        isHandlingSecurityEvent = true
        securityState = SecurityState.HANDLING_CHEAT
        suppressFocusCheck(INTERNAL_ACTION_SUPPRESS_MS)
        invalidatePendingExamChecks()

        if (isExamMode) {
            playAlarmSound()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (isExamMode) {
                Log.w(TAG, "Kecurangan terdeteksi di ruang ujian (/room): $reason. Membuang siswa ke beranda (/com.videa.app).")
                exitProtectedZone()
                examManager.deactivate()

                runOnUiThread {
                    Toast.makeText(this, "Pelanggaran terdeteksi! Anda dikeluarkan dari ruang ujian.", Toast.LENGTH_LONG).show()
                    webView.visibility = View.VISIBLE
                    webView.loadUrl(baseUrl)
                }
            } else {
                val violations = getViolationsList(forceSyncHeavy = true)
                val displayViolations = if (violations.isEmpty()) listOf("❌ $reason") else violations
                val currentUrl = webView.url?.takeIf { it != "about:blank" } ?: lastKnownAssessmentUrl
                openGate(currentUrl, ProtectedZone.ASSESSMENT, displayViolations)
            }

            isHandlingSecurityEvent = false
        }, INTERNAL_ACTION_SUPPRESS_MS)
    }

    private fun playAlarmSound() {
        if (isAlarmPlaying) return
        isAlarmPlaying = true
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, com.videa.app.R.raw.blocksound)
            mediaPlayer?.start()
            Handler(Looper.getMainLooper()).postDelayed({ mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null; isAlarmPlaying = false }, 3000)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memutar alarm", e)
            isAlarmPlaying = false
        }
    }

    override fun onPause() {
        super.onPause()
        reportBackgroundOrFocusEvent("Aplikasi beralih ke background")
    }

    override fun onResume() {
        super.onResume()
        invalidatePendingExamChecks()
        if (isPendingGate) pollPendingGate()

        if (::appUpdateManager.isInitialized) {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    appUpdateManager.completeUpdate()
                } else if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    checkForUpdate()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
            invalidatePendingExamChecks()
            return
        }
        reportBackgroundOrFocusEvent("Aplikasi kehilangan fokus")
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && (isPendingGate || isFullyClearedForContent())) {
            val isObscured = (ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
            val isPartiallyObscured = (ev.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0

            if (isObscured || isPartiallyObscured) {
                if (focusCheckSuppressed || isWithinPinToastGrace()) {
                    return super.dispatchTouchEvent(ev)
                }

                if (isExamMode) {
                    return super.dispatchTouchEvent(ev)
                }

                manualViolation = "❌ APLIKASI MENGAMBANG / OVERLAY TERDETEKSI"
                lastOverlayObscuredAt = System.currentTimeMillis()

                if (isFullyClearedForContent()) {
                    handleCheatingDetected("Aplikasi Mengambang / Overlay Layar Terdeteksi!")
                    return true
                }

                pollPendingGate()
            }
        }
        return super.dispatchTouchEvent(ev)
    }



    private fun clearClipboard() {
        try {
            // 1. Bersihkan dengan cara resmi (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboardManager.clearPrimaryClip()
            }

            // 2. Timpa dengan data kosong
            val clipData = ClipData.newPlainText("", "")

            // 3. Tandai sebagai data SENSITIF (API 24+) agar keyboard tidak menyimpan history
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val extras = PersistableBundle()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                } else {
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                clipData.description.extras = extras
            }

            clipboardManager.setPrimaryClip(clipData)

        } catch (e: Exception) {
            Log.w(TAG, "Gagal membersihkan clipboard", e)
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val needed = mutableListOf<String>()
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS).forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) needed.add(it)
        }
        if (needed.isNotEmpty()) { ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS_CODE); return false }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        heavyScanExecutor.shutdownNow()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            Log.d(TAG, "onRequestPermissionsResult diterima")
            if (webView.url.isNullOrBlank() || webView.url == "about:blank") {
                webView.loadUrl(resolveLaunchUrl(intent))
            }
        }
    }
}