package com.sowz.aestudio

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Local Android shell for the pinned, bundled EffectCraft WebAssembly editor. */
class EffectCraftActivity : Activity() {
    private companion object {
        const val OPEN_FILE = 7301
        const val SAVE_EXPORT = 7302
        const val MAX_EXPORT_BYTES = 1024L * 1024L * 1024L
        const val EDITOR_URL =
            "https://appassets.androidplatform.net/assets/effectcraft/index.html?home&nosw&storage=indexeddb"
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var chooser: ValueCallback<Array<Uri>>? = null
    private val exportLock = Any()
    private var exportFile: File? = null
    private var exportStream: FileOutputStream? = null
    private var exportName = "SOWZ-AE-export.bin"
    private var exportMime = "application/octet-stream"
    private var expectedBytes = -1L
    private var writtenBytes = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 13, 16)
        window.navigationBarColor = Color.rgb(12, 13, 16)
        if (!hasBundledEditor()) {
            setContentView(TextView(this).apply {
                text = "EffectCraft motoru bu APK paketine eklenmemiş. Bu sürüm, GitHub Actions içindeki EffectCraft WASM derleme adımıyla oluşturulmalıdır."
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(28), dp(28), dp(28))
                setBackgroundColor(Color.rgb(12, 13, 16))
            })
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 13, 16))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.rgb(18, 20, 25))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            minWidth = dp(42)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "SOWZ AE  /  EFFECTCRAFT"
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(Color.rgb(245, 246, 248))
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(TextView(this).apply {
            text = "↻"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 156, 76))
            minWidth = dp(42)
            setOnClickListener { if (::webView.isInitialized) webView.reload() }
        })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(12, 13, 16))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            addJavascriptInterface(ExportBridge(), "SOWZDownload")
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url) ?: super.shouldInterceptRequest(view, request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net") return false
                if (uri.scheme == "https" || uri.scheme == "mailto") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                installExportBridge()
                super.onPageFinished(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                chooser?.onReceiveValue(null)
                chooser = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams.createIntent(), OPEN_FILE)
                    true
                } catch (_: Exception) {
                    chooser = null
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(this@EffectCraftActivity, "Dosya seçici açılamadı.", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        webView.loadUrl(EDITOR_URL)
    }

    private fun hasBundledEditor(): Boolean = try {
        assets.open("effectcraft/index.html").use { true }
    } catch (_: Exception) {
        false
    }

    private fun installExportBridge() {
        val script = """
            (() => {
              if (window.__sowzExportBridgeInstalled) return;
              window.__sowzExportBridgeInstalled = true;
              document.addEventListener('click', async (event) => {
                const target = event.target;
                const link = target instanceof Element ? target.closest('a[download]') : null;
                if (!link || !link.href || !link.href.startsWith('blob:')) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                try {
                  const blob = await fetch(link.href).then(r => r.blob());
                  if (!window.SOWZDownload.begin(link.download || 'SOWZ-AE-export.bin',
                      blob.type || 'application/octet-stream', blob.size)) {
                    throw new Error('Dosya boyutu sınırı aşıldı veya başka dışa aktarma sürüyor.');
                  }
                  const reader = blob.stream().getReader();
                  while (true) {
                    const item = await reader.read();
                    if (item.done) break;
                    const bytes = item.value;
                    for (let offset = 0; offset < bytes.length; offset += 32768) {
                      const part = bytes.subarray(offset, Math.min(offset + 32768, bytes.length));
                      let binary = '';
                      for (let i = 0; i < part.length; i++) binary += String.fromCharCode(part[i]);
                      if (!window.SOWZDownload.write(btoa(binary))) throw new Error('Dosya yazılamadı.');
                    }
                  }
                  if (!window.SOWZDownload.finish()) throw new Error('Dışa aktarma tamamlanamadı.');
                } catch (error) {
                  window.SOWZDownload.cancel();
                  alert('SOWZ AE dışa aktarma hatası: ' + (error && error.message ? error.message : error));
                }
              }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private inner class ExportBridge {
        @JavascriptInterface
        fun begin(name: String, mime: String, size: Double): Boolean {
            if (!size.isFinite() || size < 0 || size > MAX_EXPORT_BYTES.toDouble()) return false
            return synchronized(exportLock) {
                clearExportLocked()
                val safeName = File(name).name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    .take(120).ifBlank { "SOWZ-AE-export.bin" }
                val temp = File(cacheDir, "sowz-export-" + System.currentTimeMillis() + ".tmp")
                try {
                    exportStream = FileOutputStream(temp)
                    exportFile = temp
                    exportName = safeName
                    exportMime = mime.takeIf { it.length <= 120 && '/' in it } ?: "application/octet-stream"
                    expectedBytes = size.toLong()
                    writtenBytes = 0L
                    true
                } catch (_: Exception) {
                    clearExportLocked()
                    false
                }
            }
        }

        @JavascriptInterface
        fun write(chunk: String): Boolean = synchronized(exportLock) {
            val stream = exportStream ?: return@synchronized false
            try {
                val bytes = Base64.decode(chunk, Base64.DEFAULT)
                if (writtenBytes + bytes.size > MAX_EXPORT_BYTES ||
                    (expectedBytes >= 0 && writtenBytes + bytes.size > expectedBytes)
                ) return@synchronized false
                stream.write(bytes)
                writtenBytes += bytes.size
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun finish(): Boolean {
            val valid = synchronized(exportLock) {
                try {
                    val stream = exportStream ?: return@synchronized false
                    stream.flush()
                    stream.close()
                    exportStream = null
                    exportFile != null && (expectedBytes < 0 || writtenBytes == expectedBytes)
                } catch (_: Exception) {
                    clearExportLocked()
                    false
                }
            }
            if (valid) runOnUiThread { requestExportSaveLocation() } else cancel()
            return valid
        }

        @JavascriptInterface
        fun cancel() {
            synchronized(exportLock) { clearExportLocked() }
        }
    }

    private fun requestExportSaveLocation() {
        if (synchronized(exportLock) { exportFile } == null) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = exportMime
            putExtra(Intent.EXTRA_TITLE, exportName)
        }
        try {
            startActivityForResult(intent, SAVE_EXPORT)
        } catch (_: Exception) {
            Toast.makeText(this, "Kaydetme penceresi açılamadı.", Toast.LENGTH_LONG).show()
            synchronized(exportLock) { clearExportLocked() }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OPEN_FILE -> {
                val callback = chooser ?: return
                chooser = null
                callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            }
            SAVE_EXPORT -> {
                val source = synchronized(exportLock) { exportFile }
                val destination = data?.data
                if (resultCode == RESULT_OK && source != null && destination != null) {
                    try {
                        val output = contentResolver.openOutputStream(destination)
                            ?: throw IllegalStateException("Seçilen konuma yazılamıyor.")
                        output.use { target ->
                            FileInputStream(source).use { input -> input.copyTo(target, 1024 * 1024) }
                        }
                        Toast.makeText(this, "Dışa aktarılan dosya kaydedildi.", Toast.LENGTH_LONG).show()
                    } catch (error: Exception) {
                        Toast.makeText(this, "Kaydetme başarısız: " + (error.message ?: "bilinmeyen hata"), Toast.LENGTH_LONG).show()
                    } finally {
                        synchronized(exportLock) { clearExportLocked() }
                    }
                } else {
                    synchronized(exportLock) { clearExportLocked() }
                }
            }
        }
    }

    private fun clearExportLocked() {
        runCatching { exportStream?.close() }
        exportStream = null
        runCatching { exportFile?.delete() }
        exportFile = null
        expectedBytes = -1L
        writtenBytes = 0L
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        chooser?.onReceiveValue(null)
        chooser = null
        synchronized(exportLock) { clearExportLocked() }
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("SOWZDownload")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }
}
