package com.hbg.live.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hbg.live.R
import com.hbg.live.capture.AudioSourceManager
import com.hbg.live.capture.CameraSourceManager
import com.hbg.live.databinding.ActivityMainBinding
import com.hbg.live.stream.RtmpStreamerEngine

/**
 * Studio Controller - HBG LIVE CAMERA
 * Ứng dụng Livestream Đa Nguồn Chuyên Nghiệp:
 * - Hỗ trợ phát trực tiếp 100% bằng Camera Điện Thoại (Cam Sau / Cam Trước) khi không cắm Capture Card.
 * - Ẩn 100% lớp phủ thông báo khi dùng Cam Điện Thoại.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, 
    CameraSourceManager.CameraSourceListener, 
    AudioSourceManager.AudioSourceListener, 
    RtmpStreamerEngine.StreamStatsListener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraSourceManager: CameraSourceManager
    private lateinit var audioSourceManager: AudioSourceManager
    private lateinit var rtmpEngine: RtmpStreamerEngine

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isLive = false
    private var isSourceStarted = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    onUsbCaptureCardAttached()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    onUsbCaptureCardDetached()
                }
                CameraSourceManager.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        onUsbCaptureCardAttached()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraSourceManager = CameraSourceManager(this, this)
        audioSourceManager = AudioSourceManager(this, 44100, this)
        rtmpEngine = RtmpStreamerEngine(this)

        binding.surfacePreview.holder.addCallback(this)

        binding.btnSettings.setOnClickListener {
            val options = arrayOf("🔍 Nhật ký Phần cứng (Debug Log)", "📹 Thông số H.264 Encoder (1080p60)")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚙️ CÀI ĐẶT STUDIO")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> DebugLogDialog(this).show()
                    }
                }
                .setNegativeButton("Đóng", null)
                .show()
        }

        binding.btnSourceHdmi.setOnClickListener {
            val hasUsb = cameraSourceManager.getConnectedUsbCaptureDevice() != null
            binding.layoutNoSignal.visibility = if (hasUsb) View.GONE else View.VISIBLE
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.HDMI_CAPTURE, binding.surfacePreview.holder)
        }

        binding.btnSourceBackCam.setOnClickListener {
            binding.layoutNoSignal.visibility = View.GONE
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_BACK, binding.surfacePreview.holder)
        }

        binding.btnSourceFrontCam.setOnClickListener {
            binding.layoutNoSignal.visibility = View.GONE
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_FRONT, binding.surfacePreview.holder)
        }

        binding.btnAudioHdmi.setOnClickListener {
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.HDMI_AUDIO)
        }

        binding.btnAudioMic.setOnClickListener {
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)
        }

        binding.btnPresetYoutube.setOnClickListener {
            binding.etRtmpUrl.setText(RtmpStreamerEngine.YOUTUBE_RTMP_URL)
        }

        binding.btnPresetFacebook.setOnClickListener {
            binding.etRtmpUrl.setText(RtmpStreamerEngine.FACEBOOK_RTMPS_URL)
        }

        binding.btnToggleLive.setOnClickListener {
            toggleLiveStream()
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(CameraSourceManager.ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        requestRequiredPermissions()
        startStatsTicker()
    }

    private fun initCameraAndAudioOnce() {
        if (isSourceStarted) return
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val surfaceValid = binding.surfacePreview.holder.surface?.isValid == true

        if (hasCam && hasAudio && surfaceValid) {
            isSourceStarted = true
            cameraSourceManager.autoDetectAndSelectSource(binding.surfacePreview.holder)
            audioSourceManager.startAudio()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions.add("android.permission.POST_NOTIFICATIONS")
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            initCameraAndAudioOnce()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initCameraAndAudioOnce()
        }
    }

    private fun onUsbCaptureCardAttached() {
        binding.layoutNoSignal.visibility = View.GONE
        cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.HDMI_CAPTURE, binding.surfacePreview.holder)
        audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.HDMI_AUDIO)
    }

    private fun onUsbCaptureCardDetached() {
        binding.layoutNoSignal.visibility = View.GONE
        com.hbg.live.util.StudioLogger.log("MainActivity", "⚠️ MẤT TÍN HIỆU USB CAPTURE CARD! Tự động quay về Cam Điện Thoại Sau...")
        cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_BACK, binding.surfacePreview.holder)
        audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)
    }

    private fun updateVideoSourceButtonsUI(mode: CameraSourceManager.VideoSourceMode) {
        val activeColor = ContextCompat.getColor(this, R.color.accent_blue)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.btnSourceHdmi.setTextColor(if (mode == CameraSourceManager.VideoSourceMode.HDMI_CAPTURE) activeColor else inactiveColor)
        binding.btnSourceHdmi.strokeColor = ContextCompat.getColorStateList(this, if (mode == CameraSourceManager.VideoSourceMode.HDMI_CAPTURE) R.color.accent_blue else R.color.card_stroke)

        binding.btnSourceBackCam.setTextColor(if (mode == CameraSourceManager.VideoSourceMode.PHONE_BACK) activeColor else inactiveColor)
        binding.btnSourceBackCam.strokeColor = ContextCompat.getColorStateList(this, if (mode == CameraSourceManager.VideoSourceMode.PHONE_BACK) R.color.accent_blue else R.color.card_stroke)

        binding.btnSourceFrontCam.setTextColor(if (mode == CameraSourceManager.VideoSourceMode.PHONE_FRONT) activeColor else inactiveColor)
        binding.btnSourceFrontCam.strokeColor = ContextCompat.getColorStateList(this, if (mode == CameraSourceManager.VideoSourceMode.PHONE_FRONT) R.color.accent_blue else R.color.card_stroke)

        // Ẩn 100% lớp phủ No Signal khi sử dụng Camera Điện Thoại
        if (mode == CameraSourceManager.VideoSourceMode.PHONE_BACK || mode == CameraSourceManager.VideoSourceMode.PHONE_FRONT) {
            binding.layoutNoSignal.visibility = View.GONE
        } else {
            val hasUsb = cameraSourceManager.getConnectedUsbCaptureDevice() != null
            binding.layoutNoSignal.visibility = if (hasUsb) View.GONE else View.VISIBLE
        }
    }

    private fun updateAudioSourceButtonsUI(mode: AudioSourceManager.AudioSourceMode) {
        val activeColor = ContextCompat.getColor(this, R.color.accent_green)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.btnAudioHdmi.setTextColor(if (mode == AudioSourceManager.AudioSourceMode.HDMI_AUDIO) activeColor else inactiveColor)
        binding.btnAudioHdmi.strokeColor = ContextCompat.getColorStateList(this, if (mode == AudioSourceManager.AudioSourceMode.HDMI_AUDIO) R.color.accent_green else R.color.card_stroke)

        binding.btnAudioMic.setTextColor(if (mode == AudioSourceManager.AudioSourceMode.PHONE_MIC) activeColor else inactiveColor)
        binding.btnAudioMic.strokeColor = ContextCompat.getColorStateList(this, if (mode == AudioSourceManager.AudioSourceMode.PHONE_MIC) R.color.accent_green else R.color.card_stroke)
    }

    private fun toggleLiveStream() {
        if (!isLive) {
            startLiveStream()
        } else {
            stopLiveStream()
        }
    }

    private fun startLiveStream() {
        val url = binding.etRtmpUrl.text.toString().ifEmpty { getString(R.string.default_rtmp_url) }
        val key = binding.etStreamKey.text.toString()

        rtmpEngine.startStream(url, key)
        isLive = true

        binding.btnToggleLive.text = "⏹  STOP LIVE"
        binding.btnToggleLive.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke))
        binding.tvStatusBadge.text = "🔴 LIVE BROADCASTING"
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.live_red))
    }

    private fun stopLiveStream() {
        rtmpEngine.stopStream()
        isLive = false

        binding.btnToggleLive.text = "▶  START LIVE"
        binding.btnToggleLive.setBackgroundColor(ContextCompat.getColor(this, R.color.live_red))
        binding.tvStatusBadge.text = "⚪ STANDBY"
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun startStatsTicker() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                mainHandler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    // --- CameraSourceListener Callbacks ---
    override fun onSourceChanged(mode: CameraSourceManager.VideoSourceMode, description: String) {
        runOnUiThread {
            updateVideoSourceButtonsUI(mode)
        }
    }

    override fun onFrameReceived(fps: Float) {
        runOnUiThread {
            binding.tvFps.text = String.format("%.0f", fps)
        }
    }

    // --- AudioSourceListener Callbacks ---
    override fun onAudioSourceChanged(mode: AudioSourceManager.AudioSourceMode) {
        runOnUiThread {
            updateAudioSourceButtonsUI(mode)
        }
    }

    override fun onAudioLevelChanged(levelPercent: Int) {
        runOnUiThread {
            binding.vuMeterBar.progress = levelPercent
        }
    }

    // --- RtmpStreamerEngine Callbacks ---
    override fun onConnected() {}

    override fun onDisconnected() {}

    override fun onStatsUpdated(bitrateMbps: Float, uploadSpeedMbps: Float, fps: Float, droppedPercent: Float) {
        runOnUiThread {
            binding.tvBitrate.text = String.format("%.1f Mbps", bitrateMbps)
            binding.tvUploadSpeed.text = String.format("%.1f Mbps", uploadSpeedMbps)
            binding.tvFps.text = String.format("%.0f", fps)
            binding.tvDroppedFrames.text = String.format("%.1f%%", droppedPercent)
        }
    }

    override fun onError(errorMsg: String) {}

    // --- SurfaceHolder Callbacks ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        initCameraAndAudioOnce()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        cameraSourceManager.stopAllSources()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Throwable) {}
        cameraSourceManager.stopAllSources()
        audioSourceManager.release()
        rtmpEngine.stopStream()
    }
}
