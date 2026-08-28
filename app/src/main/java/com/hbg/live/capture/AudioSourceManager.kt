package com.hbg.live.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.hbg.live.util.StudioLogger
import kotlin.math.sqrt

/**
 * Enterprise Audio Source Manager - HBG LIVE CAMERA
 * Hỗ trợ chuyển đổi linh hoạt nguồn Âm thanh Livestream:
 * 1. HDMI_AUDIO: Tiếng từ HDMI Capture Card / Bàn trộn hình qua USB Audio Class (UAC) 48kHz
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

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "AudioSourceManager"

        fun applyPreferredDevice(record: AudioRecord, device: AudioDeviceInfo): Boolean {
            return try {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    val method = AudioRecord::class.java.getMethod("setPreferredDevice", AudioDeviceInfo::class.java)
                    val result = method.invoke(record, device) as? Boolean
                    result ?: false
                } else false
            } catch (e: Throwable) {
                false
            }
        }
    }

    fun selectAudioMode(mode: AudioSourceMode) {
        currentAudioMode = mode
        stopRecording()
        startRecordingForMode(mode)
        listener.onAudioSourceChanged(mode)
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingForMode(mode: AudioSourceMode) {
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val sampleRatesToTry = intArrayOf(48000, 44100)
        val sourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        val usbAudioDevice: AudioDeviceInfo? = if (mode == AudioSourceMode.HDMI_AUDIO && android.os.Build.VERSION.SDK_INT >= 23) {
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            inputDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BUS
            }
        } else null

        var record: AudioRecord? = null

        for (sr in sampleRatesToTry) {
            val minBufferSize = AudioRecord.getMinBufferSize(sr, channelConfig, audioFormat)
            if (minBufferSize <= 0) continue
            val bufferSize = maxOf(minBufferSize, 8192)

            for (src in sourcesToTry) {
                try {
                    val r = AudioRecord(src, sr, channelConfig, audioFormat, bufferSize)
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        record = r
                        if (usbAudioDevice != null) {
                            val ok = applyPreferredDevice(r, usbAudioDevice)
                            if (ok) {
                                StudioLogger.log(TAG, "🟢 Khóa cứng thiết bị thu âm USB Audio Class (UAC) Capture Card: ${usbAudioDevice.productName} ($sr Hz)")
                            }
                        }
                        break
                    } else {
                        r.release()
                    }
                } catch (e: Throwable) {}
            }
            if (record != null) break
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
                val buffer = ByteArray(4096)

                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val level = calculatePcmLevel(buffer, read)
                        listener.onAudioLevelChanged(level)
                    }
                }
            }.apply {
                name = "AudioSourceRecordThread"
                start()
            }

            StudioLogger.log(TAG, "🟢 AudioSourceManager Khởi Động Thu Âm Chế Độ: ${mode.displayName}")

        } catch (e: Throwable) {
            StudioLogger.log(TAG, "❌ Lỗi startRecordingForMode: ${e.message}")
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

    fun stopRecording() {
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
    }

    fun release() {
        stopRecording()
    }
}
