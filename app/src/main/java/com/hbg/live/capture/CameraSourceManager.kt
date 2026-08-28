package com.hbg.live.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.hbg.live.stream.H264Encoder
import com.hbg.live.util.StudioLogger

/**
 * Engine Quản lý Nguồn Video Tập Trung - Hỗ Trợ Đầy Đủ Multi-Camera API & Chuyển Ống Kính Vật Lý 0.5x Ultra-Wide, 1x Main, 2x, 3x, 5x Telephoto.
 */
class CameraSourceManager(
    private val context: Context,
    private val listener: CameraSourceListener
) : UvcOfficialEngine.UvcOfficialListener {

    data class BackLensOption(
        val label: String,
        val zoomRatio: Float,
        val physicalId: String? = null
    )

    enum class VideoSourceMode(var displayName: String) {
        HDMI_CAPTURE("CAM HDMI"),
        PHONE_BACK("Camera Sau Điện Thoại"),
        PHONE_FRONT("Camera Trước Điện Thoại")
    }

    interface CameraSourceListener {
        fun onSourceChanged(mode: VideoSourceMode, description: String)
        fun onFrameReceived(fps: Float)
        fun onError(errorMsg: String)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    var currentSourceMode: VideoSourceMode = VideoSourceMode.PHONE_BACK
        private set
    var activeCameraId: String? = null
        private set
    var currentZoomRatio: Float = 1.0f
        private set

    // Camera2 API Variables (Cam điện thoại)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Engine UVC Nguồn Mở Chuẩn Quốc Tế
    private var uvcOfficialEngine: UvcOfficialEngine = UvcOfficialEngine(context, this)

    var h264Encoder: H264Encoder? = null
        set(value) {
            field = value
            uvcOfficialEngine.h264Encoder = value
            activeSurfaceHolder?.let { holder ->
                if (currentSourceMode == VideoSourceMode.PHONE_BACK || currentSourceMode == VideoSourceMode.PHONE_FRONT) {
                    cameraDevice?.let { camera ->
                        createCameraPreviewSession(camera, holder)
                    }
                }
            }
        }

    fun getUvcEngine(): UvcOfficialEngine = uvcOfficialEngine

    private var isOpening = false
    private var activeSurfaceHolder: SurfaceHolder? = null

    companion object {
        private const val TAG = "CameraSourceManager"
        const val ACTION_USB_PERMISSION = "com.hbg.live.ACTION_USB_PERMISSION"
    }

    fun getBackLensOptions(): List<BackLensOption> {
        val options = ArrayList<BackLensOption>()
        options.add(BackLensOption("📷 0.5x - Góc Siêu Rộng (Ultra-Wide)", 0.5f))
        options.add(BackLensOption("📷 1.0x - Camera Chính (Main Lens)", 1.0f))
        options.add(BackLensOption("📷 2.0x - Zoom Chân Dung (Portrait 2x)", 2.0f))
        options.add(BackLensOption("📷 3.0x - Camera Telephoto (Zoom 3x)", 3.0f))
        options.add(BackLensOption("📷 5.0x - Camera Siêu Tele (Zoom 5x)", 5.0f))

        try {
            val mainCharacteristics = cameraManager.getCameraCharacteristics("0")
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val physicalIds = mainCharacteristics.physicalCameraIds
                for (pId in physicalIds) {
                    options.add(BackLensOption("🔍 Mắt Camera Vật Lý ID [$pId]", 1.0f, pId))
                }
            }
        } catch (e: Throwable) {}

        return options
    }

    fun getConnectedUsbCaptureDevice(): UsbDevice? {
        val deviceList = try { usbManager.deviceList } catch (e: Throwable) { return null }
        for (device in deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 14 && iface.interfaceSubclass == 2) {
                    val vidHex = Integer.toHexString(device.vendorId)
                    val pidHex = Integer.toHexString(device.productId)
                    val dynamicName = if (usbManager.hasPermission(device)) {
                        try { device.productName ?: "USB Capture (0x$vidHex:0x$pidHex)" } catch (_: Throwable) { "CAM HDMI" }
                    } else {
                        "CAM HDMI (0x$vidHex:0x$pidHex)"
                    }
                    VideoSourceMode.HDMI_CAPTURE.displayName = dynamicName
                    return device
                }
            }
        }
        return null
    }

    fun selectSourceMode(mode: VideoSourceMode, holder: SurfaceHolder, targetCameraId: String? = null, zoomRatio: Float = 1.0f) {
        activeSurfaceHolder = holder
        currentSourceMode = mode
        currentZoomRatio = zoomRatio
        stopAllSources()

        when (mode) {
            VideoSourceMode.HDMI_CAPTURE -> {
                val usbDevice = getConnectedUsbCaptureDevice()
                if (usbDevice != null) {
                    if (!usbManager.hasPermission(usbDevice)) {
                        StudioLogger.log(TAG, "Chưa có quyền USB Permission. Đang kích hoạt Popup hệ thống xin quyền truy cập USB Video...")
                        val permissionIntent = android.app.PendingIntent.getBroadcast(
                            context, 0, android.content.Intent(ACTION_USB_PERMISSION),
                            if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
                        )
                        usbManager.requestPermission(usbDevice, permissionIntent)
                        listener.onSourceChanged(mode, "Đang chờ cấp quyền USB")
                        return
                    }
                    StudioLogger.log(TAG, "► KHỞI CHẠY KHUNG HÌNH UVCCAMERA NATIVE ENGINE...")
                    uvcOfficialEngine.startStream(usbDevice, holder)
                    listener.onSourceChanged(mode, mode.displayName)
                } else {
                    StudioLogger.log(TAG, "Chưa cắm USB Capture Card")
                    listener.onError("Không tìm thấy thiết bị USB Capture Card!")
                }
            }
            VideoSourceMode.PHONE_BACK -> {
                StudioLogger.log(TAG, "Chuyển Nguồn Video -> Camera Sau Điện Thoại (${zoomRatio}x)")
                resetSurfaceFormatForCamera2(holder)
                openPhoneCamera(CameraCharacteristics.LENS_FACING_BACK, holder, targetCameraId, zoomRatio)
            }
            VideoSourceMode.PHONE_FRONT -> {
                StudioLogger.log(TAG, "Chuyển Nguồn Video -> Camera Trước Điện Thoại")
                resetSurfaceFormatForCamera2(holder)
                openPhoneCamera(CameraCharacteristics.LENS_FACING_FRONT, holder, targetCameraId, 1.0f)
            }
        }
    }

    private fun resetSurfaceFormatForCamera2(holder: SurfaceHolder) {
        try {
            holder.setFormat(PixelFormat.TRANSPARENT)
            holder.setFormat(PixelFormat.OPAQUE)
        } catch (e: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun openPhoneCamera(facing: Int, holder: SurfaceHolder, targetCameraId: String? = null, zoomRatio: Float = 1.0f) {
        if (isOpening) return
        isOpening = true

        startBackgroundThread()

        try {
            var selectedId: String? = targetCameraId
            if (selectedId == null) {
                val ids = cameraManager.cameraIdList
                for (id in ids) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                        selectedId = id
                        break
                    }
                }
            }

            if (selectedId == null) {
                isOpening = false
                listener.onError("Không tìm thấy Camera Điện thoại")
                return
            }

            activeCameraId = selectedId
            val facingLabel = if (facing == CameraCharacteristics.LENS_FACING_BACK) "Cam Sau (${zoomRatio}x)" else "Cam Trước"

            StudioLogger.log(TAG, "Mở Camera Điện Thoại '$facingLabel' ID $selectedId...")

            cameraManager.openCamera(selectedId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    isOpening = false
                    cameraDevice = camera
                    createCameraPreviewSession(camera, holder, zoomRatio)
                    val modeName = if (facing == CameraCharacteristics.LENS_FACING_BACK) VideoSourceMode.PHONE_BACK else VideoSourceMode.PHONE_FRONT
                    listener.onSourceChanged(modeName, facingLabel)
                    StudioLogger.log(TAG, "► ĐÃ HIỂN THỊ THÀNH CÔNG PREVIEW $facingLabel ID '$selectedId'!")
                }

                override fun onDisconnected(camera: CameraDevice) {
                    isOpening = false
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    isOpening = false
                    camera.close()
                    cameraDevice = null
                    listener.onError("Lỗi mở Camera ID $selectedId: $error")
                }
            }, backgroundHandler)

        } catch (e: Throwable) {
            isOpening = false
            StudioLogger.log(TAG, "Lỗi openPhoneCamera", e)
            listener.onError("Lỗi Camera2: ${e.message}")
        }
    }

    private fun createCameraPreviewSession(camera: CameraDevice, holder: SurfaceHolder, zoomRatio: Float = currentZoomRatio) {
        try {
            val surface = holder.surface
            if (surface == null || !surface.isValid) {
                return
            }

            val targets = mutableListOf<Surface>(surface)
            val encoderSurface = h264Encoder?.getInputSurface()
            if (encoderSurface != null && encoderSurface.isValid) {
                targets.add(encoderSurface)
            }

            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            for (t in targets) {
                previewRequestBuilder.addTarget(t)
            }

            // Thiết lập tỷ lệ Zoom Ratio / Ống kính vật lý (0.5x Ultra-Wide, 1.0x Main, 3.0x Telephoto)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    previewRequestBuilder.set(android.hardware.camera2.CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                } catch (e: Throwable) {}
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        previewRequestBuilder.set(
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                    } catch (e: Throwable) {
                        StudioLogger.log(TAG, "❌ Lỗi thiết lập RepeatingRequest: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    listener.onError("Cấu hình Preview Session thất bại!")
                }
            }, backgroundHandler)
        } catch (e: Throwable) {
            StudioLogger.log(TAG, "Lỗi createCameraPreviewSession", e)
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackgroundThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Throwable) {}
    }

    fun stopAllSources() {
        try {
            uvcOfficialEngine.stopStream()
        } catch (e: Throwable) {}

        try {
            captureSession?.close()
            captureSession = null
        } catch (e: Throwable) {}

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Throwable) {}

        stopBackgroundThread()
    }

    override fun onFrameFps(fps: Float) {
        listener.onFrameReceived(fps)
    }

    override fun onError(errorMsg: String) {
        listener.onError(errorMsg)
    }
}
