package com.videa.app

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class ExamManager(
    private val activity: AppCompatActivity,
    private val headerContainer: LinearLayout,
    private val listener: ExamListener
) {

    interface ExamListener {
        fun onExamActivated()
        fun onExamDeactivated()
        fun onExamError(message: String)
        fun onRefreshRequested()
        fun onExitRequested()
    }

    enum class OrientationLockState { AUTO, PORTRAIT, LANDSCAPE }

    private var isExamMode = false
    private var isPinned = false
    private var examActivatedAt = 0L
    private var examOrientationLockState = OrientationLockState.AUTO

    private lateinit var examHeaderBar: LinearLayout
    private lateinit var tvExamClock: TextView
    private lateinit var tvExamBattery: TextView
    private lateinit var ivExamOnlineStatus: ImageView
    private lateinit var btnExamOrientationLock: ImageButton

    private var onlineBlinkAnimator: ObjectAnimator? = null
    private val examClockHandler = Handler(Looper.getMainLooper())
    private var connectivityManager: ConnectivityManager? = null
    private var examNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiverRegistered = false

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousVolume = 0

    private val examClockRunnable = object : Runnable {
        override fun run() {
            updateExamClock()
            examClockHandler.postDelayed(this, 1000L)
        }
    }

    private val examBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                activity.runOnUiThread { tvExamBattery.text = "🔋 $pct%" }
            }
        }
    }

    init {
        setupHeaderBar()
    }

    fun isExamActive() = isExamMode
    fun isPinned() = isPinned
    fun getActivatedAt() = examActivatedAt

    private fun setupHeaderBar() {
        examHeaderBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(8.dp, 4.dp, 8.dp, 4.dp)
            visibility = View.GONE
            elevation = 12f
        }

        val badgeMode = TextView(activity).apply {
            text = "Mode Ujian"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(8.dp, 3.dp, 8.dp, 3.dp)
            background = roundedDrawable(Color.parseColor("#D32F2F"), 10)
        }
        examHeaderBar.addView(badgeMode)

        ivExamOnlineStatus = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        examHeaderBar.addView(ivExamOnlineStatus, LinearLayout.LayoutParams(16.dp, 16.dp).apply { marginStart = 8.dp })

        tvExamBattery = TextView(activity).apply { text = "🔋 --%"; setTextColor(Color.WHITE); textSize = 12f }
        examHeaderBar.addView(tvExamBattery, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 6.dp })

        tvExamClock = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 12f; setTypeface(null, Typeface.BOLD) }
        examHeaderBar.addView(tvExamClock, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 8.dp })

        examHeaderBar.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))

        val btnRefresh = makeIconButton(R.drawable.ic_refresh, Color.parseColor("#37474F"))
        btnRefresh.setOnClickListener { listener.onRefreshRequested() }
        examHeaderBar.addView(btnRefresh, LinearLayout.LayoutParams(28.dp, 28.dp).apply { marginStart = 6.dp })

        btnExamOrientationLock = makeIconButton(R.drawable.ic_orientation_auto, Color.parseColor("#37474F"))
        btnExamOrientationLock.setOnClickListener { cycleOrientationLock() }
        examHeaderBar.addView(btnExamOrientationLock, LinearLayout.LayoutParams(28.dp, 28.dp).apply { marginStart = 6.dp })

        val btnExit = makeIconButton(R.drawable.ic_exit, Color.parseColor("#B71C1C"))
        btnExit.setOnClickListener { showExitPinDialog() }
        examHeaderBar.addView(btnExit, LinearLayout.LayoutParams(28.dp, 28.dp).apply { marginStart = 6.dp })

        headerContainer.addView(examHeaderBar, LinearLayout.LayoutParams(-1, -2))
    }

    fun activate() {
        if (isPinned || isExamMode) return
        isExamMode = true
        headerContainer.visibility = View.VISIBLE
        examHeaderBar.visibility = View.VISIBLE
        examOrientationLockState = OrientationLockState.AUTO
        startHeaderUpdates()
        
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                activity.startLockTask()
                isPinned = true
                examActivatedAt = System.currentTimeMillis()
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) activity.window.setHideOverlayWindows(true)
                listener.onExamActivated()
            } catch (e: Exception) {
                Log.e("ExamManager", "Failed to start lock task", e)
                deactivate()
                listener.onExamError("Gagal mengaktifkan mode penguncian layar.")
            }
        }, 500)
    }

    fun deactivate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) activity.window.setHideOverlayWindows(false)
            if (isPinned) activity.stopLockTask()
        } catch (e: Exception) { }
        
        isPinned = false
        isExamMode = false
        examActivatedAt = 0L
        examHeaderBar.visibility = View.GONE
        headerContainer.visibility = View.GONE
        stopHeaderUpdates()
        
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        listener.onExamDeactivated()
    }

    private fun startHeaderUpdates() {
        examClockHandler.post(examClockRunnable)
        if (connectivityManager == null) connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        updateOnlineStatus(currentlyHasInternet())
        
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        examNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { updateOnlineStatus(true) }
            override fun onLost(n: Network) { updateOnlineStatus(currentlyHasInternet()) }
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                updateOnlineStatus(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        connectivityManager?.registerNetworkCallback(request, examNetworkCallback!!)

        if (!batteryReceiverRegistered) {
            activity.registerReceiver(examBatteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true
        }
    }

    private fun stopHeaderUpdates() {
        examClockHandler.removeCallbacks(examClockRunnable)
        examNetworkCallback?.let { try { connectivityManager?.unregisterNetworkCallback(it) } catch (e: Exception) {} }
        examNetworkCallback = null
        if (batteryReceiverRegistered) {
            try { activity.unregisterReceiver(examBatteryReceiver) } catch (e: Exception) {}
            batteryReceiverRegistered = false
        }
        stopBlink()
    }

    private fun updateExamClock() {
        tvExamClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        activity.runOnUiThread {
            if (isOnline) {
                ivExamOnlineStatus.setImageDrawable(null)
                ivExamOnlineStatus.background = ovalDrawable(Color.parseColor("#4CAF50"))
            } else {
                ivExamOnlineStatus.background = null
                ivExamOnlineStatus.setImageResource(R.drawable.ic_signal_off)
            }
            startBlink(ivExamOnlineStatus)
        }
    }

    private fun currentlyHasInternet(): Boolean {
        val cm = connectivityManager ?: return false
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun cycleOrientationLock() {
        examOrientationLockState = when (examOrientationLockState) {
            OrientationLockState.AUTO -> OrientationLockState.PORTRAIT
            OrientationLockState.PORTRAIT -> OrientationLockState.LANDSCAPE
            OrientationLockState.LANDSCAPE -> OrientationLockState.AUTO
        }
        applyOrientationLock()
    }

    private fun applyOrientationLock() {
        when (examOrientationLockState) {
            OrientationLockState.AUTO -> {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                btnExamOrientationLock.setImageResource(R.drawable.ic_orientation_auto)
            }
            OrientationLockState.PORTRAIT -> {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                btnExamOrientationLock.setImageResource(R.drawable.ic_orientation_portrait)
            }
            OrientationLockState.LANDSCAPE -> {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                btnExamOrientationLock.setImageResource(R.drawable.ic_orientation_landscape)
            }
        }
    }

    private fun startBlink(v: View) {
        stopBlink()
        onlineBlinkAnimator = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.25f).apply {
            duration = 600; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator(); start()
        }
    }

    private fun stopBlink() { onlineBlinkAnimator?.cancel(); onlineBlinkAnimator = null }

    private fun showExitPinDialog() {
        // Suppress focus check di MainActivity jika ada method-nya, atau biarkan aman
        try {
            val method = activity.javaClass.getDeclaredMethod("suppressFocusCheck", Long::class.java)
            method.isAccessible = true
            method.invoke(activity, 10000L) // Suppress selama 10 detik saat dialog PIN muncul
        } catch (e: Exception) {
            // ignore if method not found
        }

        activity.runOnUiThread {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Konfirmasi Pengawas")
            builder.setMessage("Masukkan PIN Pengawas untuk keluar dari ruang ujian:")

            val input = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "PIN Pengawas"
            }

            val container = FrameLayout(activity)
            val params = FrameLayout.LayoutParams(-1, -2).apply {
                leftMargin = 50.dp
                rightMargin = 50.dp
            }
            input.layoutParams = params
            container.addView(input)
            builder.setView(container)

            builder.setPositiveButton("OK") { _, _ ->
                val enteredPin = input.text.toString()
                val correctPin = PreferenceHelper.getExitPin(activity)

                if (enteredPin == correctPin) {
                    listener.onExitRequested()
                } else {
                    Toast.makeText(activity, "PIN SALAH! Peringatan tercatat.", Toast.LENGTH_LONG).show()
                    playAlarmSound()
                }
            }
            builder.setNegativeButton("Batal") { dialog, _ -> dialog.cancel() }
            builder.setCancelable(false)
            builder.show()
        }
    }

    private fun playAlarmSound() {
        try {
            val mediaPlayer = MediaPlayer.create(activity, R.raw.blocksound)
            mediaPlayer?.start()
            Handler(Looper.getMainLooper()).postDelayed({
                mediaPlayer?.stop()
                mediaPlayer?.release()
            }, 3000)
        } catch (e: Exception) {
            Log.w("ExamManager", "Gagal memutar alarm", e)
        }
    }

    private fun makeIconButton(res: Int, bg: Int) = ImageButton(activity).apply {
        setImageResource(res); scaleType = ImageView.ScaleType.FIT_CENTER
        background = roundedDrawable(bg, 6); setPadding(5.dp, 5.dp, 5.dp, 5.dp)
    }

    private fun roundedDrawable(bg: Int, rad: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = rad.dp.toFloat(); setColor(bg)
    }

    private fun ovalDrawable(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }

    private val Int.dp: Int get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), activity.resources.displayMetrics).toInt()
}