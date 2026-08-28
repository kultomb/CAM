package com.hbg.live.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.SurfaceHolder
import com.hbg.live.stream.H264Encoder
import com.hbg.live.util.StudioLogger

/**
 * UvcOfficialEngine V19 - Live RTMP Streaming Integrated Engine:
 * Tích hợp H264Encoder tự động truyền dữ liệu khung hình camera mã hóa sang Facebook Live & YouTube Live.
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

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var nativeBridge: UvcNativeBridge? = null

    var h264Encoder: H264Encoder? = null

    private val isRendering = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var isStreaming = false

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

                connection.setInterface(targetInterface)

                val altSetting = 3
                val epAddr = 0x83
                val maxPacketSize = 3072

                logDebug("Interface ${targetInterface.id} Alt $altSetting READY (Video EP=0x${Integer.toHexString(epAddr)}, PacketSize=$maxPacketSize)")

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
            if (jpeg.size < 4) {
                isRendering.set(false)
                return
            }

            var soiOffset = -1
            var eoiOffset = -1

            val scanLimit = minOf(64, jpeg.size - 1)
            for (i in 0 until scanLimit) {
                if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xD8.toByte()) {
                    soiOffset = i
                    break
                }
            }

            if (soiOffset < 0) soiOffset = 0

            for (i in jpeg.size - 2 downTo soiOffset) {
                if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xD9.toByte()) {
                    eoiOffset = i + 2
                    break
                }
            }

            val validLength = if (eoiOffset > soiOffset) (eoiOffset - soiOffset) else (jpeg.size - soiOffset)

            val bitmap = BitmapFactory.decodeByteArray(jpeg, soiOffset, validLength)
            if (bitmap == null) {
                isRendering.set(false)
                return
            }

            // Đẩy trực tiếp khung hình Bitmap sang H264Encoder để phát luồng RTMP lên Facebook Live & YouTube Live
            try {
                h264Encoder?.encodeBitmap(bitmap)
            } catch (e: Throwable) {}

            val surface = holder.surface
            if (surface != null && surface.isValid) {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    try {
                        val dst = Rect(0, 0, canvas.width, canvas.height)
                        canvas.drawBitmap(bitmap, null, dst, null)
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }
            bitmap.recycle()
        } catch (e: Throwable) {
            Log.e(TAG, "Render JPEG failed", e)
        } finally {
            isRendering.set(false)
        }
    }

    private fun clearSurfaceWithLoading(holder: SurfaceHolder) {
        try {
            val surface = holder.surface
            if (surface != null && surface.isValid) {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    try {
                        canvas.drawColor(Color.BLACK)
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }
        } catch (e: Throwable) {}
    }

    private fun performFullProbeCommitDebug(connection: UsbDeviceConnection, interfaceId: Int) {
        try {
            val probeData = ByteArray(26)
            probeData[0] = 0x01.toByte()
            probeData[1] = 0x02.toByte()
            probeData[2] = 0x01.toByte()
            probeData[3] = 0x00.toByte()
            probeData[4] = 0x15.toByte()
            probeData[5] = 0x16.toByte()
            probeData[6] = 0x05.toByte()
            probeData[7] = 0x00.toByte()
            probeData[22] = 0x00.toByte()
            probeData[23] = 0x0C.toByte()

            val resSetProbe = connection.controlTransfer(0x21, 0x01, 0x0100, interfaceId, probeData, probeData.size, 500)
            val resGetProbe = connection.controlTransfer(0xA1, 0x81, 0x0100, interfaceId, probeData, probeData.size, 500)
            val resSetCommit = connection.controlTransfer(0x21, 0x01, 0x0200, interfaceId, probeData, probeData.size, 500)
            
            logDebug("UVC 1080p30 MJPEG Negotiation: SET_PROBE=$resSetProbe, GET_PROBE=$resGetProbe, SET_COMMIT=$resSetCommit")
        } catch (e: Throwable) {
            logError("Lỗi Probe Commit negotiation", e)
        }
    }

    fun stopStream() {
        isStreaming = false
        try {
            nativeBridge?.stop()
        } catch (e: Throwable) {
            logError("Native stop failed", e)
        }
        nativeBridge = null

        try {
            val iface = usbInterface
            val connection = usbConnection
            if (iface != null && connection != null) {
                connection.releaseInterface(iface)
                connection.close()
            }
        } catch (e: Throwable) {
            logError("USB close failed", e)
        }

        usbInterface = null
        usbConnection = null
        isRendering.set(false)
    }
}
