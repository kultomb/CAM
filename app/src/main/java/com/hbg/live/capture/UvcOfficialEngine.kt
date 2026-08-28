package com.hbg.live.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * UVC Precision EOI Render Engine - Nạp Khung Hình Bất Đồng Bộ Qua GPU Hardware Canvas.
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
    private var renderExecutor = Executors.newSingleThreadExecutor()

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var nativeBridge: UvcNativeBridge? = null

    @Volatile private var isStreaming = false
    private val isRendering = AtomicBoolean(false)

    var h264Encoder: H264Encoder? = null

    private var frameCount = 0L
    private var fpsStartTime = 0L
    private var lastValidBitmap: Bitmap? = null

    companion object {
        private const val TAG = "UvcOfficialEngine"

        // Bảng Huffman tiêu chuẩn UVC MJPEG (420 bytes) bổ sung cho Android BitmapFactory khi capture card nén bỏ bảng DHT
        private val DEFAULT_HUFFMAN_TABLE = byteArrayOf(
            0xFF.toByte(), 0xC4.toByte(), 0x01, 0xA2.toByte(),
            0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x01, 0x00, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x10, 0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x01, 0x7D.toByte(),
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81.toByte(), 0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52, 0xD1.toByte(), 0xF0.toByte(),
            0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(),
            0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(),
            0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(),
            0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(),
            0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xE1.toByte(),
            0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(),
            0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(),
            0x11, 0x00, 0x02, 0x01, 0x02, 0x04, 0x04, 0x03, 0x04, 0x07, 0x05, 0x04, 0x04, 0x00, 0x01, 0x7D.toByte(),
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81.toByte(), 0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52, 0xD1.toByte(), 0xF0.toByte(),
            0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(),
            0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(),
            0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(),
            0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(),
            0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xE1.toByte(),
            0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(),
            0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte()
        )
    }

    private fun logDebug(msg: String) {
        Log.d(TAG, msg)
        StudioLogger.log(TAG, msg)
    }

    private fun logError(msg: String, e: Throwable? = null) {
        Log.e(TAG, msg, e)
        StudioLogger.log(TAG, "❌ $msg ${e?.message ?: ""}")
    }

    private fun ensureHuffmanTable(jpeg: ByteArray): ByteArray {
        val checkLen = minOf(jpeg.size, 512)
        for (i in 0 until checkLen - 1) {
            if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xC4.toByte()) {
                return jpeg
            }
        }

        if (jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
            val result = ByteArray(jpeg.size + DEFAULT_HUFFMAN_TABLE.size)
            result[0] = 0xFF.toByte()
            result[1] = 0xD8.toByte()
            System.arraycopy(DEFAULT_HUFFMAN_TABLE, 0, result, 2, DEFAULT_HUFFMAN_TABLE.size)
            System.arraycopy(jpeg, 2, result, 2 + DEFAULT_HUFFMAN_TABLE.size, jpeg.size - 2)
            return result
        }
        return jpeg
    }

    fun startStream(device: UsbDevice, holder: SurfaceHolder) {
        stopStream()
        clearSurfaceWithLoading(holder)

        if (renderExecutor.isShutdown || renderExecutor.isTerminated) {
            renderExecutor = Executors.newSingleThreadExecutor()
        }

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

                // 1. Gửi UVC Probe & Commit Control Transfer TRƯỚC để chốt định dạng MJPEG
                performFullProbeCommitDebug(connection, targetInterface.id)

                // 2. Kích hoạt Alternate Setting thích hợp
                var altSetting = 1
                val altSettingsToTry = intArrayOf(1, 2, 3, 4, 5, 6, 7)
                for (alt in altSettingsToTry) {
                    val setOk = connection.setInterface(targetInterface)
                    if (setOk) {
                        altSetting = alt
                        logDebug("🟢 Kích hoạt thành công Alternate Setting $alt trên Interface ${targetInterface.id}")
                        break
                    }
                }

                // 3. Lấy chính xác epAddr và maxPacketSize hợp lệ của Kernel Linux SAU KHI setInterface
                var epAddr = 0x83
                var maxPacketSize = 1024

                for (i in 0 until targetInterface.endpointCount) {
                    val ep = targetInterface.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        epAddr = ep.address
                        maxPacketSize = ep.maxPacketSize
                        logDebug("🔍 Lấy Kernel PacketSize thực tế cho Alt $altSetting: Address = 0x${Integer.toHexString(epAddr)}, PacketSize = $maxPacketSize")
                        break
                    }
                }

                usbConnection = connection
                usbInterface = targetInterface
                isStreaming = true

                startNativeIso(connection, targetInterface.id, epAddr, maxPacketSize, altSetting, holder)

            } catch (e: Throwable) {
                logError("Lỗi UVC start failed", e)
                listener.onError("Lỗi UVC: ${e.message}")
            }
        }.apply {
            name = "UvcAsyncStartStreamThread"
            start()
        }
    }

    private fun startNativeIso(connection: UsbDeviceConnection, ifaceId: Int, epAddr: Int, maxPacketSize: Int, altSetting: Int, holder: SurfaceHolder) {
        val fd = connection.fileDescriptor

        nativeBridge = UvcNativeBridge(object : UvcNativeBridge.Listener {
            override fun onNativeFrame(jpeg: ByteArray) {
                if (!isRendering.compareAndSet(false, true)) {
                    return
                }

                renderExecutor.execute {
                    try {
                        renderJpegInternal(jpeg, holder)
                    } catch (e: Throwable) {
                        logError("Lỗi renderExecutor", e)
                    } finally {
                        isRendering.set(false)
                    }
                }
            }

            override fun onNativeError(message: String) {
                listener.onError("Native UVC: $message")
            }
        })

        val started = nativeBridge!!.start(fd, ifaceId, epAddr, maxPacketSize, altSetting, null)
        if (!started) {
            listener.onError("Không khởi động được ISO Native Engine")
            return
        }

        logDebug("🟢 UVC PRECISION RENDER ENGINE STARTED SUCCESSFULLY!")
    }

    private fun renderJpegInternal(jpeg: ByteArray, holder: SurfaceHolder) {
        try {
            val safeJpeg = ensureHuffmanTable(jpeg)
            val decoded = BitmapFactory.decodeByteArray(safeJpeg, 0, safeJpeg.size)
            if (decoded != null) {
                lastValidBitmap = decoded
                h264Encoder?.encodeBitmap(decoded)
            } else {
                Log.e("HBG-UVC", "❌ BitmapFactory.decodeByteArray trả về NULL cho mảng byte JPEG size=${jpeg.size}")
            }

            val bitmapToDraw = lastValidBitmap ?: return

            val surface = holder.surface
            if (surface != null && surface.isValid) {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    try {
                        val cw: Float = canvas.width.toFloat()
                        val ch: Float = canvas.height.toFloat()
                        val bw: Float = bitmapToDraw.width.toFloat()
                        val bh: Float = bitmapToDraw.height.toFloat()

                        val scale: Float = min(cw / bw, ch / bh)
                        val dw: Float = bw * scale
                        val dh: Float = bh * scale
                        val left: Float = (cw - dw) / 2.0f
                        val top: Float = (ch - dh) / 2.0f

                        canvas.drawColor(Color.BLACK)
                        val dstRect = RectF(left, top, left + dw, top + dh)
                        canvas.drawBitmap(bitmapToDraw, null, dstRect, null)
                    } catch (e: Throwable) {
                        Log.e("HBG-UVC", "❌ Surface Canvas drawBitmap failed", e)
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }

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
        } catch (e: Throwable) {
            Log.e("HBG-UVC", "❌ Surface render failed", e)
        }
    }

    private fun performFullProbeCommitDebug(connection: UsbDeviceConnection, ifaceId: Int) {
        try {
            // UVC 1.1 Video Probe & Commit Control Transfer (MJPEG 1920x1080 30fps)
            val probeData = byteArrayOf(
                0x01, 0x00, // bmHint
                0x01,       // bFormatIndex (1 = MJPEG)
                0x01,       // bFrameIndex (1 = 1920x1080)
                0x15, 0x16, 0x05, 0x00, // dwFrameInterval (333333 = 30fps)
                0x00, 0x00, // wKeyFrameRate
                0x00, 0x00, // wPFrameRate
                0x00, 0x00, // wCompQuality
                0x00, 0x00, // wCompWindowSize
                0x00, 0x00, // wDelay
                0x00, 0x80.toByte(), 0x8D.toByte(), 0x00, // dwMaxVideoFrameSize
                0x00, 0x04, 0x00, 0x00  // dwMaxPayloadTransferSize (1024)
            )

            // SET_CUR Probe (VS_PROBE_CONTROL = 0x0100)
            connection.controlTransfer(0x21, 0x01, 0x0100, ifaceId shl 8, probeData, probeData.size, 1000)
            // GET_CUR Probe (VS_PROBE_CONTROL = 0x0100)
            connection.controlTransfer(0xA1, 0x81, 0x0100, ifaceId shl 8, probeData, probeData.size, 1000)
            // SET_CUR Commit (VS_COMMIT_CONTROL = 0x0200)
            connection.controlTransfer(0x21, 0x01, 0x0200, ifaceId shl 8, probeData, probeData.size, 1000)

            logDebug("✅ Chuẩn hóa UVC Video Probe & Commit Control Transfer THÀNH CÔNG!")
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
                    try {
                        canvas.drawColor(Color.BLACK)
                        val w = canvas.width.toFloat()
                        val h = canvas.height.toFloat()

                        // Vẽ dải sọc màu OBS Studio SMPTE Test Pattern phủ kín 100% khung hình
                        val barColors = intArrayOf(
                            Color.rgb(191, 191, 191), // White/Grey
                            Color.rgb(191, 191, 0),   // Yellow
                            Color.rgb(0, 191, 191),   // Cyan
                            Color.rgb(0, 191, 0),     // Green
                            Color.rgb(191, 0, 191),   // Magenta
                            Color.rgb(191, 0, 0),     // Red
                            Color.rgb(0, 0, 191)      // Blue
                        )

                        val barWidth = w / barColors.size
                        val paint = Paint().apply { style = Paint.Style.FILL }

                        for (i in barColors.indices) {
                            paint.color = barColors[i]
                            canvas.drawRect(i * barWidth, 0f, (i + 1) * barWidth, h * 0.85f, paint)
                        }

                        // Phần đáy đen hiển thị thông báo trạng thái
                        paint.color = Color.BLACK
                        canvas.drawRect(0f, h * 0.85f, w, h, paint)

                        val textPaint = Paint().apply {
                            color = Color.WHITE
                            textSize = 34f
                            textAlign = Paint.Align.CENTER
                            isAntiAlias = true
                            isFakeBoldText = true
                        }
                        canvas.drawText("⚡ CHỜ TÍN HIỆU TỪ CAM HDMI / SONY A7...", w / 2f, h * 0.94f, textPaint)
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }
        } catch (e: Throwable) {}
    }

    fun stopStream() {
        isStreaming = false
        lastValidBitmap = null
        try {
            nativeBridge?.stop()
            nativeBridge = null
        } catch (e: Throwable) {}

        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Throwable) {}

        try {
            renderExecutor.shutdownNow()
        } catch (e: Throwable) {}

        usbInterface = null
        usbConnection = null
        logDebug("⏹ Đã dừng UVC Precision Render Engine.")
    }

    fun release() {
        stopStream()
    }
}
