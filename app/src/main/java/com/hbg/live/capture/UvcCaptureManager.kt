package com.hbg.live.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder

/**
 * Quản lý thu nhận tín hiệu Video từ HDMI Capture Card (Sony A73/Bàn trộn).
 * Khóa trạng thái chống Spam kết nối và chống Crash khi mở Camera nhiều lần.
 */
class UvcCaptureManager(
    private val context: Context,
    private val listener: UvcEventListener
) {
    interface UvcEventListener {
        fun onDeviceConnected(device: UsbDevice)
        fun onDeviceDisconnected(device: UsbDevice)
        fun onFrameReceived(fps: Float)
        fun onError(errorMsg: String)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var connectedUsbDevice: UsbDevice? = null
    private var externalCameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Flags chống Spam & Re-entry
    private var isStreaming = false
    private var isOpeningCamera = false

    companion object {
        private const val TAG = "UvcCaptureManager"
        private const val ACTION_USB_PERMISSION = "com.hbg.live.USB_PERMISSION"
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d(TAG, "ĐÃ CẤP QUYỀN USB: ${it.deviceName}")
                            connectedUsbDevice = it
                            listener.onDeviceConnected(it)
                        }
                    } else {
                        Log.e(TAG, "TỪ CHỐI QUYỀN USB")
                        listener.onError("Chưa cấp quyền truy cập USB Capture Card.")
                    }
                }
            }
        }
    }

    init {
        try {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbPermissionReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đăng ký USB Receiver", e)
        }
    }

    /**
     * Tự động quét thiết bị USB HDMI Capture Card (An toàn, chống Spam)
     */
    fun scanAndConnect(): UsbDevice? {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (isUvcCaptureDevice(device)) {
                if (connectedUsbDevice?.deviceName == device.deviceName && usbManager.hasPermission(device)) {
                    // Đã kết nối và đã có quyền từ trước, không cần spam cấp quyền lại
                    return device
                }

                connectedUsbDevice = device
                if (!usbManager.hasPermission(device)) {
                    Log.d(TAG, "Gửi yêu cầu xin quyền USB...")
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                    val permissionIntent = PendingIntent.getBroadcast(
                        context, 0, Intent(ACTION_USB_PERMISSION), flags
                    )
                    usbManager.requestPermission(device, permissionIntent)
                } else {
                    listener.onDeviceConnected(device)
                }
                return device
            }
        }
        return null
    }

    private fun isUvcCaptureDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 14 || device.deviceClass == 238 || device.deviceClass == 255) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }

    /**
     * Tìm ID của HDMI Capture Card (Khóa không cho chọn nhầm Camera Selfie trước)
     */
    fun findExternalCameraId(): String? {
        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "Danh sách toàn bộ Camera IDs trên thiết bị: ${cameraIds.joinToString()}")

            // Bước 1: Tìm camera có thuộc tính LENS_FACING_EXTERNAL (giá trị 2)
            for (id in cameraIds) {
                val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                Log.d(TAG, "Camera ID: $id -> Lens Facing: $facing")
                if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    Log.d(TAG, "Đã chọn HDMI Capture Card (LENS_FACING_EXTERNAL): ID $id")
                    externalCameraId = id
                    return id
                }
            }

            // Bước 2: Tìm camera ID khác 0 (Chính) và khác 1 (Selfie trước)
            for (id in cameraIds) {
                val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_FRONT && facing != CameraCharacteristics.LENS_FACING_BACK) {
                    Log.d(TAG, "Đã chọn Camera mở rộng: ID $id")
                    externalCameraId = id
                    return id
                }
            }

            // Bước 3: Nếu là máy có camera mở rộng ID > 1 (ví dụ ID 2, 3...)
            val externalCandidate = cameraIds.firstOrNull { it != "0" && it != "1" }
            if (externalCandidate != null) {
                Log.d(TAG, "Đã chọn Camera phụ ID: $externalCandidate")
                externalCameraId = externalCandidate
                return externalCandidate
            }

            // Bước 4: Mặc định chọn Camera sau (ID 0), tuyệt đối KHÔNG chọn Camera selfie trước (ID 1)
            val defaultBack = cameraIds.firstOrNull { 
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK 
            } ?: cameraIds.firstOrNull()

            Log.w(TAG, "Tạm chọn Camera ID: $defaultBack")
            externalCameraId = defaultBack
            return defaultBack

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi quét danh sách Camera IDs", e)
        }
        return null
    }

    /**
     * Chuyển đổi thủ công sang Camera ID tiếp theo trong danh sách
     */
    fun selectNextCameraId(): String? {
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) return null
            val currentIndex = cameraIds.indexOf(externalCameraId)
            val nextIndex = (currentIndex + 1) % cameraIds.size
            externalCameraId = cameraIds[nextIndex]
            Log.d(TAG, "Đã chuyển thủ công sang Camera ID: $externalCameraId")
            return externalCameraId
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi chuyển camera", e)
        }
        return null
    }

    /**
     * Khởi động Preview (Có khoá chống mở trùng lặp)
     */
    @SuppressLint("MissingPermission")
    fun startPreview(holder: SurfaceHolder) {
        if (isStreaming || isOpeningCamera || cameraDevice != null) {
            Log.d(TAG, "Camera đang chạy hoặc đang mở, bỏ qua cuộc gọi trùng lặp.")
            return
        }

        val targetCamId = externalCameraId ?: findExternalCameraId()
        if (targetCamId == null) {
            listener.onError("Chưa tìm thấy camera từ Capture Card.")
            return
        }

        isOpeningCamera = true
        startBackgroundThread()

        try {
            Log.d(TAG, "Mở Camera ID: $targetCamId")
            cameraManager.openCamera(targetCamId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    isOpeningCamera = false
                    cameraDevice = camera
                    createCameraPreviewSession(camera, holder)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    isOpeningCamera = false
                    camera.close()
                    cameraDevice = null
                    isStreaming = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    isOpeningCamera = false
                    camera.close()
                    cameraDevice = null
                    isStreaming = false
                    listener.onError("Lỗi mở camera ($error)")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            isOpeningCamera = false
            Log.e(TAG, "Lỗi mở camera", e)
        }
    }

    private fun createCameraPreviewSession(camera: CameraDevice, holder: SurfaceHolder) {
        try {
            val surface = holder.surface
            if (!surface.isValid) {
                isOpeningCamera = false
                return
            }

            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                            isStreaming = true
                            listener.onFrameReceived(60f)
                        } catch (e: Exception) {
                            Log.e(TAG, "Lỗi repeating request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isStreaming = false
                        listener.onError("Cấu hình luồng preview thất bại.")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tạo preview session", e)
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
        } catch (e: Exception) {
            // Ignored
        }
        backgroundThread = null
        backgroundHandler = null
    }

    fun stopPreview() {
        isStreaming = false
        isOpeningCamera = false
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đóng camera", e)
        }
        stopBackgroundThread()
    }

    fun release() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            // Unregistered
        }
        stopPreview()
        connectedUsbDevice = null
    }

    fun isPreviewRunning(): Boolean = isStreaming
}
