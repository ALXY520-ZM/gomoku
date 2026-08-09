package com.rendong.gomoku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.ValueCallback
import org.json.JSONArray

/**
 * 自动下棋引擎：截图 → 识别棋盘 → 黑白判断 → JS引擎算棋 → 无障碍点击 → 随机等待循环
 */
object GomokuAutoEngine {

    private const val TAG = "GomokuAuto"
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var running = false
    private var loopThread: Thread? = null

    // 棋盘定位比例（基于微信五子棋实测：X 4%~96%，Y 30%~72.5%）
    private const val GRID_X1 = 0.04
    private const val GRID_X2 = 0.96
    private const val GRID_Y1 = 0.30
    private const val GRID_Y2 = 0.725
    private const val GRID = 15

    /** 初始化截图（由MainActivity授权后调用） */
    fun init(ctx: Context, resultCode: Int, data: android.content.Intent) {
        GomokuLog.log("init: 截图授权回调 resultCode=$resultCode")
        stop()
        try {
            val pm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "gomoku-capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
            )
            GomokuLog.log("init完成: ${width}x$height")
        } catch (e: Exception) {
            GomokuLog.log("init异常: ${e.message}")
        }
    }

    fun isRunning() = running

    /** 是否已初始化（截图授权完成） */
    fun isReady(): Boolean = imageReader != null

    fun start() {
        if (running) return
        GomokuLog.log("start: 自动循环启动 levelMs=${FloatService.levelMs} minWait=${FloatService.minWaitSec} maxWait=${FloatService.maxWaitSec} myColor=${FloatService.myColor} imageReader=${imageReader != null}")
        running = true
        loopThread = Thread {
            while (running) {
                try {
                    step()
                } catch (e: Exception) {
                    GomokuLog.log("循环异常: ${e.message}")
                }
                Thread.sleep(1500)
            }
        }
        loopThread?.start()
    }

    fun stop() {
        running = false
        loopThread?.interrupt()
        loopThread = null
    }

    /** 单步：截图→识别→判断→落子 */
    private fun step() {
        GomokuLog.log("step: 开始一轮")
        val bitmap = captureBitmap()
        if (bitmap == null) { GomokuLog.log("step: 截图失败（imageReader未就绪？）"); return }
        val board = recognize(bitmap)
        bitmap.recycle()
        if (board == null) { GomokuLog.log("step: 识别返回null"); return }

        // 黑白判断（数子法：黑=白 → 黑先手走）
        var blacks = 0; var whites = 0
        for (r in 0 until GRID) for (c in 0 until GRID) {
            when (board[r][c]) {
                1 -> blacks++
                2 -> whites++
            }
        }
        val myColor = FloatService.myColor
        val turn = if (blacks == whites) 1 else 2
        GomokuLog.log("step: 识别结果 黑=$blacks 白=$whites 当前轮到=$turn 我方=$myColor 无障服务=${GomokuAccessibilityService.isReady()}")

        // 轮到我（且无障碍可用）才走
        if (turn != myColor) return
        if (!GomokuAccessibilityService.isReady()) { GomokuLog.log("step: 无障碍未开启，跳过"); return }

        // 导入引擎计算
        val move = getEngineMove(board, myColor)
        if (move == null) { GomokuLog.log("step: getEngineMove返回null（WebView引擎不可用？）"); return }
        GomokuLog.log("step: 引擎落子=(${move[0]},${move[1]})")
        if (move[0] < 0) return // 平局/异常

        // 棋盘坐标 → 屏幕坐标
        val ctx = getContext() ?: return
        val metrics = android.util.DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val gx1 = (metrics.widthPixels * GRID_X1).toInt()
        val gx2 = (metrics.widthPixels * GRID_X2).toInt()
        val gy1 = (metrics.heightPixels * GRID_Y1).toInt()
        val gy2 = (metrics.heightPixels * GRID_Y2).toInt()
        val px = gx1 + (gx2 - gx1) * move[1] / (GRID - 1)
        val py = gy1 + (gy2 - gy1) * move[0] / (GRID - 1)

        // 随机思考延迟（模拟人类，防封）
        val waitMs = (FloatService.minWaitSec * 1000 + Math.random() *
                (FloatService.maxWaitSec - FloatService.minWaitSec) * 1000).toLong()
        GomokuLog.log("step: 落子坐标($px,$py) 延迟${waitMs}ms")
        Thread.sleep(waitMs)

        GomokuAccessibilityService.tap(px, py)
        GomokuLog.log("step: 已点击($px,$py)")
    }

    /** 截图获取Bitmap */
    private fun captureBitmap(): Bitmap? {
        val reader = imageReader
        if (reader == null) { GomokuLog.log("capture: imageReader为空"); return null }
        val image = try { reader.acquireLatestImage() } catch (e: Exception) {
            GomokuLog.log("capture: acquire异常 ${e.message}"); null
        } ?: run {
            GomokuLog.log("capture: 无新帧（MediaProjection未出图？virtualDisplay=${virtualDisplay != null} mp=${mediaProjection != null}）")
            null
        } ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        // 裁剪掉rowPadding
        val crop = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        bitmap.recycle()
        return crop
    }

    /** 识别棋盘：返回 15x15 Int数组（0空 1黑 2白） */
    private fun recognize(bmp: Bitmap): Array<IntArray>? {
        val w = bmp.width; val h = bmp.height
        if (w == 0 || h == 0) return null
        val gx1 = (w * GRID_X1).toInt(); val gx2 = (w * GRID_X2).toInt()
        val gy1 = (h * GRID_Y1).toInt(); val gy2 = (h * GRID_Y2).toInt()

        // 背景参考色（棋盘中心）
        val cx = (gx1 + gx2) / 2; val cy = (gy1 + gy2) / 2
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var cnt = 0L
        for (y in cy - 15 until cy + 15) for (x in cx - 15 until cx + 15) {
            val p = bmp.getPixel(x, y)
            rSum += (p shr 16) and 0xFF; gSum += (p shr 8) and 0xFF; bSum += p and 0xFF; cnt++
        }
        val refR = (rSum / cnt).toInt(); val refG = (gSum / cnt).toInt(); val refB = (bSum / cnt).toInt()

        val cell = (gx2 - gx1) / GRID
        val R = Math.max(4, cell / 3)
        val board = Array(GRID) { IntArray(GRID) }

        for (r in 0 until GRID) for (c in 0 until GRID) {
            val px = gx1 + (gx2 - gx1) * c / (GRID - 1)
            val py = gy1 + (gy2 - gy1) * r / (GRID - 1)
            var sum = 0L; var n = 0L; var rr = 0L; var gg = 0L; var bb = 0L
            for (y in py - R..py + R) for (x in px - R..px + R) {
                if (x < 0 || x >= w || y < 0 || y >= h) continue
                val p = bmp.getPixel(x, y)
                val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
                rr += pr; gg += pg; bb += pb; sum += (pr + pg + pb) / 3; n++
            }
            if (n == 0L) { board[r][c] = 0; continue }
            val lum = (sum / n).toInt()
            val mr = (rr / n).toInt(); val mg = (gg / n).toInt(); val mb = (bb / n).toInt()
            val diff = Math.abs(mr - refR) + Math.abs(mg - refG) + Math.abs(mb - refB)
            board[r][c] = when {
                lum < 110 -> 1            // 暗 → 黑子
                diff > 130 && lum > 150 -> 2  // 亮且色差大 → 白子
                else -> 0                 // 接近背景 → 空
            }
        }
        return board
    }

    /** 调用WebView引擎算棋 */
    private fun getEngineMove(board: Array<IntArray>, color: Int): IntArray? {
        val webView = MainActivity.sharedWebView ?: return null
        val jsonArr = JSONArray()
        for (r in 0 until GRID) {
            val row = JSONArray()
            for (c in 0 until GRID) row.put(board[r][c])
            jsonArr.put(row)
        }
        var result: IntArray? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            try {
                webView.evaluateJavascript("importBoard('${jsonArr.toString().replace("'", "\\'")}')", null)
                webView.evaluateJavascript(
                    "getAIMove($color, ${FloatService.levelMs})",
                    object : ValueCallback<String> {
                        override fun onReceiveValue(value: String?) {
                            try {
                                val s = value?.trim()?.removePrefix("\"")?.removeSuffix("\"")
                                val obj = org.json.JSONObject(s ?: "{}")
                                result = intArrayOf(obj.optInt("r", -1), obj.optInt("c", -1))
                            } catch (_: Exception) {
                            } finally {
                                latch.countDown()
                            }
                        }
                    }
                )
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        try { latch.await(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return result
    }

    private var appContext: Context? = null
    fun setContext(ctx: Context) { appContext = ctx.applicationContext }
    private fun getContext(): Context? = appContext

    fun destroy() {
        stop()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }
}