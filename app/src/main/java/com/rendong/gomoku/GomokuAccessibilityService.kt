package com.rendong.gomoku

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：模拟点击落子（自动模式的"手"）
 */
class GomokuAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GomokuAccessibilityService? = null
        private val handler = Handler(Looper.getMainLooper())

        /** 检查无障碍是否可用 */
        fun isReady(): Boolean = instance != null

        /** 模拟点击指定屏幕坐标（带人类轨迹：曲线+微随机偏移） */
        fun tap(x: Int, y: Int) {
            val svc = instance ?: return
            // 微随机偏移，模拟真人手指
            val ox = (Math.random() * 6 - 3).toInt()
            val oy = (Math.random() * 6 - 3).toInt()
            val path = Path().apply {
                moveTo((x + ox - 8).toFloat(), (y + oy - 8).toFloat())
                // 带弧线的人类轨迹
                quadraticTo(
                    (x + ox + 10).toFloat(), (y + oy - 12).toFloat(),
                    (x + ox).toFloat(), (y + oy).toFloat()
                )
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                .build()
            handler.post {
                try {
                    svc.dispatchGesture(gesture, null, null)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}