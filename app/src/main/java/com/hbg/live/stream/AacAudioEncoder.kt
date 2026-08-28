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
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.VOICE_RECOGNITION
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
                        break
                    } else {
                        r.release()
                    }
                } catch (e: Throwable) {}
            }

            if (record == null) {
                Log.e(TAG, "❌ Không thể mở bất kỳ nguồn AudioRecord nào!")
                return
            }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            audioRecord = record
            mediaCodec = codec
            isRecording = true

            record.startRecording()

            recordThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val pcmBuffer = ByteArray(4096)
                val bufferInfo = MediaCodec.BufferInfo()

                while (isRecording) {
                    val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readBytes > 0) {
                        val level = calculatePcmLevel(pcmBuffer, readBytes)
                        listener.onAudioLevelChanged(level)

                        val inputBufferIndex = codec.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                inputBuffer.put(pcmBuffer, 0, readBytes)
                                val pts = System.nanoTime() / 1000
                                codec.queueInputBuffer(inputBufferIndex, 0, readBytes, pts, 0)
                            }
                        }
                    }

                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    while (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (bufferInfo.size > 0) {
                                listener.onAacFrameAvailable(outputBuffer, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }
                }
            }.apply {
                name = "AacAudioEncoderRecordThread"
                start()
            }

            Log.d(TAG, "🟢 AAC Audio Encoder Khởi Động Thành Công (44.1 kHz Stereo @ 128 kbps)")
        } catch (e: Throwable) {
            Log.e(TAG, "❌ Khởi động AacAudioEncoder thất bại", e)
        }
    }

    private fun calculatePcmLevel(pcmData: ByteArray, size: Int): Int {
        var sum = 0.0
        val samples = size / 2
        for (i in 0 until samples) {
            val sample = (pcmData[i * 2 + 1].toInt() shl 8) or (pcmData[i * 2].toInt() and 0xFF)
            sum += (sample * sample).toDouble()
        }
        if (samples == 0) return 0
        val rms = sqrt(sum / samples)
        val maxAmp = 32767.0
        val percentage = (rms / maxAmp * 100).toInt()
        return percentage.coerceIn(0, 100)
    }

    fun stop() {
        isRecording = false
        try {
            recordThread?.join(500)
            recordThread = null
        } catch (e: Throwable) {}

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Throwable) {}

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: Throwable) {}

        Log.d(TAG, "⏹ Đã dừng AAC Audio Encoder")
    }
}
