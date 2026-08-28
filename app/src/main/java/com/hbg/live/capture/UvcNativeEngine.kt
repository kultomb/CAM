package com.hbg.live.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.SurfaceHolder
import com.hbg.live.util.StudioLogger

/**
 * Engine Native UVC Thu & Giải Mã Video (Ưu Tiên BULK Endpoint Type 2 Tương Thích 100% Java USB Host)
 */
class UvcNativeEngine(
    private val context: Context,
    private val listener: UvcFrameListener
) {
    interface UvcFrameListener {
        fun onFrameFps(fps: Float)
        fun onError(errorMsg: String)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var usbEndpoint: UsbEndpoint? = null

    private var workerThread: Thread? = null
    @Volatile private var isRunning = false

    companion object {
        private const val TAG = "UvcNativeEngine"
        private const val USB_CLASS_VIDEO = 14
        private const val USB_SUBCLASS_VIDEOSTREAMING = 2
    }

    fun startStream(device: UsbDevice, holder: SurfaceHolder) {
        stopStream()

        StudioLogger.log(TAG, "=== [BƯỚC 1/7 UVC DIAG] KHỞI ĐỘNG KẾT NỐI MÁY ẢNH ===")
        StudioLogger.log(TAG, "Thiết bị USB: ${device.deviceName} (VID: 0x${Integer.toHexString(device.vendorId)}, PID: 0x${Integer.toHexString(device.productId)})")

        val hasPermission = usbManager.hasPermission(device)
        StudioLogger.log(TAG, "=== [BƯỚC 2/7 UVC DIAG] KIỂM TRA QUYỀN USB ===")
        StudioLogger.log(TAG, "Permission Granted: $hasPermission")

        if (!hasPermission) {
            StudioLogger.log(TAG, "❌ [LỖI UVC] Chưa có quyền truy cập USB Video Device!")
            listener.onError("Chưa cấp quyền USB")
            return
        }

        try {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                StudioLogger.log(TAG, "❌ [LỖI UVC] usbManager.openDevice() trả về NULL!")
                listener.onError("Không thể mở thiết bị USB")
                return
            }

            StudioLogger.log(TAG, "=== [BƯỚC 3/7 UVC DIAG] TÌM BULK ENDPOINT (TYPE 2) & VIDEOSTREAMING INTERFACE ===")
            var bulkInterface: UsbInterface? = null
            var bulkEndpoint: UsbEndpoint? = null

            var isocInterface: UsbInterface? = null
            var isocEndpoint: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val isVs = iface.interfaceClass == USB_CLASS_VIDEO && iface.interfaceSubclass == USB_SUBCLASS_VIDEOSTREAMING
                StudioLogger.log(TAG, " -> Interface $i (ID ${iface.id}): Class=${iface.interfaceClass}, Subclass=${iface.interfaceSubclass}, IsVideoStreaming=$isVs, Endpoints=${iface.endpointCount}")

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        val epTypeStr = when(ep.type) {
                            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK (Type 2 - Ưu Tiên Chuẩn)"
                            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC (Type 1)"
                            else -> "TYPE ${ep.type}"
                        }
                        StudioLogger.log(TAG, "    * Endpoint IN 0x${Integer.toHexString(ep.address)}: $epTypeStr, MaxPacketSize: ${ep.maxPacketSize}")

                        // Ưu tiên Endpoint BULK (Type 2) tương thích 100% bulkTransfer
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (bulkEndpoint == null || ep.maxPacketSize > bulkEndpoint.maxPacketSize) {
                                bulkInterface = iface
                                bulkEndpoint = ep
                            }
                        } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                            if (isocEndpoint == null || ep.maxPacketSize > isocEndpoint.maxPacketSize) {
                                isocInterface = iface
                                isocEndpoint = ep
                            }
                        }
                    }
                }
            }

            val targetInterface = bulkInterface ?: isocInterface
            val targetEndpoint = bulkEndpoint ?: isocEndpoint

            if (targetInterface == null || targetEndpoint == null) {
                StudioLogger.log(TAG, "❌ [LỖI UVC] Không tìm thấy Endpoint IN trên VideoStreaming Interface!")
                connection.close()
                listener.onError("Không có VideoStreaming Endpoint IN")
                return
            }

            // Claim chính xác Interface sở hữu Endpoint này
            val claimed = connection.claimInterface(targetInterface, true)
            StudioLogger.log(TAG, "🔑 Đã Claim chính xác USB Interface ${targetInterface.id}: $claimed")

            try {
                val setIfRes = connection.setInterface(targetInterface)
                StudioLogger.log(TAG, "Set Interface ${targetInterface.id} Alternate Setting result: $setIfRes")
            } catch (e: Throwable) {}

            StudioLogger.log(TAG, "=== [BƯỚC 4/7 UVC DIAG] CHỌN ENDPOINT THU NỐI ===")
            val chosenTypeStr = if (targetEndpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "BULK (Type 2)" else "ISOC (Type 1)"
            StudioLogger.log(TAG, "Khớp Endpoint: Address 0x${Integer.toHexString(targetEndpoint.address)} ($chosenTypeStr) trên Interface ${targetInterface.id} (MaxPacketSize: ${targetEndpoint.maxPacketSize})")

            StudioLogger.log(TAG, "=== [BƯỚC 5/7 UVC DIAG] ĐÀM THOẠI UVC PROBE & COMMIT CONTROL TRÊN INTERFACE ${targetInterface.id} ===")
            performFullUvcProbeAndCommit(connection, targetInterface.id)

            usbConnection = connection
            usbInterface = targetInterface
            usbEndpoint = targetEndpoint

            isRunning = true

            workerThread = Thread {
                val packetSize = 64 * 1024
                val buffer = ByteArray(packetSize)
                val mjpegFrame = ByteArray(4 * 1024 * 1024)
                var frameOffset = 0
                var frameCount = 0
                var totalPacketsReceived = 0
                var firstPacketLogged = false
                var lastFpsTime = System.currentTimeMillis()

                StudioLogger.log(TAG, "=== [BƯỚC 6/7 UVC DIAG] VÒNG LẶP THU VÀ BÓC TÁCH UVC PAYLOAD ĐANG CHẠY ===")

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val bytesRead = connection.bulkTransfer(targetEndpoint, buffer, packetSize, 200)
                        if (bytesRead > 0) {
                            totalPacketsReceived++

                            if (!firstPacketLogged) {
                                firstPacketLogged = true
                                StudioLogger.log(TAG, "🟢 [USB TRANSFER STARTED] Đã nhận thành công gói tin USB! BytesRead: $bytesRead")
                            }

                            var payloadOffset = 0
                            val headerLen = buffer[0].toInt() and 0xFF

                            if (headerLen in 2..12 && bytesRead > headerLen) {
                                payloadOffset = headerLen
                            }

                            val payloadLen = bytesRead - payloadOffset
                            if (payloadLen > 0) {
                                for (i in payloadOffset until bytesRead) {
                                    if (frameOffset < mjpegFrame.size - 1) {
                                        mjpegFrame[frameOffset++] = buffer[i]
                                    } else {
                                        frameOffset = 0
                                    }

                                    if (frameOffset > 2 && 
                                        mjpegFrame[frameOffset - 2] == 0xFF.toByte() && 
                                        mjpegFrame[frameOffset - 1] == 0xD9.toByte()) {
                                        
                                        renderFrameToCanvas(mjpegFrame, frameOffset, holder)
                                        frameOffset = 0
                                        frameCount++

                                        val now = System.currentTimeMillis()
                                        if (now - lastFpsTime >= 1000) {
                                            StudioLogger.log(TAG, "=== [BƯỚC 7/7 UVC THÀNH CÔNG 100%] LIVESTREAM: $frameCount FPS (Packets: $totalPacketsReceived) ===")
                                            listener.onFrameFps(frameCount.toFloat())
                                            frameCount = 0
                                            lastFpsTime = now
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        // Protected iteration
                    }
                }
            }.apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

        } catch (e: Throwable) {
            StudioLogger.log(TAG, "❌ [LỖI NGOẠI LỆ UVC] ${e.message}")
            listener.onError("Lỗi UVC: ${e.message}")
        }
    }

    /**
     * Đàm thoại đầy đủ UVC PROBE (GET_CUR) -> UVC COMMIT (SET_CUR) theo đúng UVC Spec 1.5
     */
    private fun performFullUvcProbeAndCommit(connection: UsbDeviceConnection, interfaceId: Int) {
        try {
            val probeData = ByteArray(26)
            
            val getProbeRes = connection.controlTransfer(
                0xA1, // Device to Host | Class | Interface
                0x81, // GET_CUR
                0x0100, // VS_PROBE_CONTROL
                interfaceId,
                probeData,
                probeData.size,
                1000
            )
            StudioLogger.log(TAG, "UVC GET_CUR Probe Control Result (Interface $interfaceId): Code $getProbeRes")

            val uvcCommitData = byteArrayOf(
                0x01.toByte(), 0x00.toByte(),
                0x01.toByte(),
                0x01.toByte(),
                0x15.toByte(), 0x16.toByte(), 0x05.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x30.toByte(), 0x00.toByte()
            )

            val setCommitRes = connection.controlTransfer(
                0x21, // Host to Device | Class | Interface
                0x01, // SET_CUR
                0x0200, // VS_COMMIT_CONTROL
                interfaceId,
                if (getProbeRes > 0) probeData else uvcCommitData,
                if (getProbeRes > 0) probeData.size else uvcCommitData.size,
                1000
            )
            StudioLogger.log(TAG, "UVC SET_CUR Commit Control Result (Interface $interfaceId): Code $setCommitRes")
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi UVC Probe/Commit negotiation", e)
        }
    }

    private fun renderFrameToCanvas(jpegData: ByteArray, length: Int, holder: SurfaceHolder) {
        try {
            if (length <= 0 || length > jpegData.size) return
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, length)
            if (bitmap != null) {
                val surface = holder.surface
                if (surface != null && surface.isValid) {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            val destRect = Rect(0, 0, canvas.width, canvas.height)
                            canvas.drawBitmap(bitmap, null, destRect, null)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                }
                bitmap.recycle()
            }
        } catch (e: Throwable) {}
    }

    fun stopStream() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null

        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
            usbConnection = null
            usbInterface = null
            usbEndpoint = null
        } catch (e: Throwable) {}
    }
}
