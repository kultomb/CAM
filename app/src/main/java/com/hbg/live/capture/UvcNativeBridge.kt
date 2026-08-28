package com.hbg.live.capture

import android.util.Log
import android.view.Surface

class UvcNativeBridge(
    private val listener: Listener
) {

    interface Listener {
        fun onNativeFrame(jpeg: ByteArray)
        fun onNativeError(message: String)
    }

    companion object {
        private const val TAG = "UvcNativeBridge"

        init {
            try {
                System.loadLibrary("hbg_uvc")
            } catch (e: Throwable) {
                Log.e(TAG, "Lỗi nạp thư viện hbg_uvc", e)
            }
        }
    }

    private external fun nativeStart(fd: Int, epAddr: Int, maxPacketSize: Int, altSetting: Int, surface: Surface?): Int
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean

    fun start(fd: Int, epAddr: Int, maxPacketSize: Int, altSetting: Int, surface: Surface?): Boolean {
        Log.d(TAG, "nativeStart(fd=$fd, epAddr=0x${Integer.toHexString(epAddr)}, maxPacketSize=$maxPacketSize, alt=$altSetting)")
        val result = try {
            nativeStart(fd, epAddr, maxPacketSize, altSetting, surface)
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi nativeStart", e)
            -99
        }

        if (result != 0) {
            listener.onNativeError("Native UVC start failed: $result")
            return false
        }
        return true
    }

    fun stop() {
        try {
            nativeStop()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeStop failed", e)
        }
    }

    fun isRunning(): Boolean {
        return try {
            nativeIsRunning()
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("unused")
    fun onNativeFrame(jpeg: ByteArray) {
        listener.onNativeFrame(jpeg)
    }
}
