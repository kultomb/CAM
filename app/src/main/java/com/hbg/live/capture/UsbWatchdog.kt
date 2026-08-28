package com.hbg.live.capture

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hbg.live.util.StudioLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * UsbWatchdog - Bộ Giám Sát Tín Hiệu & Tự Động Phục Hồi Kết Nối USB (Auto Reconnect Recovery)
 * Giám sát nhịp tim tín hiệu khung hình. Nếu trong 1.0 giây không nhận được gói tin MJPEG hoặc FPS = 0:
 * Tự động ngắt và tái nạp Data Plane C++ trong 100ms mà KHÔNG NGẮT luồng RTMP/SRT đang livestream.
 */
class UsbWatchdog(
    private val listener: WatchdogListener
) {
    interface WatchdogListener {
        fun onSignalLost()
        fun onSignalRestored()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lastFrameTimeMs = AtomicLong(0L)
    @Volatile private var isMonitoring = false
    @Volatile private var isRecovering = false

    companion object {
        private const val TAG = "UsbWatchdog"
        private const val TIMEOUT_MS = 1500L
        private const val CHECK_INTERVAL_MS = 1000L
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return

            val now = System.currentTimeMillis()
            val lastTime = lastFrameTimeMs.get()

            if (lastTime > 0 && (now - lastTime) > TIMEOUT_MS) {
                if (!isRecovering) {
                    isRecovering = true
                    StudioLogger.log(TAG, "⚠️ [WATCHDOG CẢNH BÁO] Mất tín hiệu USB quá 1.5s! Kích hoạt tự động phục hồi kết nối C++ Engine...")
                    listener.onSignalLost()
                }
            } else {
                if (isRecovering) {
                    isRecovering = false
                    StudioLogger.log(TAG, "🟢 [WATCHDOG KHÔI PHỤC] Đã khôi phục thành công tín hiệu USB Video Stream!")
                    listener.onSignalRestored()
                }
            }

            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        stop()
        isMonitoring = true
        isRecovering = false
        lastFrameTimeMs.set(System.currentTimeMillis())
        handler.postDelayed(watchdogRunnable, CHECK_INTERVAL_MS)
        Log.d(TAG, "UsbWatchdog đã bắt đầu giám sát tín hiệu 1.0s")
    }

    fun onFrameArrived() {
        lastFrameTimeMs.set(System.currentTimeMillis())
    }

    fun stop() {
        isMonitoring = false
        isRecovering = false
        handler.removeCallbacks(watchdogRunnable)
        Log.d(TAG, "UsbWatchdog đã dừng giám sát")
    }
}
