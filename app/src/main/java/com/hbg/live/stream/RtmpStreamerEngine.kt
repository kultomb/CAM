package com.hbg.live.stream

import android.os.SystemClock
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Engine phát luồng RTMP/RTMPS chuyên dụng hỗ trợ YouTube Live & Facebook Live.
 * Hỗ trợ tự động phân tích và nạp trực tiếp SPS/PPS (H.264 AVC Decoder Configuration Record) cho Pedro RTMP Client.
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
    private var isVideoHeaderConfigured = false
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
            isVideoHeaderConfigured = false
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
            // Tự động phân tích SPS/PPS từ gói CODEC_CONFIG để thiết lập H.264 AVC Header cho Pedro Client
            if ((bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || !isVideoHeaderConfigured) {
                val spsPps = parseSpsAndPps(byteBuffer)
                if (spsPps != null) {
                    val (sps, pps) = spsPps
                    try {
                        rtmpClient.setVideoInfo(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps), null)
                        isVideoHeaderConfigured = true
                        Log.d(TAG, "🟢 Đã nạp thành công SPS (${sps.size}b) và PPS (${pps.size}b) cho Pedro RTMP Client!")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Lỗi nạp setVideoInfo", e)
                    }
                }
            }

            rtmpClient.sendVideo(byteBuffer, bufferInfo)
            totalBytesSent.addAndGet(bufferInfo.size.toLong())
            totalFramesSent.incrementAndGet()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gửi Video Frame", e)
            totalFramesDropped.incrementAndGet()
        }
    }

    private fun parseSpsAndPps(buffer: ByteBuffer): Pair<ByteArray, ByteArray>? {
        val bytes = ByteArray(buffer.remaining())
        val duplicate = buffer.duplicate()
        duplicate.get(bytes)

        var sps: ByteArray? = null
        var pps: ByteArray? = null

        val nalList = ArrayList<ByteArray>()
        var lastIndex = 0
        var i = 0
        while (i < bytes.size - 3) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                if (i > lastIndex) {
                    val nal = bytes.copyOfRange(lastIndex, i)
                    nalList.add(nal)
                }
                lastIndex = i + 4
                i += 4
            } else if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()) {
                if (i > lastIndex) {
                    val nal = bytes.copyOfRange(lastIndex, i)
                    nalList.add(nal)
                }
                lastIndex = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (lastIndex < bytes.size) {
            nalList.add(bytes.copyOfRange(lastIndex, bytes.size))
        }

        for (nal in nalList) {
            if (nal.isNotEmpty()) {
                val type = (nal[0].toInt() and 0x1F)
                if (type == 7) {
                    sps = nal
                } else if (type == 8) {
                    pps = nal
                }
            }
        }

        if (sps != null && pps != null) {
            return Pair(sps, pps)
        }
        return null
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
        isVideoHeaderConfigured = false
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
