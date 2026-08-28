package com.hbg.live.stream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Enterprise H.264 Encoder (1280x720 @ 30 FPS / 2.5 Mbps) cho Facebook Live & YouTube Live.
 * Hỗ trợ nạp trực tiếp Hardware InputSurface từ Camera2 API và mảng Bitmap từ USB UVC Engine.
 */
class H264Encoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val bitrate: Int = 2500000, // 2.5 Mbps chuẩn ổn định Facebook Live
    private val frameRate: Int = 30,
    private val listener: Listener
) {
    interface Listener {
        fun onH264FrameAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isEncoderRunning = false

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    companion object {
        private const val TAG = "H264Encoder"
    }

    fun start() {
        if (isEncoderRunning) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe 1 giây mượt mà cho Facebook Live
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec!!.createInputSurface()

            encoderThread = HandlerThread("H264EncoderThread").apply { start() }
            encoderHandler = Handler(encoderThread!!.looper)

            mediaCodec!!.start()
            isEncoderRunning = true

            startOutputDrainThread()
            Log.d(TAG, "🟢 Đã khởi chạy H.264 Surface Encoder (720p30 @ 2.5Mbps) thành công!")
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi khởi chạy H.264 Encoder", e)
        }
    }

    fun getInputSurface(): Surface? = inputSurface

    fun encodeBitmap(bitmap: Bitmap) {
        if (!isEncoderRunning || inputSurface == null) return
        try {
            val surface = inputSurface ?: return
            val canvas = surface.lockHardwareCanvas() ?: return
            try {
                val dst = Rect(0, 0, width, height)
                canvas.drawBitmap(bitmap, null, dst, null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi nạp Bitmap vào H.264 Surface Encoder", e)
        }
    }

    private fun startOutputDrainThread() {
        encoderHandler?.post(object : Runnable {
            override fun run() {
                if (!isEncoderRunning) return
                try {
                    drainEncoder()
                } catch (e: Throwable) {
                    Log.e(TAG, "Lỗi drain H.264 Encoder", e)
                }
                if (isEncoderRunning) {
                    encoderHandler?.postDelayed(this, 10)
                }
            }
        })
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val frameBuffer = ByteBuffer.allocate(bufferInfo.size)
                    frameBuffer.put(outputBuffer)
                    frameBuffer.flip()

                    val cloneInfo = MediaCodec.BufferInfo().apply {
                        set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }

                    listener.onH264FrameAvailable(frameBuffer, cloneInfo)
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
        }
    }

    fun stop() {
        if (!isEncoderRunning) return
        isEncoderRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Throwable) {}
        mediaCodec = null

        try {
            inputSurface?.release()
        } catch (e: Throwable) {}
        inputSurface = null

        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
        Log.d(TAG, "Đã dừng H.264 Encoder")
    }
}
