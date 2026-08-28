package com.hbg.live.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Quản lý thu âm thanh PCM từ USB Audio Class (UAC) hoặc Mic/Line-in và tính toán VU Meter.
 */
class UacAudioRecorder(
    private val sampleRate: Int = 44100,
    private val onAudioLevelListener: (levelPercent: Int) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
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
                        onAudioLevelListener(percent)
                    }
                }
            }.apply { start() }

            Log.d(TAG, "Đã khởi chạy UAC Audio Recorder ($sampleRate Hz Stereo)")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi khởi tạo AudioRecord", e)
        }
    }

    private fun calculateRms(buffer: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i] * buffer[i]
        }
        return sqrt(sum / readSize)
    }

    fun stop() {
        isRecording = false
        recordingThread?.join(500)
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi dừng AudioRecord", e)
        }
        Log.d(TAG, "Đã dừng Audio Recorder")
    }

    companion object {
        private const val TAG = "UacAudioRecorder"
    }
}
