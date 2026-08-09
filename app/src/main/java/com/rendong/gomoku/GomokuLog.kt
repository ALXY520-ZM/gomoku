package com.rendong.gomoku

import android.util.Log
import java.io.File
import java.io.FileWriter

/**
 * 简易文件日志：写入 /sdcard/Download/Operit/gomoku_debug.log 方便排查
 */
object GomokuLog {
    private val logFile = File("/sdcard/Download/Operit/gomoku_debug.log")

    fun log(msg: String) {
        Log.d("Gomoku", msg)
        try {
            if (!logFile.parentFile.exists()) logFile.parentFile.mkdirs()
            val fw = FileWriter(logFile, true)
            fw.write("${System.currentTimeMillis()} | $msg\n")
            fw.close()
        } catch (_: Exception) {}
    }

    fun clear() {
        try { if (logFile.exists()) logFile.delete() } catch (_: Exception) {}
    }
}