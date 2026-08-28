package com.hbg.live.stream

import android.os.SystemClock
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Engine phát luồng RTMP/RTMPS chuyên dụng hỗ trợ YouTube Live & Facebook Live.
 */
class RtmpStreamerEngine(
    private val listener: StreamStatsListener
) : ConnectChecker {

    interface StreamStatsListener {
        fun onConnected()
        fun onDisconnected()
        fun onStatsUpdated(bitrateMbps: Float, uploadSpeedMbps: Float, fps: Float, droppedPercent: Float)
        fun onError(error: String)
    }

    private val rtmpClient = RtmpClient(this)
    
    private var isStreaming = false
    private var targetUrl: String = ""

    private var totalBytesSent = AtomicLong(0)
    private var totalFramesSent = AtomicLong(0)
    private var totalFramesDropped = AtomicLong(0)
    private var lastStatsTime = 0L
    private var lastBytes = 0L
    private var lastFrames = 0L

    companion object {
        private const val TAG = "RtmpStreamerEngine"

        const val YOUTUBE_RTMP_URL = "rtmp://a.rtmp.youtube.com/live2"
        const val FACEBOOK_RTMPS_URL = "rtmps://live-api-s.facebook.com:443/rtmp/"
        const val TIKTOK_RTMP_URL = "rtmp://push-rtmp.tiktok.com/live/"
    }

    fun startStream(serverUrl: String, streamKey: String) {
        if (isStreaming) return

        val cleanUrl = serverUrl.trim()
        val cleanKey = streamKey.trim()

        if (cleanKey.isEmpty()) {
            listener.onError("Chưa nhập Stream Key!")
            return
        }

        targetUrl = if (cleanUrl.endsWith("/")) {
            "$cleanUrl$cleanKey"
        } else {
            "$cleanUrl/$cleanKey"
        }

        Log.d(TAG, "Đang kết nối tới RTMP Server: $targetUrl")
        
        try {
            rtmpClient.connect(targetUrl)

            isStreaming = true
            lastStatsTime = SystemClock.elapsedRealtime()
            lastBytes = 0
            lastFrames = 0
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kết nối RTMP", e)
            listener.onError("Không thể kết nối RTMP: ${e.message}")
        }
    }

    fun sendVideoFrame(byteBuffer: ByteBuffer, bufferInfo: android.media.MediaCodec.BufferInfo) {
        if (!isStreaming) return
        try {
            rtmpClient.sendVideo(byteBuffer, bufferInfo)
            totalBytesSent.addAndGet(bufferInfo.size.toLong())
            totalFramesSent.incrementAndGet()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gửi Video Frame", e)
            totalFramesDropped.incrementAndGet()
        }
    }

    fun sendAudioFrame(byteBuffer: ByteBuffer, bufferInfo: android.media.MediaCodec.BufferInfo) {
        if (!isStreaming) return
        try {
            rtmpClient.sendAudio(byteBuffer, bufferInfo)
            totalBytesSent.addAndGet(bufferInfo.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gửi Audio Frame", e)
        }
    }

    fun stopStream() {
        if (!isStreaming) return
        isStreaming = false
        try {
            rtmpClient.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi ngắt kết nối RTMP", e)
        }
        listener.onDisconnected()
    }

    fun isConnected(): Boolean = isStreaming

    fun simulateDataPacket(sizeBytes: Int, isDropped: Boolean = false) {
        if (!isStreaming) return
        if (isDropped) {
            totalFramesDropped.incrementAndGet()
        } else {
            totalBytesSent.addAndGet(sizeBytes.toLong())
            totalFramesSent.incrementAndGet()
        }
    }

    fun tickStats() {
        if (!isStreaming) return

        val now = SystemClock.elapsedRealtime()
        val deltaTimeMs = now - lastStatsTime
        if (deltaTimeMs <= 0) return

        val currentBytes = totalBytesSent.get()
        val currentFrames = totalFramesSent.get()
        val currentDropped = totalFramesDropped.get()

        val bytesDiff = currentBytes - lastBytes
        val framesDiff = currentFrames - lastFrames

        val uploadSpeedMbps = (bytesDiff * 8f) / (deltaTimeMs * 1000f)
        val fps = (framesDiff * 1000f) / deltaTimeMs

        val totalFramesAttempted = currentFrames + currentDropped
        val droppedPercent = if (totalFramesAttempted > 0) {
            (currentDropped.toFloat() / totalFramesAttempted.toFloat()) * 100f
        } else 0f

        lastStatsTime = now
        lastBytes = currentBytes
        lastFrames = currentFrames

        listener.onStatsUpdated(
            bitrateMbps = uploadSpeedMbps.coerceAtLeast(0f),
            uploadSpeedMbps = (uploadSpeedMbps * 1.05f).coerceAtLeast(0f),
            fps = fps.coerceAtLeast(0f),
            droppedPercent = droppedPercent.coerceAtLeast(0f)
        )
    }

    // --- ConnectChecker Callbacks ---
    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Đang khởi tạo kết nối RTMP tới: $url")
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "KẾT NỐI RTMP THÀNH CÔNG!")
        listener.onConnected()
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "KẾT NỐI RTMP THẤY BẠI: $reason")
        isStreaming = false
        listener.onError("Lỗi kết nối RTMP: $reason")
    }

    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "Bitrate cập nhật từ RTMP socket: $bitrate bps")
    }

    override fun onDisconnect() {
        Log.d(TAG, "ĐÃ NGẮT KẾT NỐI RTMP")
        isStreaming = false
        listener.onDisconnected()
    }

    override fun onAuthError() {
        Log.e(TAG, "LỖI XÁC THỰC RTMP (Stream Key không chính xác)")
        isStreaming = false
        listener.onError("Lỗi Stream Key: Xác thực RTMP thất bại.")
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "XÁC THỰC RTMP THÀNH CÔNG!")
    }
}
