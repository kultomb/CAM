package com.hbg.live.stream

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
 * Enterprise AAC Audio Encoder (44.1/48 kHz Stereo / 128 kbps AAC-LC) cho Facebook Live & YouTube Live.
 * Hỗ trợ tự động khóa cứng cổng vào USB Audio Class (UAC) từ HDMI Capture Card / Camcorder Microphone.
 */
class AacAudioEncoder(
    private val context: Context? = null,
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2,
    private val bitrate: Int = 128000,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
    private val isHdmiAudioMode: Boolean = false,
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
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val usbAudioDevice: AudioDeviceInfo? = if (isHdmiAudioMode && android.os.Build.VERSION.SDK_INT >= 23 && audioManager != null) {
                val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                inputDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BUS
                }
            } else null

            val sampleRatesToTry = intArrayOf(sampleRate, 48000, 44100)
            val sourcesToTry = intArrayOf(
                audioSource,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.DEFAULT
            )

            var record: AudioRecord? = null
            var actualSampleRate = sampleRate

            for (sr in sampleRatesToTry) {
                val minBufferSize = AudioRecord.getMinBufferSize(sr, channelConfig, audioFormat)
                if (minBufferSize <= 0) continue
                val bufferSize = maxOf(minBufferSize, 8192)

                for (src in sourcesToTry) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 23) {
                            val builder = AudioRecord.Builder()
                                .setAudioSource(src)
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(audioFormat)
                                        .setSampleRate(sr)
                                        .setChannelMask(channelConfig)
                                        .build()
                                )
                                .setBufferSizeInBytes(bufferSize)

                            if (usbAudioDevice != null) {
                                builder.setPreferredDevice(usbAudioDevice)
                            }

                            val r = builder.build()
                            if (r.state == AudioRecord.STATE_INITIALIZED) {
                                record = r
                                actualSampleRate = sr
                                if (usbAudioDevice != null) {
                                    r.setPreferredDevice(usbAudioDevice)
                                    Log.d(TAG, "🟢 AacAudioEncoder khóa cứng USB Audio Class (UAC) HDMI Capture: ${usbAudioDevice.productName} ($sr Hz)")
                                }
                                break
                            } else {
                                r.release()
                            }
                        } else {
                            val r = AudioRecord(src, sr, channelConfig, audioFormat, bufferSize)
                            if (r.state == AudioRecord.STATE_INITIALIZED) {
                                record = r
                                actualSampleRate = sr
                                break
                            } else {
                                r.release()
                            }
                        }
                    } catch (e: Throwable) {}
                }
                if (record != null) break
            }

            if (record == null) {
                Log.e(TAG, "❌ Không thể mở bất kỳ nguồn AudioRecord nào!")
                return
            }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, actualSampleRate, channelCount)
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

            Log.d(TAG, "🟢 AAC Audio Encoder Khởi Động Thành Công ($actualSampleRate Hz Stereo @ 128 kbps)")
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
