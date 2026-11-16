package com.example.chatshell

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 最小可跑的安卓 WebView 壳：把 chat-demo 的静态产物装进 assets/www，
 * 用 WebViewAssetLoader 以 https://appassets.androidplatform.net 提供「属实源」，
 * 这样 localStorage / IndexedDB / getUserMedia 都能正常工作。
 *
 * 不需要本地代理：api.deepseek.com 对任意 Origin 反射放行 CORS。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 调试期可在 chrome://inspect 看到这个 WebView
        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 朗读（TTS）按 PCM 流播放，关掉手势限制避免首帧被静音策略卡住
            mediaPlaybackRequiresUserGesture = false
            // 页面与接口都走 https，不需要降低混合内容限制
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 注入下载桥接：导出历史时前端用的是 blob + a[download]，WebView 原生下载器接不住
                view?.evaluateJavascript(DOWNLOAD_SHIM, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // 录音走 getUserMedia，必须在这里显式放行麦克风
            override fun onPermissionRequest(request: PermissionRequest) {
                val granted = request.resources
                    .filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                if (granted.isNotEmpty()) {
                    request.grant(granted.toTypedArray())
                } else {
                    request.deny()
                }
            }
        }

        // 兜底：若页面出现普通的 http(s) 下载链接
        webView.setDownloadListener { url, _, _, _, _ ->
            Toast.makeText(this, "该链接需在浏览器中下载：$url", Toast.LENGTH_LONG).show()
        }

        requestMicPermissionIfNeeded()

        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    private fun requestMicPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予麦克风权限，语音输入不可用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** 前端调用 window.AndroidBridge.saveFile(name, base64, mime) 落地导出文件 */
    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, base64: String, mime: String): Boolean {
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                writeToDownloads(sanitize(name), bytes, mime)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "已保存：$name", Toast.LENGTH_LONG).show()
                }
                true
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存失败：${error.message}", Toast.LENGTH_LONG).show()
                }
                false
            }
        }
    }

    private fun writeToDownloads(name: String, bytes: ByteArray, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建下载条目")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("无法打开输出流")
        } else {
            // Android 9 及以下不申请存储权限，落到应用私有目录，避免额外授权
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val target = File(dir, name)
            FileOutputStream(target).use { it.write(bytes) }
        }
    }

    private fun sanitize(name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return safe.ifBlank { "deepseek_data.zip" }
    }

    private companion object {
        const val REQ_MIC = 1001

        const val DOWNLOAD_SHIM = """
(function () {
  try {
    var original = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      var href = this.href || '';
      var name = this.download;
      if (name && href.indexOf('blob:') === 0 && window.AndroidBridge) {
        var self = this;
        fetch(href)
          .then(function (r) { return r.blob(); })
          .then(function (blob) {
            var reader = new FileReader();
            reader.onload = function () {
              var data = String(reader.result);
              var comma = data.indexOf(',');
              window.AndroidBridge.saveFile(
                name,
                data.substring(comma + 1),
                blob.type || 'application/octet-stream'
              );
            };
            reader.readAsDataURL(blob);
          })
          .catch(function () { original.call(self); });
        return;
      }
      return original.call(this);
    };
  } catch (e) {}
})();
"""
    }
}
