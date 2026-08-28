package com.hbg.live.util

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trình ghi nhật ký phần cứng Studio (Studio Hardware Diagnostics Logger)
 * Ghi lại toàn bộ sự kiện cắm/rút USB, cấp quyền, USB Endpoint, MJPEG Decode và lỗi phần cứng real-time.
 */
object StudioLogger {
    private val logList = ArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var listener: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val errSuffix = if (throwable != null) " Exception: ${throwable.message}" else ""
        val entry = "[$timestamp] [$tag] $message$errSuffix"
        
        logList.add(entry)
        if (logList.size > 200) {
            logList.removeAt(0)
        }

        mainHandler.post {
            listener?.invoke(getAllLogs())
        }
    }

    @Synchronized
    fun getAllLogs(): String {
        return logList.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        logList.clear()
        mainHandler.post {
            listener?.invoke("")
        }
    }

    fun setLogListener(logListener: ((String) -> Unit)?) {
        this.listener = logListener
        listener?.invoke(getAllLogs())
    }
}
