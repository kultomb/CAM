package com.hbg.live.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * Bộ mã hóa H.264 & AAC phần cứng sử dụng MediaCodec với Surface Input (Zero-Copy).
 */
class MediaCodecEncoder(
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 60,
    private val bitrateBps: Int = 8_500_000, // 8.5 Mbps
    private val listener: EncoderCallback
) {
    interface EncoderCallback {
        fun onVideoEncodedFrame(buffer: MediaCodec.BufferInfo, data: ByteArray)
        fun onAudioEncodedFrame(buffer: MediaCodec.BufferInfo, data: ByteArray)
        fun onError(error: String)
    }

    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isEncoding = false

    /**
     * Khởi tạo Bộ mã hóa H.264 Phần cứng (MediaCodec Surface Input)
     */
    fun initVideoEncoder(): Surface? {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe mỗi 1 giây (chuẩn YouTube RTMP)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder?.createInputSurface()
            videoEncoder?.start()

            Log.d(TAG, "Đã khởi tạo H.264 Hardware Encoder thành công: ${width}x${height} @ ${fps}FPS, Bitrate: ${bitrateBps / 1000} Kbps")
            return inputSurface
        } catch (e: Exception) {
            Log.e(TAG, "Không thể khởi tạo MediaCodec H.264 Encoder phần cứng", e)
            listener.onError("Lỗi MediaCodec H.264: ${e.message}")
            return null
        }
    }

    /**
     * Thay đổi bitrate động theo thời gian thực dựa trên băng thông mạng (Adaptive Bitrate)
     */
    fun updateBitrate(newBitrateBps: Int) {
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
        }
        videoEncoder?.setParameters(params)
        Log.d(TAG, "Đã cập nhật Bitrate động: ${newBitrateBps / 1000} Kbps")
    }

    fun stop() {
        isEncoding = false
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null
            inputSurface?.release()
            inputSurface = null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi giải phóng MediaCodec Encoder", e)
        }
    }

    companion object {
        private const val TAG = "MediaCodecEncoder"
    }
}
