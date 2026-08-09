package com.rendong.gomoku

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        @Volatile var sharedWebView: WebView? = null
        @Volatile var topActivity: MainActivity? = null
        private const val REQUEST_MEDIA_PROJECTION = 2001
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topActivity = this

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/gomoku.html")
        sharedWebView = webView
        GomokuAutoEngine.setContext(applicationContext)

        // 悬浮球按钮 + 授权截屏按钮（右上角）
        val floatBtn = TextView(this).apply {
            text = "♟"
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundColor(0xE8B64C.toInt())
            setTextColor(0xFF1A1A2E.toInt())
            setPadding(14, 14, 14, 14)
            setOnClickListener {
                toggleFloat()
            }
        }
        val capBtn = TextView(this).apply {
            text = "📺\n授权截屏"
            textSize = 10f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF2D2D4E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(10, 8, 10, 8)
            setOnClickListener {
                requestScreenCapture()
            }
        }
        val floatLabel = TextView(this).apply {
            text = "悬浮球"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        val btnWrap = FrameLayout(this).apply {
            setBackgroundColor(0x80000000.toInt())
            setPadding(8, 8, 8, 8)
            addView(floatBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START })
            addView(capBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                setMargins(dp(56), 0, 0, 0)
            })
            addView(floatLabel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })
        }

        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(btnWrap, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(40), dp(12), 0)
        })
        setContentView(root)
    }

    private fun toggleFloat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限，请授权后重试", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, FloatService::class.java))
        } else {
            startService(Intent(this, FloatService::class.java))
        }
        Toast.makeText(this, "♟ 悬浮球已启动（可拖动）", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 请求截屏授权（供自动模式用） */
    fun requestScreenCapture() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        try {
            startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                // 交给前台服务创建MediaProjection（Android14：切后台不失效）
                FloatService.initFromActivity(resultCode, data)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // 返回键：回退网页历史
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}