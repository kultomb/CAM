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
import android.view.SurfaceHolder
import com.hbg.live.util.StudioLogger

/**
 * Engine Quản lý Nguồn Video Tập Trung - Tự Động Nhận Dạng Mọi Thiết Bị USB Capture Card Động (Chuẩn CameraFi Live)
 * An toàn 100% tránh ANR IPC Binder block trên Main UI Thread.
 */
class CameraSourceManager(
    private val context: Context,
    private val listener: CameraSourceListener
) : UvcOfficialEngine.UvcOfficialListener {

    enum class VideoSourceMode(var displayName: String) {
        HDMI_CAPTURE("USB HDMI Capture Device"),
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

    // Camera2 API Variables (Cam điện thoại)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Engine UVC Nguồn Mở Chuẩn Quốc Tế
    private var uvcOfficialEngine: UvcOfficialEngine = UvcOfficialEngine(context, this)

    private var isOpening = false
    private var activeSurfaceHolder: SurfaceHolder? = null

    companion object {
        private const val TAG = "CameraSourceManager"
        const val ACTION_USB_PERMISSION = "com.hbg.live.ACTION_USB_PERMISSION"
    }

    /**
     * Tự động quét và lấy thiết bị USB Capture Card đang cắm trên máy một cách động 100%
     * AN TOÀN IPC BINDER: Tuyệt đối không gọi device.productName khi chưa có permission để tránh ANR.
     */
    fun getConnectedUsbCaptureDevice(): UsbDevice? {
        val deviceList = try { usbManager.deviceList } catch (e: Throwable) { return null }
        for (device in deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 14 && iface.interfaceSubclass == 2) {
                    val vidHex = Integer.toHexString(device.vendorId)
                    val pidHex = Integer.toHexString(device.productId)
                    val dynamicName = if (usbManager.hasPermission(device)) {
                        try { device.productName ?: "USB Capture (0x$vidHex:0x$pidHex)" } catch (_: Throwable) { "USB Capture Card" }
                    } else {
                        "USB Capture (0x$vidHex:0x$pidHex)"
                    }
                    VideoSourceMode.HDMI_CAPTURE.displayName = dynamicName
                    return device
                }
            }
        }
        return null
    }

    fun autoDetectAndSelectSource(holder: SurfaceHolder) {
        val usbDevice = getConnectedUsbCaptureDevice()
        if (usbDevice != null) {
            val name = VideoSourceMode.HDMI_CAPTURE.displayName
            StudioLogger.log(TAG, "🟢 Đã phát hiện thiết bị USB: $name (VID: 0x${Integer.toHexString(usbDevice.vendorId)}, PID: 0x${Integer.toHexString(usbDevice.productId)})")
            selectSourceMode(VideoSourceMode.HDMI_CAPTURE, holder)
        } else {
            StudioLogger.log(TAG, "Chưa phát hiện thiết bị USB Capture Card nào. Chuyển mặc định Cam Sau Điện Thoại.")
            selectSourceMode(VideoSourceMode.PHONE_BACK, holder)
        }
    }

    fun selectSourceMode(mode: VideoSourceMode, holder: SurfaceHolder) {
        activeSurfaceHolder = holder
        currentSourceMode = mode
        stopAllSources()

        when (mode) {
            VideoSourceMode.HDMI_CAPTURE -> {
                val usbDevice = getConnectedUsbCaptureDevice()
                if (usbDevice != null) {
                    val name = VideoSourceMode.HDMI_CAPTURE.displayName
                    StudioLogger.log(TAG, "Chuyển Nguồn Video -> $name")
                    if (!usbManager.hasPermission(usbDevice)) {
                        StudioLogger.log(TAG, "Chưa có quyền USB Permission. Đang kích hoạt Popup hệ thống xin quyền truy cập USB Video...")
                        requestUsbPermission(usbDevice)
                    } else {
                        startUvcHdmiOfficialCapture(usbDevice, holder)
                    }
                } else {
                    StudioLogger.log(TAG, "❌ Không tìm thấy thiết bị USB Capture Card!")
                    listener.onError("Chưa cắm cáp USB Capture Card")
                }
            }
            VideoSourceMode.PHONE_BACK -> {
                StudioLogger.log(TAG, "Chuyển Nguồn Video -> Camera Sau Điện Thoại")
                resetSurfaceFormatForCamera2(holder)
                openPhoneCamera(getPhoneCameraId(facing = CameraCharacteristics.LENS_FACING_BACK), holder)
            }
            VideoSourceMode.PHONE_FRONT -> {
                StudioLogger.log(TAG, "Chuyển Nguồn Video -> Camera Trước Điện Thoại")
                resetSurfaceFormatForCamera2(holder)
                openPhoneCamera(getPhoneCameraId(facing = CameraCharacteristics.LENS_FACING_FRONT), holder)
            }
        }
    }

    private fun resetSurfaceFormatForCamera2(holder: SurfaceHolder) {
        try {
            holder.setFormat(PixelFormat.TRANSPARENT)
            holder.setFormat(PixelFormat.OPAQUE)
        } catch (e: Throwable) {}
    }

    private fun startUvcHdmiOfficialCapture(device: UsbDevice, holder: SurfaceHolder) {
        StudioLogger.log(TAG, "► KHỞI CHẠY KHUNG HÌNH UVCCAMERA NATIVE ENGINE...")
        uvcOfficialEngine.startStream(device, holder)
        val name = VideoSourceMode.HDMI_CAPTURE.displayName
        listener.onSourceChanged(VideoSourceMode.HDMI_CAPTURE, name)
    }

    fun requestUsbPermission(device: UsbDevice) {
        try {
            val intent = android.content.Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val flags = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = android.app.PendingIntent.getBroadcast(context, 0, intent, flags)
            StudioLogger.log(TAG, "🔔 KÍCH HOẠT POPUP HỆ THỐNG: Cho phép truy cập USB Video Device (${device.deviceName})")
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Throwable) {
            StudioLogger.log(TAG, "❌ Lỗi xin quyền USB Permission: ${e.message}")
        }
    }

    private fun getPhoneCameraId(facing: Int): String {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                return id
            }
        }
        return "0"
    }

    @SuppressLint("MissingPermission")
    private fun openPhoneCamera(cameraId: String, holder: SurfaceHolder) {
        startBackgroundThread()
        StudioLogger.log(TAG, "Mở Camera Điện Thoại ID: '$cameraId'...")
        isOpening = true

        backgroundHandler?.postDelayed({
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        isOpening = false
                        cameraDevice = camera
                        createPhoneCameraPreviewSession(camera, holder)
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
                        StudioLogger.log(TAG, "❌ Lỗi mở Camera ID '$cameraId': Code $error")
                        listener.onError("Lỗi mở Camera ID '$cameraId': Code $error")
                    }
                }, backgroundHandler)
            } catch (e: Throwable) {
                isOpening = false
                StudioLogger.log(TAG, "❌ Lỗi mở Camera: ${e.message}")
                listener.onError("Lỗi Camera: ${e.message}")
            }
        }, 150)
    }

    private fun createPhoneCameraPreviewSession(camera: CameraDevice, holder: SurfaceHolder) {
        try {
            val surface = holder.surface
            if (surface == null || !surface.isValid) {
                StudioLogger.log(TAG, "❌ SurfaceHolder chưa sẵn sàng!")
                return
            }

            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        requestBuilder.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                        StudioLogger.log(TAG, "▶ ĐÃ HIỂN THỊ THÀNH CÔNG PREVIEW CAMERA ID '${camera.id}'!")
                        val mode = if (currentSourceMode == VideoSourceMode.PHONE_FRONT) VideoSourceMode.PHONE_FRONT else VideoSourceMode.PHONE_BACK
                        listener.onSourceChanged(mode, mode.displayName)
                    } catch (e: Throwable) {
                        StudioLogger.log(TAG, "❌ Lỗi thiết lập RepeatingRequest: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    StudioLogger.log(TAG, "Cấu hình Preview Session thất bại!")
                }
            }, backgroundHandler)
        } catch (e: Throwable) {
            StudioLogger.log(TAG, "❌ Lỗi tạo Preview Session: ${e.message}")
        }
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

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("PhoneCameraBackgroundThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    override fun onFrameFps(fps: Float) {
        listener.onFrameReceived(fps)
    }

    override fun onError(errorMsg: String) {
        listener.onError(errorMsg)
    }
}
