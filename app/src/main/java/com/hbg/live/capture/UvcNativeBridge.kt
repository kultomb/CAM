package com.hbg.live.capture

import android.util.Log
import android.view.Surface

/**
 * JNI Native Bridge - Kết nối trực tiếp Kotlin UvcOfficialEngine tới C++ Native ISO Transfer Engine (libhbg_uvc.so).
 */
class UvcNativeBridge(
    private val listener: Listener
) {

    interface Listener {
        fun onNativeFrame(jpeg: ByteArray)
        fun onNativeError(message: String)
    }

    fun onNativeFrame(jpeg: ByteArray) {
        listener.onNativeFrame(jpeg)
    }

    fun onNativeError(message: String) {
        listener.onNativeError(message)
    }

    companion object {
        private const val TAG = "UvcNativeBridge"

        init {
            try {
                System.loadLibrary("hbg_uvc")
                Log.d(TAG, "🟢 Đã nạp thành công thư viện C++ Native: libhbg_uvc.so")
            } catch (e: Throwable) {
                Log.e(TAG, "Lỗi nạp thư viện hbg_uvc", e)
            }
        }
    }

    private external fun nativeStartEngine(fd: Int, ifaceId: Int, epAddr: Int, maxPacketSize: Int, altSetting: Int, surface: Surface?): Boolean
    private external fun nativeStopEngine()

    fun start(fd: Int, ifaceId: Int, epAddr: Int, maxPacketSize: Int, altSetting: Int, surface: Surface?): Boolean {
        Log.d(TAG, "nativeStartEngine(fd=$fd, iface=$ifaceId, epAddr=0x${Integer.toHexString(epAddr)}, maxPacketSize=$maxPacketSize, alt=$altSetting)")
        val result = try {
            nativeStartEngine(fd, ifaceId, epAddr, maxPacketSize, altSetting, surface)
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi nativeStartEngine", e)
            false
        }

        if (!result) {
            listener.onNativeError("Native UVC startEngine failed")
            return false
        }
        return true
    }

    fun stop() {
        try {
            nativeStopEngine()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeStopEngine failed", e)
        }
    }
}
