package com.videa.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream

class VideaWebManager(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val webViewProgress: LinearProgressIndicator,
    private val layoutOffline: View,
    private val listener: WebEventListener
) {

    interface WebEventListener {
        fun onUrlChanged(url: String)
        fun onExamPinTriggered()
        fun onFileChooseRequest(filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?)
        fun onPermissionRequested(request: PermissionRequest)
    }

    companion object {
        private const val TAG = "VideaWeb"
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setFilterTouchesWhenObscured(true)

        webView.setOnLongClickListener { true }
        webView.isLongClickable = false

        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")
        webView.addJavascriptInterface(BlobDownloader(activity), "BlobDownloader")
        webView.addJavascriptInterface(WebAppShareInterface(activity), "WebAppShare")

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, contentDisposition, mimeType)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                listener.onPermissionRequested(request)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                listener.onFileChooseRequest(filePathCallback, fileChooserParams)
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("AutoLoginJS", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                listener.onUrlChanged(url)
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                webViewProgress.visibility = View.VISIBLE
                layoutOffline.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    activity.runOnUiThread {
                        webViewProgress.visibility = View.GONE
                        webView.visibility = View.GONE
                        layoutOffline.visibility = View.VISIBLE
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webViewProgress.visibility = View.GONE
                val u = url ?: return
                if (u != "about:blank") {
                    webView.visibility = View.VISIBLE
                }
                AutoLoginInjector.inject(activity, view)
                setupUrlSync(view)
            }
        }
    }

    private fun handleDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                allowScanningByMediaScanner()
            }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(activity, "Mengunduh $fileName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
        }
    }

    fun applyExamShield() {
        webView.evaluateJavascript(
            "(function() { " +
                    "document.oncopy = function(e) { e.preventDefault(); return false; }; " +
                    "document.onpaste = function(e) { e.preventDefault(); return false; }; " +
                    "document.oncut = function(e) { e.preventDefault(); return false; }; " +
                    "document.oncontextmenu = function(e) { e.preventDefault(); return false; }; " +
                    "document.body.style.webkitUserSelect='none'; " +
                    "})();", null
        )
    }

    private fun setupUrlSync(view: WebView?) {
        view?.evaluateJavascript(
            "(function() { if (window.__examSyncStarted) return; window.__examSyncStarted = true; " +
                    "function sync() { const url = window.location.href.toLowerCase(); " +
                    "if (window.__lastUrl !== url) { window.__lastUrl = url; " +
                    "if (window.AndroidInterface) window.AndroidInterface.onUrlChange(url); } } " +
                    "setInterval(sync, 1000); })();",
            null
        )
    }



    inner class WebAppInterface {
        @JavascriptInterface
        fun onUrlChange(url: String) {
            activity.runOnUiThread { listener.onUrlChanged(url) }
        }

        @JavascriptInterface
        fun triggerExamPin() {
            activity.runOnUiThread { listener.onExamPinTriggered() }
        }

        /**
         * Dipanggil oleh AutoLoginInjector.kt (JS) setelah Tahap 2 (form final) berhasil
         * diklik. Menandai sesi ini sebagai "sudah pernah authenticated", supaya kalau
         * nanti tiba-tiba muncul form login lagi (mis. setelah user tekan Logout di
         * website), itu dikenali sebagai logout -- bukan kondisi awal buka app -- dan
         * app akan panggil requestManualLogout() alih-alih login otomatis lagi.
         */
        @JavascriptInterface
        fun onAutoLoginSuccess() {
            Log.d(TAG, "Auto-login sukses, sesi ditandai authenticated.")
            AutoLoginInjector.markAuthenticated()
        }

        @JavascriptInterface
        fun requestManualLogout() {
            activity.runOnUiThread {
                Log.d(TAG, "Logout manual dipicu (dari website atau dari deteksi AutoLoginInjector).")
                AutoLoginInjector.resetSession()
                PreferenceHelper.clear(activity)
                val intent = Intent(activity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                activity.startActivity(intent)
                activity.finish()
            }
        }
    }

    class BlobDownloader(private val context: Context) {
        @JavascriptInterface
        fun downloadFile(base64Data: String, mimeType: String, fileName: String) {
            try {
                val cleanBase64 = base64Data.substringAfter(",")
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(bytes) }
                (context as? AppCompatActivity)?.runOnUiThread {
                    Toast.makeText(context, "File tersimpan: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
            }
        }
    }

    class WebAppShareInterface(private val context: Context) {
        @JavascriptInterface
        fun shareText(text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, "Bagikan lewat..."))
        }
    }
}