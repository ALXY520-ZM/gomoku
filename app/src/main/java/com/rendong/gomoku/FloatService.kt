package com.rendong.gomoku

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮球服务：显示"♟"悬浮球，可拖动，点击提示状态
 * 阶段2为状态展示用，后续阶段3/4将扩展为控制面板
 */
class FloatService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: TextView
    private var startX = 0
    private var startY = 0
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatView = TextView(this).apply {
            text = "♟"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A2E.toInt())
            setBackgroundColor(0xFFE8B64C.toInt())
            setPadding(20, 20, 20, 20)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 300
        }

        // 拖动 + 点击
        floatView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) isDragging = true
                    if (isDragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager.updateViewLayout(floatView, params)
                        } catch (e: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        Toast.makeText(this, "♟ 五子棋AI助手运行中\n引擎v2 · 随时待命", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(floatView, params)
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮球启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            windowManager.removeView(floatView)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}