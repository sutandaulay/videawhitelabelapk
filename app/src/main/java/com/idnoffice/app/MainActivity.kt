package com.idnoffice.app

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var btnSelesai: Button

    // Base URL
    var baseUrl = BuildConfig.APP_URL
    private val examPath = listOf("/assesment/r/room", "/assesment/room")

    // State
    private var isPinned = false
    var isExamMode = false

    // Codes
    private val REQUEST_PERMISSIONS_CODE = 1001
    private val FILE_CHOOSER_REQUEST_CODE = 2001

    // File upload
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var cameraVideoUri: Uri? = null

    // Audio / alarm
    private lateinit var audioManager: AudioManager
    private var previousVolume: Int = 0
    private var isAlarmPlaying = false
    private var mediaPlayer: MediaPlayer? = null
    private var alarmStopHandler: Handler? = null

    // Anti-cheat focus guard
    private var allowFocusCheck = true

    // Modern Photo Picker (Android 13+). Registered at class level.
    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                filePathCallback?.onReceiveValue(arrayOf(uri))
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }
    private var lastDownloadId: Long = -1

    private var startY = 0f
    private val SWIPE_THRESHOLD = 120f

    // Dialog flag
    private var isDialogOpen = false

    private fun signRoute() {
        val actionUrl = intent.getStringExtra("actionUrl")

        if (!actionUrl.isNullOrEmpty()) {
            baseUrl = baseUrl + actionUrl
        }

        webView.loadUrl(baseUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        signRoute()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        webView = findViewById(R.id.webView)
        setupSwipeRefresh()
        setupWebView()

        // update enable state considering dialog & exam mode
        webView.viewTreeObserver.addOnScrollChangedListener {
            swipeRefreshLayout.isEnabled = (webView.scrollY == 0 && !isExamMode && !isDialogOpen)
        }

        // ===== Tambahan kontrol sensitivitas swipe =====
        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startY = event.y
                MotionEvent.ACTION_MOVE -> {
                    val diffY = event.y - startY
                    // also check dialog flag here
                    swipeRefreshLayout.isEnabled = diffY > SWIPE_THRESHOLD && webView.scrollY == 0 && !isExamMode && !isDialogOpen
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    startY = 0f
                }
            }
            false
        }

        // JavaScript bridge for Blob downloads and share
        webView.addJavascriptInterface(BlobDownloader(this), "BlobDownloader")
        webView.addJavascriptInterface(WebAppShareInterface(this), "AndroidShare")

        addExitButton()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (checkAndRequestPermissions()) {
            webView.loadUrl(baseUrl)
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------- Helper classes ----------------
    class BlobDownloader(private val context: Context) {
        @JavascriptInterface
        fun downloadFile(base64Data: String, mimeType: String, fileName: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsPath, fileName)
                FileOutputStream(file).use { it.write(bytes) }
                Toast.makeText(context, "File tersimpan: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Gagal menyimpan file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    class WebAppShareInterface(private val context: Context) {
        @JavascriptInterface
        fun shareText(text: String) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val shareIntent = Intent.createChooser(sendIntent, "Bagikan lewat...")
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(shareIntent)
        }
    }

    // ---------------- URL / exam handling ----------------
    fun handleUrlChange(url: String?) {
        val inExam = examPath.any { url?.contains(it, ignoreCase = true) == true }
        if (inExam && !isPinned) activateExamMode()
        else if (isPinned && !inExam) deactivateExamMode()
    }

    // ---------------- Exit button (UI) ----------------
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
            setOnClickListener { showExitConfirmationDialog() }
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
        allowFocusCheck = false
        try {
            alarmStopHandler?.removeCallbacksAndMessages(null)
            alarmStopHandler = null
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isAlarmPlaying = false
        } catch (e: Exception) {
            // ignore cleanup errors
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Konfirmasi Keluar")
            .setMessage("Apakah Anda Yakin Ingin Keluar ?")
            .setPositiveButton("Ya, Keluar") { dialog, _ ->
                deactivateExamMode()
                webView.loadUrl(baseUrl)
                dialog.dismiss()
            }
            .setNegativeButton("Batal") { dialog, _ ->
                Handler(Looper.getMainLooper()).postDelayed({ allowFocusCheck = true }, 3000)
                dialog.dismiss()
            }
            .setCancelable(false)

        val dialog = builder.create()

        // disable swipe refresh while dialog shown
        dialog.setOnShowListener {
            isDialogOpen = true
            swipeRefreshLayout.isEnabled = false
        }

        dialog.setOnDismissListener {
            isDialogOpen = false
            // only enable if not in exam mode and web is at top
            swipeRefreshLayout.isEnabled = (webView.scrollY == 0 && !isExamMode && !isDialogOpen)
            if (isExamMode) {
                Handler(Looper.getMainLooper()).postDelayed({
                    allowFocusCheck = true
                }, 500)
            } else {
                allowFocusCheck = true
            }
        }

        dialog.show()
    }

    // ---------------- Scroll Refresh ---------------
    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            refreshWebView()
        }
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )
        // ensure initial state
        swipeRefreshLayout.isEnabled = !isExamMode && !isDialogOpen
    }

    private fun refreshWebView() {
        if (isExamMode) {
            Toast.makeText(this, "Refresh dinonaktifkan selama ujian", Toast.LENGTH_SHORT).show()
            swipeRefreshLayout.isRefreshing = false
            return
        }
        webView.reload()
    }

    // ---------------- WebView setup ----------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")

        // ============== DETEKSI MODAL DI DALAM WEBVIEW (JS) ==============
        webView.evaluateJavascript("""
    (function() {
        const observer = new MutationObserver(() => {
            const modalVisible = !!document.querySelector('.modal.show, .modal[style*="display: block"], .dialog-backdrop, [role="dialog"], .swal2-container');
            if (modalVisible) {
                AndroidInterface.setSwipeEnabled(false);
            } else {
                AndroidInterface.setSwipeEnabled(true);
            }
        });
        observer.observe(document.body, { childList: true, subtree: true, attributes: true });
    })();
""".trimIndent(), null)


        // Block long press / copy paste
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false
        webView.isHapticFeedbackEnabled = false
        webView.setOnCreateContextMenuListener { menu, _, _ -> menu.clear() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        webView.isFocusable = true

        // best-effort prevent text selection
        webView.evaluateJavascript("""
            document.documentElement.style.userSelect = 'none';
            document.documentElement.style.webkitUserSelect = 'none';
            document.documentElement.style.msUserSelect = 'none';
            document.documentElement.style.MozUserSelect = 'none';
        """.trimIndent(), null)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val resources = request.resources

                // Hanya izinkan jika permintaan adalah untuk Video/Audio Capture
                if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) ||
                    resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {

                    // Verifikasi izin Android sudah diberikan sebelum mengizinkan WebView
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(resources) // <-- IZIN DIBERIKAN
                    } else {
                        request.deny()
                        Toast.makeText(this@MainActivity, "Izin kamera Android ditolak.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    request.deny()
                }
            }
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // Panggil parent implementation (menampilkan dialog browser standar)
                super.onGeolocationPermissionsShowPrompt(origin, callback)

                // Verifikasi izin Android (LOCATION) sebelum memberikan izin WebView
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                ) {
                    // Beri izin Lokasi
                    callback?.invoke(origin, true, false)
                } else {
                    // Tolak izin
                    callback?.invoke(origin, false, false)
                    Toast.makeText(this@MainActivity, "Izin lokasi Android ditolak.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallbackArg: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // reset any previous callback
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallbackArg

                // show dialog with options: gallery / photo / video
                showFilePickerDialog()
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob")) {
                // handle blob via JS -> BlobDownloader bridge
                webView.evaluateJavascript("""
                    (async function() {
                      const blob = await fetch("$url").then(r => r.blob());
                      const reader = new FileReader();
                      reader.onload = function() {
                        const base64data = reader.result.split(',')[1];
                        window.BlobDownloader.downloadFile(base64data, "$mimeType", "${URLUtil.guessFileName(url, contentDisposition, mimeType)}");
                      };
                      reader.readAsDataURL(blob);
                    })();
                """.trimIndent(), null)
            } else {
                // http/https download
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                request.addRequestHeader("User-Agent", userAgent)
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
                lastDownloadId = dm.enqueue(request)
                Toast.makeText(applicationContext, "Mengunduh file...", Toast.LENGTH_LONG).show()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                handleUrlChange(url)
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefreshLayout.isRefreshing = false
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

                // Provide navigator.share fallback to Android share bridge
                view?.evaluateJavascript("""
                    (function() {
                        if (navigator.share && !navigator._androidOverridden) {
                            navigator._androidOverridden = true;
                            const originalShare = navigator.share;
                            navigator.share = function(data) {
                                if (window.AndroidShare && data) {
                                   let shareText = "";
                                    if (data.title) shareText += data.title + "\n";
                                    if (data.text) shareText += data.text + "\n";
                                    if (data.url) shareText += data.url;
                                    window.AndroidShare.shareText(shareText);
                                    return Promise.resolve();
                                } else {
                                    return originalShare.apply(this, arguments);
                                }
                            }
                        }
                    })();
                """.trimIndent(), null)

                view?.evaluateJavascript("""
                    if (!navigator.share) {
                        window.navigator.share = function(data) {
                            if (window.AndroidShare && data) {
                                let shareText = "";
                                if (data.title) shareText += data.title + "\n";
                                if (data.text) shareText += data.text + "\n";
                                if (data.url) shareText += data.url;
                                window.AndroidShare.shareText(shareText);
                                return Promise.resolve();
                            }
                            return Promise.reject("Fitur share tidak tersedia");
                        }
                    }
                """.trimIndent(), null)
            }
        }
    }

    // ---------------- File picker UI & helpers ----------------
    private fun showFilePickerDialog() {
        val options = arrayOf("Ambil dari Galeri", "Ambil Foto", "Ambil Video")
        val builder = AlertDialog.Builder(this)
            .setTitle("Pilih Sumber File")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openGallery()
                    1 -> openCameraImage()
                    2 -> openCameraVideo()
                }
            }
            .setOnCancelListener {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }

        val dialog = builder.create()

        // disable swipe refresh when dialog shown
        dialog.setOnShowListener {
            isDialogOpen = true
            swipeRefreshLayout.isEnabled = false
        }

        dialog.setOnDismissListener {
            isDialogOpen = false
            swipeRefreshLayout.isEnabled = (webView.scrollY == 0 && !isExamMode && !isDialogOpen)
        }

        dialog.show()
    }

    private fun openGallery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            photoPicker.launch(request)
        } else {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            startActivityForResult(Intent.createChooser(intent, "Pilih Media"), FILE_CHOOSER_REQUEST_CODE)
        }
    }

    private fun openCameraImage() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            val photoFile = createMediaFile("IMG_", ".jpg")
            cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.provider", photoFile)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
            startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka kamera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCameraVideo() {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        try {
            val videoFile = createMediaFile("VID_", ".mp4")
            cameraVideoUri = FileProvider.getUriForFile(this, "${packageName}.provider", videoFile)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraVideoUri)
            startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka kamera video", Toast.LENGTH_SHORT).show()
        }
    }

    @Throws(IOException::class)
    private fun createMediaFile(prefix: String, suffix: String): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = prefix + timeStamp + "_"
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (storageDir == null || (!storageDir.exists() && !storageDir.mkdirs())) {
            Log.e("FILE_ERROR", "Gagal menemukan atau membuat direktori")
            throw IOException("gagal mengakses direktori penyimpanan media")
        }
        return File.createTempFile(fileName, suffix, storageDir)
    }


    // ---------------- Activity result ----------------
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val callback = filePathCallback
            filePathCallback = null
            var results: Array<Uri>? = null
            if (resultCode == RESULT_OK) {
                if (data?.data != null){
                    results = arrayOf(data.data!!)
                }
                else if (cameraImageUri != null) {
                    results = arrayOf(cameraImageUri!!)
                }
                else if (cameraVideoUri != null) {
                    results = arrayOf(cameraVideoUri!!)
                }
            }
            callback?.onReceiveValue(results)

            // reset camera uris after use
            cameraImageUri = null
            cameraVideoUri = null
        }
    }

    // ---------------- Anti-cheat / exam ----------------
    private fun handleCheatingDetected(reason: String) {
        if (!isExamMode) return

        allowFocusCheck = false
        playAlarmSound()

        Handler(Looper.getMainLooper()).postDelayed({
            allowFocusCheck = true
            deactivateExamMode()
            webView.loadUrl(baseUrl)
            Toast.makeText(this, "Kecurangan terdeteksi. Anda dikeluarkan dari ujian.", Toast.LENGTH_LONG).show()
        }, 3000)
    }

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
            Handler(Looper.getMainLooper()).postDelayed({
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                isAlarmPlaying = false
            }, 3000)
        } catch (e: Exception) {
            isAlarmPlaying = false
        }
    }

    private fun playExitAlarmSound() {
        if (isAlarmPlaying) return
        isAlarmPlaying = true
        try {
            mediaPlayer?.release()
            mediaPlayer?.setVolume(100.0F, 100.0F)
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
            Handler(Looper.getMainLooper()).postDelayed({
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                isAlarmPlaying = false
            }, 1000)
        } catch (e: Exception) {
            isAlarmPlaying = false
        }
    }

    override fun onPause() {
        super.onPause()
        if (isExamMode) handleCheatingDetected("Aplikasi diminimize")
    }

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
            swipeRefreshLayout.isEnabled = false
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
                    (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            }
            allowFocusCheck = false
            Handler(Looper.getMainLooper()).postDelayed({ allowFocusCheck = true }, 3000)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun deactivateExamMode() {
        try {
            playExitAlarmSound()
            stopLockTask()
            swipeRefreshLayout.isEnabled = true
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
            // ignore
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isPinned && (event?.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            Toast.makeText(this, "Volume terkunci selama ujian", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- Permissions ----------------
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsNeeded = mutableListOf<String>()
        val basePermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        basePermissions.forEach { perm ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(perm)
            }
        }

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

// WebAppInterface (JS bridge) - tetap serupa
class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun setSwipeEnabled(enabled: Boolean) {
        activity.runOnUiThread {
            activity.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshLayout).isEnabled =
                enabled && !activity.isExamMode
        }
    }
}