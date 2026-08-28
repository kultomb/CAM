package com.hbg.live.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.SurfaceHolder
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hbg.live.R
import com.hbg.live.capture.AudioSourceManager
import com.hbg.live.capture.CameraSourceManager
import com.hbg.live.databinding.ActivityMainBinding
import com.hbg.live.stream.AacAudioEncoder
import com.hbg.live.stream.H264Encoder
import com.hbg.live.stream.RtmpStreamerEngine
import java.nio.ByteBuffer

/**
 * Studio Controller - HBG LIVE CAMERA (Chuyên Nghiệp Đạt Chuẩn OBS / CameraFi Live)
 * Hỗ Trợ Phát Trực Tiếp Đan Luồng Kép (H.264 Video + AAC Audio) Lên Facebook Live & YouTube Live < 1 Giây.
 * Giao Diện Studio Tinh Gọn: Nhãn Thông Báo Trạng Thái Dạng Pill Siêu Nhỏ Gọn (Chữ 10sp) Nằm Ở Góc Dưới Bên Trái Màn Hình Preview Không Che Khuôn Mặt.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, 
    CameraSourceManager.CameraSourceListener, 
    AudioSourceManager.AudioSourceListener, 
    RtmpStreamerEngine.StreamStatsListener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraSourceManager: CameraSourceManager
    private lateinit var audioSourceManager: AudioSourceManager
    private lateinit var rtmpEngine: RtmpStreamerEngine
    private var h264Encoder: H264Encoder? = null
    private var aacAudioEncoder: AacAudioEncoder? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isLive = false
    private var isSourceStarted = false

    // Cấu hình Độ phân giải & Bitrate Studio
    private var streamWidth = 1280
    private var streamHeight = 720
    private var streamBitrate = 2500000 // 2.5 Mbps
    private var isAutoBitrate = true

    private var bannerHideRunnable: Runnable? = null

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
            showStudioSettingsMenu()
        }

        binding.btnSourceHdmi.setOnClickListener {
            val hasUsb = cameraSourceManager.getConnectedUsbCaptureDevice() != null
            binding.layoutNoSignal.visibility = if (hasUsb) View.GONE else View.VISIBLE
            binding.layoutQuickLensBar.visibility = View.GONE
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.HDMI_CAPTURE, binding.surfacePreview.holder)
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.HDMI_AUDIO)
        }

        binding.btnSourceBackCam.setOnClickListener {
            binding.layoutNoSignal.visibility = View.GONE
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)

            if (cameraSourceManager.currentSourceMode == CameraSourceManager.VideoSourceMode.PHONE_BACK) {
                val isCurrentlyVisible = binding.layoutQuickLensBar.visibility == View.VISIBLE
                binding.layoutQuickLensBar.visibility = if (isCurrentlyVisible) View.GONE else View.VISIBLE
            } else {
                cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_BACK, binding.surfacePreview.holder, zoomRatio = 1.0f)
                binding.layoutQuickLensBar.visibility = View.VISIBLE
            }
        }

        binding.btnSourceFrontCam.setOnClickListener {
            binding.layoutNoSignal.visibility = View.GONE
            binding.layoutQuickLensBar.visibility = View.GONE
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_FRONT, binding.surfacePreview.holder)
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)
        }

        // --- Nút Chuyển Góc Quay Ống Kính Nhanh 1-Chạm (Không Thông Báo Toast) ---
        binding.btnLensUltraWide.setOnClickListener {
            binding.layoutNoSignal.visibility = View.GONE
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_BACK, binding.surfacePreview.holder, zoomRatio = 0.5f)
        }

        binding.btnLensMain.setOnClickListener {
            binding.layoutNoSignal.visibility = View.GONE
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_BACK, binding.surfacePreview.holder, zoomRatio = 1.0f)
        }

        binding.btnLensTele.setOnClickListener {
            binding.layoutNoSignal.visibility = View.GONE
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_BACK, binding.surfacePreview.holder, zoomRatio = 3.0f)
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

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        requestRequiredPermissions()
        startStatsTicker()
    }

    private fun showStudioNotification(message: String, isError: Boolean = false) {
        runOnUiThread {
            binding.tvNotificationBanner.text = message
            binding.tvNotificationBanner.visibility = View.VISIBLE
            binding.tvNotificationBanner.setTextColor(
                if (isError) 0xFFFF4D4D.toInt() else 0xFF38BDF8.toInt()
            )
            bannerHideRunnable?.let { mainHandler.removeCallbacks(it) }
            val hideRunnable = Runnable {
                binding.tvNotificationBanner.visibility = View.GONE
            }
            bannerHideRunnable = hideRunnable
            mainHandler.postDelayed(hideRunnable, 4000)
        }
    }

    private fun showStudioSettingsMenu() {
        val currentRes = if (streamWidth == 1920) "Full HD 1080p" else "HD 720p"
        val currentBitrateStr = if (isAutoBitrate) "Auto (Theo Tốc Độ Mạng)" else "${streamBitrate / 1000000f} Mbps"

        val options = arrayOf(
            "📹 Độ phân giải Video: [$currentRes]",
            "⚡ Tốc độ Băng thông Bitrate: [$currentBitrateStr]",
            "🔍 Nhật ký Phần cứng (Debug Log)"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ CÀI ĐẶT STUDIO LIVESTREAM")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showResolutionDialog()
                    1 -> showBitrateDialog()
                    2 -> DebugLogDialog(this).show()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showResolutionDialog() {
        val resolutions = arrayOf("HD 720p (1280 x 720) - Ổn định", "Full HD 1080p (1920 x 1080) - Sắc nét")
        val selectedIndex = if (streamWidth == 1920) 1 else 0

        AlertDialog.Builder(this)
            .setTitle("📹 CHỌN ĐỘ PHÂN GIẢI VIDEO")
            .setSingleChoiceItems(resolutions, selectedIndex) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    applyNewResolution(1280, 720)
                } else {
                    applyNewResolution(1920, 1080)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun applyNewResolution(width: Int, height: Int) {
        streamWidth = width
        streamHeight = height

        if (width == 1920 && streamBitrate <= 2500000) {
            streamBitrate = 4500000
        } else if (width == 1280 && streamBitrate == 4500000) {
            streamBitrate = 2500000
        }

        val label = if (width == 1920) "1080p Full HD" else "720p HD"

        if (isLive) {
            h264Encoder?.stop()
            cameraSourceManager.h264Encoder = null

            val vEncoder = H264Encoder(
                width = streamWidth,
                height = streamHeight,
                bitrate = streamBitrate,
                frameRate = 30,
                listener = object : H264Encoder.Listener {
                    override fun onH264FrameAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                        rtmpEngine.sendVideoFrame(buffer, info)
                    }
                }
            )
            vEncoder.start()
            h264Encoder = vEncoder
            cameraSourceManager.h264Encoder = vEncoder

            val resLabel = if (streamWidth == 1920) "1080p" else "720p"
            binding.tvStatusBadge.text = "🔴 LIVE BROADCASTING ($resLabel)"
            showStudioNotification("🚀 ĐÃ CHUYỂN LUỒNG PHÁT SANG $label!")
        }
    }

    private fun showBitrateDialog() {
        val bitrateOptions = arrayOf(
            "⚡ Auto (Tự động thích ứng theo tốc độ mạng)",
            "🟢 2.5 Mbps (Tiêu chuẩn 720p)",
            "🟦 4.5 Mbps (Tiêu chuẩn 1080p Full HD)",
            "🟣 6.0 Mbps (Chất lượng cao 1080p60)",
            "✏️ Nhập Bitrate Tùy Chỉnh (Kbps)..."
        )
        val selectedIndex = when {
            isAutoBitrate -> 0
            streamBitrate == 2500000 -> 1
            streamBitrate == 4500000 -> 2
            streamBitrate == 6000000 -> 3
            else -> 4
        }

        AlertDialog.Builder(this)
            .setTitle("⚡ CHỌN BĂNG THÔNG BITRATE")
            .setSingleChoiceItems(bitrateOptions, selectedIndex) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> applyNewBitrate(2500000, true)
                    1 -> applyNewBitrate(2500000, false)
                    2 -> applyNewBitrate(4500000, false)
                    3 -> applyNewBitrate(6000000, false)
                    4 -> showCustomBitrateInputDialog()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun applyNewBitrate(bitrate: Int, autoMode: Boolean) {
        streamBitrate = bitrate
        isAutoBitrate = autoMode

        if (isLive) {
            h264Encoder?.stop()
            cameraSourceManager.h264Encoder = null

            val vEncoder = H264Encoder(
                width = streamWidth,
                height = streamHeight,
                bitrate = streamBitrate,
                frameRate = 30,
                listener = object : H264Encoder.Listener {
                    override fun onH264FrameAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                        rtmpEngine.sendVideoFrame(buffer, info)
                    }
                }
            )
            vEncoder.start()
            h264Encoder = vEncoder
            cameraSourceManager.h264Encoder = vEncoder

            val mbpsStr = "${streamBitrate / 1000000f} Mbps"
            showStudioNotification("⚡ ĐÃ ĐỔI BĂNG THÔNG BITRATE: $mbpsStr")
        }
    }

    private fun showCustomBitrateInputDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Nhập Bitrate (Kbps), ví dụ: 4500"
        }

        AlertDialog.Builder(this)
            .setTitle("✏️ NHẬP BITRATE TÙY CHỈNH (Kbps)")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val kbpsText = input.text.toString().trim()
                val kbps = kbpsText.toIntOrNull()
                if (kbps != null && kbps > 500) {
                    applyNewBitrate(kbps * 1000, false)
                } else {
                    showStudioNotification("⚠️ Giá trị Bitrate không hợp lệ!", isError = true)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun initCameraAndAudioOnce() {
        if (isSourceStarted) return
        isSourceStarted = true

        val usbDevice = cameraSourceManager.getConnectedUsbCaptureDevice()
        if (usbDevice != null) {
            onUsbCaptureCardAttached()
        } else {
            cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_BACK, binding.surfacePreview.holder)
            audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)
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
        binding.layoutQuickLensBar.visibility = View.GONE
        cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.HDMI_CAPTURE, binding.surfacePreview.holder)
        audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.HDMI_AUDIO)
    }

    private fun onUsbCaptureCardDetached() {
        binding.layoutNoSignal.visibility = View.GONE
        binding.layoutQuickLensBar.visibility = View.GONE
        com.hbg.live.util.StudioLogger.log("MainActivity", "⚠️ MẤT TÍN HIỆU USB CAPTURE CARD! Tự động quay về Cam Điện Thoại Sau...")
        cameraSourceManager.selectSourceMode(CameraSourceManager.VideoSourceMode.PHONE_BACK, binding.surfacePreview.holder)
        audioSourceManager.selectAudioMode(AudioSourceManager.AudioSourceMode.PHONE_MIC)
    }

    private fun updateVideoSourceButtonsUI(mode: CameraSourceManager.VideoSourceMode) {
        val activeColor = ContextCompat.getColor(this, R.color.accent_blue)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        val isHdmi = (mode == CameraSourceManager.VideoSourceMode.HDMI_CAPTURE)
        binding.btnSourceHdmi.setTextColor(if (isHdmi) activeColor else inactiveColor)
        binding.btnSourceHdmi.strokeColor = ContextCompat.getColorStateList(this, if (isHdmi) R.color.accent_blue else R.color.card_stroke)

        val isBack = (mode == CameraSourceManager.VideoSourceMode.PHONE_BACK)
        binding.btnSourceBackCam.setTextColor(if (isBack) activeColor else inactiveColor)
        binding.btnSourceBackCam.strokeColor = ContextCompat.getColorStateList(this, if (isBack) R.color.accent_blue else R.color.card_stroke)

        val isFront = (mode == CameraSourceManager.VideoSourceMode.PHONE_FRONT)
        binding.btnSourceFrontCam.setTextColor(if (isFront) activeColor else inactiveColor)
        binding.btnSourceFrontCam.strokeColor = ContextCompat.getColorStateList(this, if (isFront) R.color.accent_blue else R.color.card_stroke)

        val zoom = cameraSourceManager.currentZoomRatio
        val isUltraWide = isBack && (zoom < 0.8f)
        val isMain = isBack && (zoom in 0.8f..2.2f)
        val isTele = isBack && (zoom > 2.2f)

        binding.btnLensUltraWide.setTextColor(if (isUltraWide) activeColor else inactiveColor)
        binding.btnLensUltraWide.strokeColor = ContextCompat.getColorStateList(this, if (isUltraWide) R.color.accent_blue else R.color.card_stroke)

        binding.btnLensMain.setTextColor(if (isMain) activeColor else inactiveColor)
        binding.btnLensMain.strokeColor = ContextCompat.getColorStateList(this, if (isMain) R.color.accent_blue else R.color.card_stroke)

        binding.btnLensTele.setTextColor(if (isTele) activeColor else inactiveColor)
        binding.btnLensTele.strokeColor = ContextCompat.getColorStateList(this, if (isTele) R.color.accent_blue else R.color.card_stroke)

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

        val isHdmi = (mode == AudioSourceManager.AudioSourceMode.HDMI_AUDIO)
        binding.btnAudioHdmi.setTextColor(if (isHdmi) activeColor else inactiveColor)
        binding.btnAudioHdmi.strokeColor = ContextCompat.getColorStateList(this, if (isHdmi) R.color.accent_green else R.color.card_stroke)

        val isMic = (mode == AudioSourceManager.AudioSourceMode.PHONE_MIC)
        binding.btnAudioMic.setTextColor(if (isMic) activeColor else inactiveColor)
        binding.btnAudioMic.strokeColor = ContextCompat.getColorStateList(this, if (isMic) R.color.accent_green else R.color.card_stroke)
    }

    private fun toggleLiveStream() {
        if (!isLive) {
            startLiveStream()
        } else {
            stopLiveStream()
        }
    }

    private fun startLiveStream() {
        var url = binding.etRtmpUrl.text.toString().trim()
        val key = binding.etStreamKey.text.toString().trim()

        if (key.isEmpty()) {
            showStudioNotification("⚠️ Vui lòng nhập Stream Key của Facebook Live!", isError = true)
            return
        }

        if (key.startsWith("FB-") || url.contains("facebook.com")) {
            url = RtmpStreamerEngine.FACEBOOK_RTMPS_URL
            binding.etRtmpUrl.setText(url)
        } else if (url.isEmpty()) {
            url = RtmpStreamerEngine.YOUTUBE_RTMP_URL
        }

        // 1. Khởi tạo H.264 Video Encoder (Cấu hình linh hoạt 720p / 1080p & Bitrate tùy chọn)
        val vEncoder = H264Encoder(
            width = streamWidth, 
            height = streamHeight, 
            bitrate = streamBitrate, 
            frameRate = 30, 
            listener = object : H264Encoder.Listener {
                override fun onH264FrameAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                    rtmpEngine.sendVideoFrame(buffer, info)
                }
            }
        )
        vEncoder.start()
        h264Encoder = vEncoder
        cameraSourceManager.h264Encoder = vEncoder

        // 2. Khởi tạo AAC Audio Encoder (44.1 kHz Stereo @ 128 kbps) - Sử dụng Camcorder Directional Micro
        val targetAudioSource = if (audioSourceManager.currentAudioMode == AudioSourceManager.AudioSourceMode.HDMI_AUDIO) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.CAMCORDER
        }

        val aEncoder = AacAudioEncoder(
            sampleRate = 44100, 
            channelCount = 2, 
            bitrate = 128000, 
            audioSource = targetAudioSource,
            listener = object : AacAudioEncoder.Listener {
                override fun onAacFrameAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                    rtmpEngine.sendAudioFrame(buffer, info)
                }

                override fun onAudioLevelChanged(levelPercent: Int) {
                    runOnUiThread {
                        binding.vuMeterBar.progress = levelPercent
                    }
                }
            }
        )
        aEncoder.start()
        aacAudioEncoder = aEncoder

        rtmpEngine.startStream(url, key)
        isLive = true

        val resLabel = if (streamWidth == 1920) "1080p" else "720p"
        binding.btnToggleLive.text = "⏹  STOP LIVE"
        binding.btnToggleLive.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke))
        binding.tvStatusBadge.text = "🔴 LIVE BROADCASTING ($resLabel)"
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.live_red))
    }

    private fun stopLiveStream() {
        h264Encoder?.stop()
        h264Encoder = null
        cameraSourceManager.h264Encoder = null

        aacAudioEncoder?.stop()
        aacAudioEncoder = null

        rtmpEngine.stopStream()
        isLive = false

        binding.btnToggleLive.text = "▶  START LIVE"
        binding.btnToggleLive.setBackgroundColor(ContextCompat.getColor(this, R.color.live_red))
        binding.tvStatusBadge.text = "⚪ STANDBY"
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        showStudioNotification("⚪ ĐÃ DỪNG PHÁT TRỰC TIẾP")
    }

    private fun startStatsTicker() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isLive) {
                    rtmpEngine.tickStats()
                }
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
    override fun onConnected() {
        runOnUiThread {
            val resLabel = if (streamWidth == 1920) "1080p Full HD" else "720p HD"
            showStudioNotification("🟢 ĐÃ KẾT NỐI LUỒNG ($resLabel) THÀNH CÔNG LÊN FACEBOOK LIVE!")
        }
    }

    override fun onDisconnected() {}

    override fun onStatsUpdated(bitrateMbps: Float, uploadSpeedMbps: Float, fps: Float, droppedPercent: Float) {
        runOnUiThread {
            binding.tvBitrate.text = String.format("%.1f Mbps", bitrateMbps)
            binding.tvUploadSpeed.text = String.format("%.1f Mbps", uploadSpeedMbps)
            binding.tvFps.text = String.format("%.0f", fps)
            binding.tvDroppedFrames.text = String.format("%.1f%%", droppedPercent)
        }
    }

    override fun onError(errorMsg: String) {
        runOnUiThread {
            showStudioNotification("❌ $errorMsg", isError = true)
        }
    }

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
        h264Encoder?.stop()
        aacAudioEncoder?.stop()
        cameraSourceManager.stopAllSources()
        audioSourceManager.release()
        rtmpEngine.stopStream()
    }
}
