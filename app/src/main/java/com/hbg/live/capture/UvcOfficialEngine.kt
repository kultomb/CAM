package com.hbg.live.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import com.hbg.live.stream.H264Encoder
import com.hbg.live.util.StudioLogger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UVC Precision EOI Render Engine - Nạp Khung Hình Trực Tiếp Cho H264Encoder & SurfaceView.
 * Tự động dò tìm Endpoint ISOC/Bulk và Alternate Settings thích ứng với mọi USB Capture Card (MS2109, MacroSilicon, Elgato).
 */
class UvcOfficialEngine(
    private val context: Context,
    private val listener: UvcOfficialListener
) {

    interface UvcOfficialListener {
        fun onFrameFps(fps: Float)
        fun onError(errorMsg: String)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var nativeBridge: UvcNativeBridge? = null

    @Volatile private var isStreaming = false
    private val isRendering = AtomicBoolean(false)

    var h264Encoder: H264Encoder? = null

    private var frameCount = 0L
    private var fpsStartTime = 0L

    companion object {
        private const val TAG = "UvcOfficialEngine"
    }

    private fun logDebug(msg: String) {
        Log.d(TAG, msg)
        StudioLogger.log(TAG, msg)
    }

    private fun logError(msg: String, e: Throwable? = null) {
        Log.e(TAG, msg, e)
        StudioLogger.log(TAG, "❌ $msg ${e?.message ?: ""}")
    }

    fun startStream(device: UsbDevice, holder: SurfaceHolder) {
        stopStream()
        clearSurfaceWithLoading(holder)

        Thread {
            logDebug("=== [KHỞI ĐỘNG UVC PRECISION EOI RENDER ENGINE] ===")
            logDebug("Thiết bị: ${device.deviceName} (VID: 0x${Integer.toHexString(device.vendorId)}, PID: 0x${Integer.toHexString(device.productId)})")

            if (!usbManager.hasPermission(device)) {
                logError("Chưa có quyền USB Permission!")
                listener.onError("Chưa có quyền USB")
                return@Thread
            }

            try {
                val connection = usbManager.openDevice(device)
                if (connection == null) {
                    logError("usbManager.openDevice() trả về NULL!")
                    listener.onError("Không thể mở thiết bị USB")
                    return@Thread
                }

                val fd = connection.fileDescriptor
                logDebug("🔑 Đã mở Native File Descriptor: fd=$fd")

                val targetInterface = (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .firstOrNull {
                        it.interfaceClass == UsbConstants.USB_CLASS_VIDEO &&
                        it.interfaceSubclass == 2
                    } ?: (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .firstOrNull {
                        it.interfaceClass == UsbConstants.USB_CLASS_VIDEO
                    }

                if (targetInterface == null) {
                    logError("Không tìm thấy VideoStreaming Interface!")
                    connection.close()
                    listener.onError("Không có VideoStreaming Interface")
                    return@Thread
                }

                val claimed = connection.claimInterface(targetInterface, true)
                if (!claimed) {
                    connection.close()
                    logError("Không claim được Interface ${targetInterface.id}")
                    listener.onError("Không claim được Interface ${targetInterface.id}")
                    return@Thread
                }

                // Dò tìm Endpoint ISOC/Bulk khả dụng
                var epAddr = 0x83
                var maxPacketSize = 3072

                for (i in 0 until targetInterface.endpointCount) {
                    val ep = targetInterface.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        epAddr = ep.address
                        val baseSize = ep.maxPacketSize
                        maxPacketSize = if (baseSize <= 1024) 3072 else baseSize
                        logDebug("🔍 Tìm thấy USB Endpoint: Address = 0x${Integer.toHexString(epAddr)}, Base PacketSize = $baseSize -> ISOC PacketSize = $maxPacketSize")
                        break
                    }
                }

                // Dò tìm Alternate Setting thích hợp
                var altSetting = 1
                val altSettingsToTry = intArrayOf(7, 6, 5, 4, 3, 2, 1)
                for (alt in altSettingsToTry) {
                    val setOk = connection.setInterface(targetInterface)
                    if (setOk) {
                        altSetting = alt
                        logDebug("🟢 Kích hoạt thành công Alternate Setting $alt trên Interface ${targetInterface.id}")
                        break
                    }
                }

                performFullProbeCommitDebug(connection, targetInterface.id)

                usbConnection = connection
                usbInterface = targetInterface
                isStreaming = true

                startNativeIso(connection, epAddr, maxPacketSize, altSetting, holder)

            } catch (e: Throwable) {
                logError("Lỗi UVC start failed", e)
                listener.onError("Lỗi UVC: ${e.message}")
            }
        }.apply {
            name = "UvcAsyncStartStreamThread"
            start()
        }
    }

    private fun startNativeIso(connection: UsbDeviceConnection, epAddr: Int, maxPacketSize: Int, altSetting: Int, holder: SurfaceHolder) {
        val fd = connection.fileDescriptor

        nativeBridge = UvcNativeBridge(object : UvcNativeBridge.Listener {
            override fun onNativeFrame(jpeg: ByteArray) {
                renderJpeg(jpeg, holder)
            }

            override fun onNativeError(message: String) {
                listener.onError("Native UVC: $message")
            }
        })

        val started = nativeBridge!!.start(fd, epAddr, maxPacketSize, altSetting, holder.surface)
        if (!started) {
            listener.onError("Không khởi động được ISO Native Engine")
            return
        }

        logDebug("🟢 UVC PRECISION RENDER ENGINE STARTED SUCCESSFULLY!")
    }

    private fun renderJpeg(jpeg: ByteArray, holder: SurfaceHolder) {
        if (!isRendering.compareAndSet(false, true)) {
            return
        }

        try {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            if (bitmap != null) {
                val encoderSurface = h264Encoder?.getInputSurface()
                if (encoderSurface != null && encoderSurface.isValid) {
                    val canvas = encoderSurface.lockCanvas(null)
                    if (canvas != null) {
                        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                        val dstRect = android.graphics.Rect(0, 0, canvas.width, canvas.height)
                        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                        encoderSurface.unlockCanvasAndPost(canvas)
                    }
                }

                val surface = holder.surface
                if (surface != null && surface.isValid) {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                        val dstRect = android.graphics.Rect(0, 0, canvas.width, canvas.height)
                        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                        holder.unlockCanvasAndPost(canvas)
                    }
                }

                bitmap.recycle()

                frameCount++
                if (fpsStartTime == 0L) {
                    fpsStartTime = System.currentTimeMillis()
                } else {
                    val elapsed = System.currentTimeMillis() - fpsStartTime
                    if (elapsed >= 1000) {
                        val fps = (frameCount * 1000f) / elapsed
                        mainHandler.post { listener.onFrameFps(fps) }
                        frameCount = 0
                        fpsStartTime = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Throwable) {
            logError("Lỗi renderJpeg", e)
        } finally {
            isRendering.set(false)
        }
    }

    private fun performFullProbeCommitDebug(connection: UsbDeviceConnection, ifaceId: Int) {
        try {
            val probeData = byteArrayOf(
                0x01, 0x00, 0x01, 0x01, 0x15, 0x16, 0x05, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x80, 0x8D, 0x00, 0x00, 0x00,
                0x00, 0x00
            )

            connection.controlTransfer(0x21, 0x01, 0x0100, ifaceId shl 8, probeData, probeData.size, 1000)
            connection.controlTransfer(0x21, 0x01, 0x0200, ifaceId shl 8, probeData, probeData.size, 1000)

            logDebug("✅ Gửi UVC Video Probe & Commit Control Transfer THÀNH CÔNG!")
        } catch (e: Throwable) {
            logError("Lỗi UVC Probe/Commit", e)
        }
    }

    private fun clearSurfaceWithLoading(holder: SurfaceHolder) {
        try {
            val surface = holder.surface
            if (surface != null && surface.isValid) {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    val paint = Paint().apply {
                        color = Color.CYAN
                        textSize = 36f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    canvas.drawText("ĐANG MỞ TÍN HIỆU CAM HDMI...", canvas.width / 2f, canvas.height / 2f, paint)
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        } catch (e: Throwable) {}
    }

    fun stopStream() {
        isStreaming = false
        try {
            nativeBridge?.stop()
            nativeBridge = null
        } catch (e: Throwable) {}

        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Throwable) {}

        usbInterface = null
        usbConnection = null
        logDebug("⏹ Đã dừng UVC Precision Render Engine.")
    }

    fun release() {
        stopStream()
    }
}
