package com.hbg.live.stream

import android.util.Log

/**
 * Engine phát luồng SRT (Low-latency Secure Reliable Transport - Caller Mode).
 */
class SrtStreamerEngine {
    private var isConnected = false

    fun connect(host: String, port: Int, latencyMs: Int = 120) {
        Log.d(TAG, "Kết nối SRT Streamer -> srt://$host:$port?latency=$latencyMs")
        isConnected = true
    }

    fun disconnect() {
        isConnected = false
        Log.d(TAG, "Đã ngắt kết nối SRT Streamer")
    }

    companion object {
        private const val TAG = "SrtStreamerEngine"
    }
}
