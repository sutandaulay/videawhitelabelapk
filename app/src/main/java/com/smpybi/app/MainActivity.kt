package com.smpybi.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.WindowInsets
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnSelesai: Button
    private val baseUrl = "https://smpbhaktiinsanibogor.sch.id/app"
//    private val baseUrl = "https://idnoffice.videaclass.com/app"
    private val examPath = listOf("/assesment/r/room", "/assesment/room")
    private var isPinned = false
    private var isExamMode = false

    private val REQUEST_PERMISSIONS_CODE = 1001
    private val FILE_CHOOSER_REQUEST_CODE = 2001
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private lateinit var audioManager: AudioManager
    private var previousVolume: Int = 0
    private var isAlarmPlaying = false
    private var mediaPlayer: MediaPlayer? = null
    private var alarmStopHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = findViewById(R.id.webView)
        setupWebView()
        addExitButton()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (checkAndRequestPermissions()) {
            webView.loadUrl(baseUrl)
        }
    }
    fun handleUrlChange(url: String?) {
        Log.d("ExamDebug", "Navigasi ke: $url")
        val inExam = examPath.any { url?.contains(it, ignoreCase = true) == true }
        if (inExam && !isPinned) {
            activateExamMode()
        } else if (isPinned && !inExam) {
            deactivateExamMode()
        }
    }
    private fun addExitButton() {
        btnSelesai = Button(this).apply {
            text = "Keluar"
            textSize = 12f
            setPadding(3, 0, 3, 0)
            setBackgroundColor(getColor(android.R.color.holo_red_dark))
            setTextColor(getColor(android.R.color.white))
            visibility = View.GONE
            alpha = 0.85f
            elevation = 8f

            setOnClickListener {

                showExitConfirmationDialog()

            }
        }

        val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics).toInt()
        val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()

        val params = FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 20
            rightMargin = 40


        }

        val rootLayout = findViewById<FrameLayout>(android.R.id.content)
        rootLayout.addView(btnSelesai, params)
    }


    private fun showExitConfirmationDialog() {

        // ⬇️ LOGIKA UNTUK MENGHENTIKAN ALARM DENGAN AMAN
        try {
            // Hentikan Handler yang bertugas menghentikan alarm (SEKARANG BERFUNGSI)
            alarmStopHandler?.removeCallbacksAndMessages(null)
            alarmStopHandler = null

            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isAlarmPlaying = false

        } catch (e: Exception) {
            Log.e("ExamAudio", "Gagal menghentikan/membersihkan alarm: ${e.message}")
        }
        // ⬆️ AKHIR LOGIKA HENTIKAN ALARM

        AlertDialog.Builder(this)
            .setTitle("Konfirmasi Keluar")
            .setMessage("Apakah Anda Yakin Ingin Keluar ?")
            // Tombol 'Ya, Keluar' (Positif)
            .setPositiveButton("Ya, Keluar") { dialog, _ ->
                // Matikan deteksi saat keluar agar tidak ada deteksi palsu setelah pin mati
                allowFocusCheck = true
                deactivateExamMode()
                webView.loadUrl(baseUrl)
                dialog.dismiss()
            }
            // Tombol 'Batal' (Negatif)
            .setNegativeButton("Batal") { dialog, _ ->

                // PERBAIKAN: AKTIFKAN ALLOWFOCUSCHECK KEMBALI SETELAH JEDA AMAN
                // Memberi waktu 3 detik bagi user untuk menutup aplikasi floating/split-screen
                Handler(Looper.getMainLooper()).postDelayed({
                    allowFocusCheck = true
                }, 3000)

                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
//        settings.setSupportZoom(true) // untuk PDF zoom



        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")

        // ❌ Blok long press / copy paste
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false
        webView.isHapticFeedbackEnabled = false
        webView.setOnCreateContextMenuListener { menu, v, menuInfo -> menu.clear() }

        // Inject JS untuk mencegah seleksi teks
        webView.evaluateJavascript("""
            document.documentElement.style.userSelect = 'none';
            document.documentElement.style.webkitUserSelect = 'none';
            document.documentElement.style.msUserSelect = 'none';
            document.documentElement.style.MozUserSelect = 'none';
        """.trimIndent(), null)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT)
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE)
                contentIntent.type = "*/*"

                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (cameraIntent.resolveActivity(packageManager) != null) {
                    val photoFile = createImageFile()
                    cameraImageUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.provider",
                        photoFile
                    )
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER)
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentIntent)
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Pilih File atau Kamera")
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                return true
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType)
            request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
            request.addRequestHeader("User-Agent", webView.settings.userAgentString)
            request.setDescription("Mengunduh file...")
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
            request.allowScanningByMediaScanner()
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimeType)
            )
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(applicationContext, "Download dimulai...", Toast.LENGTH_SHORT).show()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                handleUrlChange(url)
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handleUrlChange(url)
                view?.evaluateJavascript("""
                    (function() {
                        window.addEventListener('popstate', function() {
                            AndroidInterface.onUrlChange(window.location.href);
                        });
                        const pushState = history.pushState;
                        history.pushState = function() {
                            pushState.apply(this, arguments);
                            AndroidInterface.onUrlChange(window.location.href);
                        };
                    })();
                """.trimIndent(), null)
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir: File = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            var results: Array<Uri>? = null
            if (resultCode == Activity.RESULT_OK) {
                if (data == null || data.data == null) {
                    cameraImageUri?.let { results = arrayOf(it) }
                } else {
                    data.data?.let { results = arrayOf(it) }
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }


    private var allowFocusCheck = true
    // 🔹 Fungsi utama deteksi kecurangan
// 🔹 Fungsi utama deteksi kecurangan
    fun handleCheatingDetected(reason: String) {
        if (!isExamMode) return

        val readableReason = when (reason) {
            "Aplikasi diminimize" -> "Kamu mencoba keluar dari aplikasi ujian."
            "Aplikasi kehilangan fokus" -> "Kamu mencoba membuka aplikasi lain selama ujian."
            "Tombol deteksi dari web ditekan" -> "Sistem mendeteksi aktivitas mencurigakan."
            else -> "Terjadi aktivitas mencurigakan selama ujian."
        }

        // Log lengkap
        Log.w("ExamSecurity", "⚠️ Kecurangan terdeteksi! Alasan teknis: $reason")
        Log.i("ExamSecurity", "Pengguna dikeluarkan karena: $readableReason")

        // PERBAIKAN KRITIS: MATIKAN DETEKSI FOKUS SEGERA
        allowFocusCheck = false

        // Bunyi alarm (Alarm akan berbunyi 3 detik, kemudian mati sendiri)
        playAlarmSound()

        // ⬇️ LOGIKA KELUAR PAKSA SETELAH ALARM
        // Beri jeda 3 detik (setelah alarm selesai) untuk keluar paksa
        Handler(Looper.getMainLooper()).postDelayed({
            // Pastikan tidak ada konflik dengan allowFocusCheck dari activateExamMode
            allowFocusCheck = true
            deactivateExamMode()
            webView.loadUrl(baseUrl)
            Toast.makeText(
                this,
                "Kecurangan terdeteksi! $readableReason. Anda dikeluarkan dari ujian.",
                Toast.LENGTH_LONG
            ).show()
        }, 3000) // Keluar paksa setelah 3 detik
    }




    // 🔹 Bunyi alarm dari file raw (selama 3 detik)
    private fun playAlarmSound() {
        if (isAlarmPlaying) return
        isAlarmPlaying = true

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, R.raw.blocksound)
            mediaPlayer?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = false
                start()
            }

            // ⬇️ LOGIKA ALARM MATI SENDIRI SETELAH 3 DETIK
            Handler(Looper.getMainLooper()).postDelayed({
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                isAlarmPlaying = false
            }, 3000)
            // ⬆️ TIDAK MENGGUNAKAN alarmStopHandler LAGI

        } catch (e: Exception) {
            e.printStackTrace()
            isAlarmPlaying = false
        }
    }

    // 🔹 Jika keluar dari app / minimize saat ujian
    override fun onPause() {
        super.onPause()
        if (isExamMode) handleCheatingDetected("Aplikasi diminimize")
    }

    // 🔹 Jika kehilangan fokus (misal buka floating app / split screen)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (isExamMode && allowFocusCheck && !hasFocus) {
            handleCheatingDetected("Aplikasi kehilangan fokus")
        }
    }
    private fun activateExamMode() {
        try {
            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

            webView.settings.setSupportZoom(true)
            webView.settings.builtInZoomControls = true
            webView.settings.displayZoomControls = false
            try {
                startLockTask()
                isPinned = true
                isExamMode = true
            } catch (e: Exception) {
                isPinned = false
                isExamMode = false
            }
            if (!isPinned && !isExamMode) {
                handleCheatingDetected("Menolak izin pin aplikasi (tidak masuk ujian)")
                return
            }

            btnSelesai.visibility = View.VISIBLE
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            )
            }
            allowFocusCheck = false
            Handler(Looper.getMainLooper()).postDelayed({
                allowFocusCheck = true
            }, 3000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deactivateExamMode() {
        try {
            stopLockTask()
            isPinned = false
            isExamMode = false
            btnSelesai.visibility = View.GONE
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)



            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isPinned && (event?.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            Toast.makeText(this, "Volume terkunci selama ujian", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsNeeded = mutableListOf<String>()

        // Izin Inti (CAMERA & AUDIO)
        val basePermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        // Logika Izin Membaca File Sesuai Versi Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33) ke atas

            // Hanya meminta izin granular Media Store
            val mediaPermissions = arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )

            // Gabungkan Izin Dasar + Media Store API
            (basePermissions + mediaPermissions).forEach { perm -> // Pengecekan sekali jalan
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(perm)
                }
            }

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6 (API 23) sampai 12 (API 32)

            // Meminta izin Legacy Storage
            val storagePermissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )

            // Gabungkan Izin Dasar + Legacy Storage
            (basePermissions + storagePermissions).forEach { perm ->
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(perm)
                }
            }
        }
        // Android < API 23 (Marshmallow) tidak memerlukan runtime check

        return if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_PERMISSIONS_CODE)
            false
        } else true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                webView.loadUrl(baseUrl)
            } else {
                Toast.makeText(this, "Semua izin wajib diaktifkan agar aplikasi berjalan", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    override fun onBackPressed() {
        if (isPinned) Toast.makeText(this, "Tidak bisa kembali selama ujian", Toast.LENGTH_SHORT).show()
        else if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

}

class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun onUrlChange(url: String) {
        activity.runOnUiThread { activity.handleUrlChange(url) }
    }
}
