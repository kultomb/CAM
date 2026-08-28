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

/**
 * UVC Precision EOI Render Engine - Nạp Khung Hình Bất Đồng Bộ Qua Render Executor Dedicated Thread.
 * Tách biệt 100% luồng đọc USB ISOC và luồng Decode/Render SurfaceView giúp loại bỏ triệt để nghẽn luồng và nháy màn hình.
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
                // Tách biệt luồng nhận USB bất đồng bộ: Nếu luồng decode đang bận, bỏ qua khung để luồng USB không bao giờ bị nghẽn
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
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
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
                        val cw = canvas.width.toFloat()
                        val ch = canvas.height.toFloat()
                        val bw = bitmapToDraw.width.toFloat()
                        val bh = bitmapToDraw.height.toFloat()

                        val scale = minOf(cw / bw, ch / bh)
                        val dw = bw * scale
                        val dh = bh * scale
                        val left = (cw - dw) / 2f
                        val top = (ch - dh) / 2f

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
