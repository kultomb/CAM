package com.hbg.live.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import com.hbg.live.stream.H264Encoder
import com.hbg.live.util.StudioLogger

/**
 * Điều phối Nguồn Video đa năng (CAM HDMI USB Capture / Camera Sau Multi-Lens / Camera Trước).
 */
class CameraSourceManager(
    private val context: Context,
    private val listener: CameraSourceListener
) : UvcOfficialEngine.UvcOfficialListener {

    interface CameraSourceListener {
        fun onSourceChanged(mode: VideoSourceMode, displayName: String)
        fun onFrameReceived(fps: Float)
        fun onError(errorMsg: String)
    }

    enum class VideoSourceMode(var displayName: String) {
        HDMI_CAPTURE("CAM HDMI"),
        PHONE_BACK("Camera Sau (0.5x, 1x, 3x)"),
        PHONE_FRONT("Camera Trước")
    }

    data class BackLensOption(
        val name: String,
        val zoomRatio: Float,
        val physicalCameraId: String? = null
    )

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    val uvcOfficialEngine = UvcOfficialEngine(context, this)

    var h264Encoder: H264Encoder? = null
        set(value) {
            field = value
            uvcOfficialEngine.h264Encoder = value
        }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeSurfaceHolder: SurfaceHolder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    var currentSourceMode = VideoSourceMode.PHONE_BACK
        private set

    var currentZoomRatio = 1.0f
        private set

    var activePhysicalCameraId: String? = null
        private set

    companion object {
        private const val TAG = "CameraSourceManager"
        const val ACTION_USB_PERMISSION = "com.hbg.live.USB_PERMISSION"
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
                if ((iface.interfaceClass == 14 && iface.interfaceSubclass == 2) ||
                    iface.interfaceClass == 14 || iface.interfaceClass == 255 || device.deviceClass == 239) {
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
        resetSurfaceFormatForCamera2(holder)

        when (mode) {
            VideoSourceMode.HDMI_CAPTURE -> {
                val usbDevice = getConnectedUsbCaptureDevice()
                if (usbDevice != null) {
                    if (!usbManager.hasPermission(usbDevice)) {
                        StudioLogger.log(TAG, "Chưa có quyền USB Permission. Đang kích hoạt Popup hệ thống xin quyền truy cập USB Video...")
                        val intent = android.content.Intent(ACTION_USB_PERMISSION).apply {
                            setPackage(context.packageName)
                        }
                        val permissionIntent = android.app.PendingIntent.getBroadcast(
                            context, 0, intent,
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
                openPhoneCamera(CameraCharacteristics.LENS_FACING_BACK, holder, targetCameraId, zoomRatio)
            }
            VideoSourceMode.PHONE_FRONT -> {
                StudioLogger.log(TAG, "Chuyển Nguồn Video -> Camera Trước Điện Thoại")
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

    private fun openPhoneCamera(facing: Int, holder: SurfaceHolder, targetCameraId: String? = null, zoomRatio: Float = 1.0f) {
        startBackgroundThread()
        try {
            var selectedId: String? = targetCameraId

            if (selectedId == null) {
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (lensFacing == facing) {
                        selectedId = id
                        break
                    }
                }
            }

            if (selectedId == null) {
                listener.onError("Không tìm thấy Camera phù hợp!")
                return
            }

            activePhysicalCameraId = selectedId
            StudioLogger.log(TAG, "Mở Camera2 API với ID [$selectedId] (Zoom ${zoomRatio}x)")

            try {
                cameraManager.openCamera(selectedId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCameraPreviewSession(camera, holder, zoomRatio)
                        val label = if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            "Camera Sau (${zoomRatio}x)"
                        } else {
                            "Camera Trước"
                        }
                        listener.onSourceChanged(currentSourceMode, label)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        listener.onError("Lỗi mở Camera2 (mã lỗi: $error)")
                    }
                }, backgroundHandler)
            } catch (e: SecurityException) {
                listener.onError("Chưa được cấp quyền Camera!")
            }
        } catch (e: Throwable) {
            StudioLogger.log(TAG, "Lỗi mở Phone Camera", e)
            listener.onError("Không thể mở Camera: ${e.message}")
        }
    }

    private fun createCameraPreviewSession(camera: CameraDevice, holder: SurfaceHolder, zoomRatio: Float) {
        try {
            val surfaces = ArrayList<Surface>()
            surfaces.add(holder.surface)

            val encoderSurface = h264Encoder?.getInputSurface()
            if (encoderSurface != null && encoderSurface.isValid) {
                surfaces.add(encoderSurface)
            }

            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (surface in surfaces) {
                previewRequestBuilder.addTarget(surface)
            }

            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    previewRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                } catch (e: Throwable) {}
            }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        previewRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
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
    }

    override fun onFrameFps(fps: Float) {
        listener.onFrameReceived(fps)
    }

    override fun onError(errorMsg: String) {
        listener.onError(errorMsg)
    }

    fun release() {
        stopAllSources()
        stopBackgroundThread()
        uvcOfficialEngine.release()
    }
}
