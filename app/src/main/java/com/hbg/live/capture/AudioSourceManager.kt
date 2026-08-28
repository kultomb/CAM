package com.hbg.live.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Engine Quản lý Nguồn Âm Thanh Tập Trung (Audio Source Manager)
 * Hỗ trợ 2 Chế độ Nguồn Âm thanh độc lập:
 * 1. HDMI_AUDIO: Tiếng từ HDMI Capture Card (Sony A73 / Bàn trộn hình qua USB Audio Class UAC)
 * 2. PHONE_MIC: Micro tích hợp / Tai nghe điện thoại
 */
class AudioSourceManager(
    private val context: Context,
    private val sampleRate: Int = 44100,
    private val listener: AudioSourceListener
) {
    enum class AudioSourceMode(val displayName: String) {
        HDMI_AUDIO("HDMI / USB Audio (Sony A73)"),
        PHONE_MIC("Micro Điện Thoại / Tai Nghe")
    }

    interface AudioSourceListener {
        fun onAudioSourceChanged(mode: AudioSourceMode)
        fun onAudioLevelChanged(levelPercent: Int)
        fun onError(errorMsg: String)
    }

    var currentAudioMode: AudioSourceMode = AudioSourceMode.PHONE_MIC
        private set

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    companion object {
        private const val TAG = "AudioSourceManager"
    }

    /**
     * Chuyển đổi nguồn âm thanh giữa HDMI Audio và Mic Điện thoại
     */
    fun selectAudioMode(mode: AudioSourceMode) {
        if (currentAudioMode == mode && isRecording) return

        Log.d(TAG, "Chuyển đổi Nguồn Âm Thanh sang: ${mode.displayName}")
        stopAudio()

        currentAudioMode = mode
        listener.onAudioSourceChanged(mode)
        startAudio()
    }

    @SuppressLint("MissingPermission")
    fun startAudio() {
        if (isRecording) return

        val audioSource = when (currentAudioMode) {
            AudioSourceMode.HDMI_AUDIO -> MediaRecorder.AudioSource.UNPROCESSED
            AudioSourceMode.PHONE_MIC -> MediaRecorder.AudioSource.MIC
        }

        try {
            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                val buffer = ShortArray(minBufferSize)
                while (isRecording) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        val rms = calculateRms(buffer, readSize)
                        val percent = (rms / 32767.0 * 100).toInt().coerceIn(0, 100)
                        listener.onAudioLevelChanged(percent)
                    }
                }
            }.apply { start() }

            Log.d(TAG, "Đã khởi chạy Audio Engine: ${currentAudioMode.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi thu âm PCM", e)
            listener.onError("Lỗi thu âm: ${e.message}")
        }
    }

    private fun calculateRms(buffer: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i] * buffer[i]
        }
        return sqrt(sum / readSize)
    }

    fun stopAudio() {
        isRecording = false
        recordingThread?.join(500)
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi dừng Audio", e)
        }
        Log.d(TAG, "Đã dừng Audio Engine")
    }

    fun release() {
        stopAudio()
    }
}
