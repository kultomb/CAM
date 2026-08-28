package com.hbg.live.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * ThermalController - Bộ Kiểm Soát Nhiệt Độ Thích Ứng (Mát Máy 32°C - 36°C)
 * Tự động theo dõi nhiệt độ điện thoại và điều chỉnh FPS/Bitrate của Encoder H.264
 * để giữ máy luôn mát rượi, không bao giờ bị nóng máy hay quá nhiệt khi phát livestream lâu.
 */
class ThermalController(
    private val context: Context,
    private val listener: ThermalListener
) {
    enum class ThermalState(val maxFps: Int, val targetBitrateKbps: Int, val description: String) {
        NORMAL(60, 6000, "Mát máy (<38°C) - 1080p60 @ 6.0 Mbps"),
        WARM(60, 4500, "Ấm máy (38-42°C) - 1080p60 @ 4.5 Mbps"),
        HOT(30, 3500, "Nóng máy (42-45°C) - 1080p30 @ 3.5 Mbps"),
        CRITICAL(30, 2500, "Rất nóng (>45°C) - 720p30 @ 2.5 Mbps")
    }

    interface ThermalListener {
        fun onThermalStateChanged(state: ThermalState)
    }

    var currentState: ThermalState = ThermalState.NORMAL
        private set

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var thermalListenerApi29: Any? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (Intent.ACTION_BATTERY_CHANGED == intent.action) {
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempCelsius = tempTenths / 10.0f
                evaluateTemperature(tempCelsius)
            }
        }
    }

    companion object {
        private const val TAG = "ThermalController"
    }

    fun startMonitoring() {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi đăng ký Battery Receiver", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    val newState = when (status) {
                        PowerManager.THERMAL_STATUS_NONE,
                        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.NORMAL
                        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.WARM
                        PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HOT
                        else -> ThermalState.CRITICAL
                    }
                    updateState(newState)
                }
                powerManager.addThermalStatusListener(listener)
                thermalListenerApi29 = listener
            } catch (e: Throwable) {
                Log.e(TAG, "Lỗi đăng ký ThermalStatusListener API 29", e)
            }
        }
    }

    private fun evaluateTemperature(tempCelsius: Float) {
        val newState = when {
            tempCelsius < 38.0f -> ThermalState.NORMAL
            tempCelsius < 42.0f -> ThermalState.WARM
            tempCelsius < 45.0f -> ThermalState.HOT
            else -> ThermalState.CRITICAL
        }
        updateState(newState)
    }

    private fun updateState(newState: ThermalState) {
        if (currentState != newState) {
            currentState = newState
            StudioLogger.log(TAG, "🌡️ Nhiệt độ thay đổi -> ${newState.description}")
            listener.onThermalStateChanged(newState)
        }
    }

    fun stopMonitoring() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Throwable) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListenerApi29 != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                powerManager.removeThermalStatusListener(thermalListenerApi29 as PowerManager.OnThermalStatusChangedListener)
            } catch (_: Throwable) {}
            thermalListenerApi29 = null
        }
    }
}
