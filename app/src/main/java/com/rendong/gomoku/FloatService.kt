package com.rendong.gomoku

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮球+控制面板服务
 * 点击悬浮球 → 弹出面板：思考强度选择 + 思考时间范围 + 开始/停止
 */
class FloatService : Service() {

    companion object {
        @Volatile var instance: FloatService? = null

        // 自动模式参数（供识别/引擎模块读取）
        var levelMs: Int = 5000          // 引擎思考时间（迭代加深预算）
        var minWaitSec: Int = 10         // 随机延迟最小秒
        var maxWaitSec: Int = 30         // 随机延迟最大秒
        var autoRunning: Boolean = false // 自动模式运行中
        var myColor: Int = 1             // 我方棋子：1黑 2白

        /** 由MainActivity授权回调调用：让前台服务创建MediaProjection（切后台不失效） */
        fun initFromActivity(resultCode: Int, data: android.content.Intent?) {
            instance?.let { svc ->
                if (data != null) {
                    GomokuAutoEngine.init(svc, resultCode, data)
                }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var ballView: TextView? = null
    private var panelView: View? = null
    private val ballParams: WindowManager.LayoutParams by lazy {
        makeParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 80, 300,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
    private val panelParams: WindowManager.LayoutParams by lazy {
        makeParams(dp(320), dp(440), 0, 0,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }
    private var panelShown = false
    private var ballX = 0; private var ballY = 0
    private var touchX = 0f; private var touchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun makeParams(w: Int, h: Int, x: Int, y: Int, flags: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            w, h, type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x; this.y = y
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 前台服务（MediaProjection要求）
        try {
            val channel = android.app.NotificationChannel(
                "gomoku_float", "五子棋AI助手", android.app.NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(channel)
            val notification = android.app.Notification.Builder(this, "gomoku_float")
                .setContentTitle("♟ 五子棋AI助手")
                .setContentText("悬浮球运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
            startForeground(1, notification)
        } catch (_: Exception) {}
        showBall()
    }

    // ============ 悬浮球 ============
    private fun showBall() {
        val ball = TextView(this).apply {
            text = "♟"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A2E.toInt())
            setBackgroundColor(0xFFE8B64C.toInt())
            setPadding(20, 20, 20, 20)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ballX = ballParams.x; ballY = ballParams.y
                        touchX = event.rawX; touchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) isDragging = true
                        if (isDragging) {
                            ballParams.x = ballX + dx; ballParams.y = ballY + dy
                            try { windowManager.updateViewLayout(this@apply, ballParams) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) togglePanel()
                        true
                    }
                    else -> false
                }
            }
        }
        try { windowManager.addView(ball, ballParams); ballView = ball } catch (e: Exception) { stopSelf() }
    }

    private fun togglePanel() {
        if (panelShown) { hidePanel() } else { showPanel() }
    }

    // ============ 控制面板 ============
    private var statusView: TextView? = null
    private var levelButtons = mutableListOf<Button>()

    private fun showPanel() {
        if (panelView != null) return
        val ctx = this
        panelParams.gravity = Gravity.CENTER
        panelParams.x = 0; panelParams.y = 0

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundColor(0xF01A1A2E.toInt())
        }

        // 标题
        root.addView(TextView(ctx).apply {
            text = "♟ 五子棋AI助手"
            textSize = 18f
            setTextColor(0xFFE8B64C.toInt())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(ctx).apply {
            text = "自动下棋控制"
            textSize = 12f
            setTextColor(0xFF9AA0B5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, dp(12))
        })

        // 思考强度
        root.addView(TextView(ctx).apply {
            text = "引擎强度（思考深度）"
            textSize = 13f; setTextColor(0xFFE8B64C.toInt())
        })
        val levelRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val levels = arrayOf("简单", "普通", "困难", "大师")
        val levelVals = intArrayOf(2000, 5000, 10000, 20000)
        levelButtons.clear()
        for (i in levels.indices) {
            val b = Button(ctx).apply {
                text = levels[i]
                textSize = 12f
                isAllCaps = false
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    levelMs = levelVals[i]
                    // 高亮选中的按钮
                    for (btn in levelButtons) {
                        btn.setBackgroundColor(0xFF2D2D4E.toInt())
                        btn.setTextColor(Color.WHITE)
                    }
                    setBackgroundColor(0xFFE8B64C.toInt())
                    setTextColor(0xFF1A1A2E.toInt())
                    updateStatus("✅ 已选强度：${levels[i]}（思考${levelVals[i]/1000}秒）")
                }
            }
            levelButtons.add(b)
            levelRow.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(2), dp(4), dp(2), dp(8))
            })
        }
        root.addView(levelRow)

        // 思考时间范围（随机延迟，防封）
        root.addView(TextView(ctx).apply {
            text = "模拟人类思考时间（随机秒）"
            textSize = 13f; setTextColor(0xFFE8B64C.toInt())
        })
        val timeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        timeRow.addView(TextView(ctx).apply { text = "最少"; textSize = 12f; setTextColor(0xFF9AA0B5.toInt()) })
        val minInput = EditText(ctx).apply {
            setText("10"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(0xFF2D2D4E.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        timeRow.addView(minInput, LinearLayout.LayoutParams(dp(56), dp(40)).apply { setMargins(dp(6), 0, dp(10), 0) })
        timeRow.addView(TextView(ctx).apply { text = "至"; textSize = 12f; setTextColor(0xFF9AA0B5.toInt()) })
        val maxInput = EditText(ctx).apply {
            setText("30"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(0xFF2D2D4E.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        timeRow.addView(maxInput, LinearLayout.LayoutParams(dp(56), dp(40)).apply { setMargins(dp(10), 0, 0, 0) })
        timeRow.addView(TextView(ctx).apply { text = "秒"; textSize = 12f; setTextColor(0xFF9AA0B5.toInt()) })
        root.addView(timeRow)
        root.addView(TextView(ctx).apply {
            text = "提示：落子前随机等待该区间秒数，模拟真人防封"
            textSize = 10f; setTextColor(0xFF6A7085.toInt())
            setPadding(0, dp(2), 0, dp(8))
        })

        // 我方棋子（自动识别黑白）
        root.addView(TextView(ctx).apply {
            text = "我方棋子（开局看头像旁标注）"
            textSize = 13f; setTextColor(0xFFE8B64C.toInt())
        })
        val colorRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val blackBtn = Button(ctx).apply {
            text = "⚫ 黑方"
            textSize = 12f; isAllCaps = false
            setOnClickListener { myColor = 1; updateStatus("✅ 我方：黑方（先手）") }
        }
        val whiteBtn = Button(ctx).apply {
            text = "⚪ 白方"
            textSize = 12f; isAllCaps = false
            setOnClickListener { myColor = 2; updateStatus("✅ 我方：白方（后手）") }
        }
        colorRow.addView(blackBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(4), dp(8)) })
        colorRow.addView(whiteBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(8)) })
        root.addView(colorRow)

        // 开始/停止 + 关闭
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val startBtn = Button(ctx).apply {
            text = "▶ 开始自动下棋"
            textSize = 14f; isAllCaps = false
            setOnClickListener {
                val mn = minInput.text.toString().toIntOrNull() ?: 10
                val mx = maxInput.text.toString().toIntOrNull() ?: 30
                if (mx < mn) {
                    updateStatus("⚠️ 最大值不能小于最小值")
                    return@setOnClickListener
                }
                minWaitSec = mn; maxWaitSec = mx
                if (autoRunning) {
                    // 停止
                    GomokuAutoEngine.stop()
                    autoRunning = false
                    text = "▶ 开始自动下棋"
                    updateStatus("⏹ 已停止自动下棋")
                    return@setOnClickListener
                }
                // 检查截图授权（首次需要）
                if (MainActivity.sharedWebView == null) {
                    updateStatus("⚠️ 请先打开App主界面")
                    return@setOnClickListener
                }
                if (!GomokuAutoEngine.isReady()) {
                    // 请求截图授权（走MainActivity）
                    updateStatus("📺 请允许屏幕录制授权…")
                    try {
                        MainActivity.topActivity?.requestScreenCapture()
                    } catch (_: Exception) {}
                    updateStatus("✅ 授权完成后，再次点击「开始」即启动")
                    return@setOnClickListener
                }
                // 检查无障碍
                if (!GomokuAccessibilityService.isReady()) {
                    updateStatus("⚠️ 请开启【五子棋AI助手】的无障碍服务（不是Operit的）")
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {}
                    return@setOnClickListener
                }
                // 全部就绪 → 启动自动循环
                GomokuAutoEngine.setContext(applicationContext)
                GomokuAutoEngine.start()
                autoRunning = true
                text = "⏹ 停止"
                updateStatus("✅ 自动下棋已启动：强度${levelMs/1000}秒·延迟${minWaitSec}~${maxWaitSec}秒·我方${if (myColor==1) "黑" else "白"}")
            }
        }
        btnRow.addView(startBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(0, dp(4), dp(4), dp(8)) })
        val closeBtn = Button(ctx).apply {
            text = "✕"
            textSize = 14f
            setOnClickListener { hidePanel() }
        }
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(4), dp(4), 0, dp(8)) })
        root.addView(btnRow)

        // 状态（面板内反馈，不依赖Toast）
        statusView = TextView(ctx).apply {
            text = "状态：待机中"
            textSize = 12f; setTextColor(0xFF4EC9B0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(statusView)

        val scroller = ScrollView(ctx)
        scroller.addView(root)
        panelView = scroller
        try { windowManager.addView(scroller, panelParams); panelShown = true } catch (_: Exception) {}
    }

    private fun updateStatus(text: String) {
        statusView?.let { runOnUiThreadSafe { it.text = "状态：$text" } }
    }

    private fun runOnUiThreadSafe(action: () -> Unit) {
        try { action() } catch (_: Exception) {}
    }

    private fun hidePanel() {
        panelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panelView = null; panelShown = false
    }

    override fun onDestroy() {
        instance = null
        ballView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        hidePanel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}