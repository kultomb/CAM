package com.hbg.live.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.hbg.live.util.StudioLogger
import kotlin.math.sqrt

/**
 * Enterprise Audio Source Manager - HBG LIVE CAMERA
 * Hỗ trợ chuyển đổi linh hoạt nguồn Âm thanh Livestream:
 * 1. HDMI_AUDIO: Tiếng từ HDMI Capture Card / Bàn trộn hình qua USB Audio Class (UAC)
 * 2. PHONE_MIC: Tiếng từ Micro Định Hướng Camcorder Điện Thoại
 */
class AudioSourceManager(
    private val context: Context,
    private val sampleRate: Int = 44100,
    private val listener: AudioSourceListener
) {
    enum class AudioSourceMode(val displayName: String) {
        HDMI_AUDIO("Âm thanh CAM HDMI"),
        PHONE_MIC("Micro Điện Thoại")
    }

    interface AudioSourceListener {
        fun onAudioSourceChanged(mode: AudioSourceMode)
        fun onAudioLevelChanged(levelPercent: Int)
    }

    var currentAudioMode: AudioSourceMode = AudioSourceMode.PHONE_MIC
        private set

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordThread: Thread? = null

    companion object {
        private const val TAG = "AudioSourceManager"
    }

    fun selectAudioMode(mode: AudioSourceMode) {
        currentAudioMode = mode
        stopRecording()
        startRecordingForMode(mode)
        listener.onAudioSourceChanged(mode)
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingForMode(mode: AudioSourceMode) {
        val preferredSource = when (mode) {
            AudioSourceMode.HDMI_AUDIO -> MediaRecorder.AudioSource.UNPROCESSED
            AudioSourceMode.PHONE_MIC -> MediaRecorder.AudioSource.CAMCORDER
        }

        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 4096)

        val sourcesToTry = intArrayOf(
            preferredSource,
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
                    audioFormat,
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
            StudioLogger.log(TAG, "❌ Không thể khởi tạo AudioRecord với bất kỳ nguồn âm thanh nào!")
            return
        }

        try {
            audioRecord = record
            audioRecord?.startRecording()
            isRecording = true

            recordThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ShortArray(1024)

                while (isRecording) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        val level = calculateRmsLevelPercent(buffer, readCount)
                        listener.onAudioLevelChanged(level)
                    }
                }
            }.apply {
                name = "StudioAudioRecordThread"
                start()
            }

            StudioLogger.log(TAG, "► ĐÃ MỞ NGUỒN ÂM THANH: ${mode.displayName}")
        } catch (e: Throwable) {
            StudioLogger.log(TAG, "❌ Lỗi khởi tạo AudioRecord (${mode.displayName})", e)
        }
    }

    private fun calculateRmsLevelPercent(buffer: ShortArray, readCount: Int): Int {
        var sum = 0.0
        for (i in 0 until readCount) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / readCount)
        val maxAmp = 32767.0
        val percent = ((rms / maxAmp) * 100 * 3.5).toInt()
        return percent.coerceIn(0, 100)
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Throwable) {}
        audioRecord = null
        recordThread = null
    }

    fun release() {
        stopRecording()
    }
}
