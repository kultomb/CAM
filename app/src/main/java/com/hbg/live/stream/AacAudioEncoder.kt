package com.hbg.live.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Enterprise AAC Audio Encoder (44.1 kHz Stereo / 128 kbps AAC-LC) cho Facebook Live & YouTube Live.
 * Tự động chọn nguồn Micro Camcorder directional mic (Camcorder Audio Source) tương thích 100% với Camera2 API.
 * Tính toán VU Meter Level (0-100%) hiển thị thanh sóng âm real-time và nạp luồng AAC Audio mượt mà.
 */
class AacAudioEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2,
    private val bitrate: Int = 128000,
    private val audioSource: Int = MediaRecorder.AudioSource.CAMCORDER,
    private val listener: Listener
) {
    interface Listener {
        fun onAacFrameAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onAudioLevelChanged(levelPercent: Int)
    }

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    @Volatile private var isRecording = false

    private var recordThread: Thread? = null

    companion object {
        private const val TAG = "AacAudioEncoder"
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return
        try {
            val channelConfig = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBufferSize, 8192)

            val sourcesToTry = intArrayOf(
                audioSource,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.UNPROCESSED
            )

            var record: AudioRecord? = null
            for (src in sourcesToTry) {
                try {
                    val r = AudioRecord(
                        src,
                        sampleRate,
                        channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        record = r
                        Log.d(TAG, "🟢 Khởi tạo AudioRecord thành công với AudioSource: $src")
                        break
                    } else {
                        r.release()
                    }
                } catch (e: Throwable) {}
            }

            if (record == null) {
                Log.e(TAG, "❌ Không thể khởi tạo AudioRecord với bất kỳ nguồn âm thanh nào!")
                return
            }

            audioRecord = record

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec!!.start()

            audioRecord!!.startRecording()
            isRecording = true

            recordThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val pcmBuffer = ByteArray(2048)

                while (isRecording) {
                    val readBytes = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                    if (readBytes > 0) {
                        val level = calculateRmsLevelPercent(pcmBuffer, readBytes)
                        listener.onAudioLevelChanged(level)

                        encodePcm(pcmBuffer, readBytes)
                    }
                    drainEncoder()
                }
            }.apply {
                name = "AacAudioRecordThread"
                start()
            }

            Log.d(TAG, "🟢 Đã khởi chạy AAC Audio Encoder & VU Meter (44.1kHz Stereo @ 128kbps) thành công!")
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi khởi chạy AAC Audio Encoder", e)
        }
    }

    private fun calculateRmsLevelPercent(pcmBuffer: ByteArray, readBytes: Int): Int {
        var sum = 0.0
        val sampleCount = readBytes / 2
        for (i in 0 until sampleCount * 2 step 2) {
            val sample = (pcmBuffer[i].toInt() and 0xFF) or (pcmBuffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort().toDouble()
            sum += shortSample * shortSample
        }
        if (sampleCount <= 0) return 0
        val rms = sqrt(sum / sampleCount)
        val percent = ((rms / 32767.0) * 100 * 3.5).toInt()
        return percent.coerceIn(0, 100)
    }

    private fun encodePcm(pcmData: ByteArray, length: Int) {
        val codec = mediaCodec ?: return
        val inputIndex = codec.dequeueInputBuffer(1000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(pcmData, 0, length)
                val presentationTimeUs = System.nanoTime() / 1000
                codec.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
            }
        }
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val aacBuffer = ByteBuffer.allocate(bufferInfo.size)
                    aacBuffer.put(outputBuffer)
                    aacBuffer.flip()

                    val cloneInfo = MediaCodec.BufferInfo().apply {
                        set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }

                    listener.onAacFrameAvailable(aacBuffer, cloneInfo)
                }
                codec.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Throwable) {}
        audioRecord = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Throwable) {}
        mediaCodec = null

        recordThread = null
        Log.d(TAG, "Đã dừng AAC Audio Encoder")
    }
}
