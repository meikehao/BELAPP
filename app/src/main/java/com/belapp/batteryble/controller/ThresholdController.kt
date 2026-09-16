package com.belapp.batteryble.controller

import android.util.Log
import com.belapp.batteryble.ble.BleManager
import com.belapp.batteryble.monitor.BatteryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

    /**
     * 电量阈值触发控制器（充电控制模式）：
     *   highThreshold  —— 电量上升到此值时自动发「关」指令（0x30）→ 停止充电
     *   lowThreshold   —— 电量下降到此值时自动发「开」指令（0x31）→ 开始充电
     *
     * 每个阈值独立追踪状态，避免重复触发；只有电量反向穿越阈值+滞后后才允许再次触发。
     *
     * 【指令语义】
     *   sendSwitch(true)  → CMD_ON  = 0x31 → GPIO3→3.3V → 继电器吸合 → 开始充电
     *   sendSwitch(false) → CMD_OFF = 0x30 → GPIO3→0V   → 继电器释放 → 停止充电
     */
    class ThresholdController(
    private val batteryMonitor: BatteryMonitor,
    private val bleManager: BleManager
) {
    companion object {
        private const val TAG = "ThresholdController"
        private const val DEFAULT_OPEN_THRESHOLD = 80   // 默认 80% 自动开
        private const val DEFAULT_CLOSE_THRESHOLD = 20  // 默认 20% 自动关
        private const val HYSTERESIS = 5                // 滞后：电量反向移动 5% 后才允许再次触发
    }

    /** 触发事件类型 */
    enum class TriggerType { OPEN, CLOSE }

    data class TriggerEvent(
        val type: TriggerType,
        val threshold: Int,
        val level: Int,
        val timeMs: Long,
        val sentSuccess: Boolean
    )

    val eventLog: MutableList<TriggerEvent> = mutableListOf()

    /** 开阈值：电量 ≥ 此值时发送「开」指令 */
    var openThreshold: Int = DEFAULT_OPEN_THRESHOLD
        set(value) {
            field = value.coerceIn(1, 100)
            openTriggered = false   // 重置状态，让下次上升重新触发
            Log.d(TAG, "openThreshold set to $field")
        }

    /** 关阈值：电量 ≤ 此值时发送「关」指令 */
    var closeThreshold: Int = DEFAULT_CLOSE_THRESHOLD
        set(value) {
            field = value.coerceIn(1, 100)
            closeTriggered = false
            Log.d(TAG, "closeThreshold set to $field")
        }

    // 各自独立的已触发标记
    private var openTriggered = false
    private var closeTriggered = false

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var collectJob: Job? = null

    var onTriggered: ((TriggerEvent) -> Unit)? = null

    fun start() {
        collectJob?.cancel()
        collectJob = batteryMonitor.statusFlow
            .onEach { status -> onBatteryStatus(status.level) }
            .launchIn(scope)
        Log.d(TAG, "ThresholdController started, open=$openThreshold%, close=$closeThreshold%")
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    /** 手动触发一次检查（用于测试） */
    fun triggerManually(level: Int) {
        onBatteryStatus(level)
    }

    private fun onBatteryStatus(level: Int) {
        Log.d(TAG, "CHECK: level=$level high=$openThreshold low=$closeThreshold highTrig=$openTriggered lowTrig=$closeTriggered")
        // 高阈值：电量上升越过阈值 → 发「关」（停止充电）
        if (level >= openThreshold && !openTriggered && openThreshold > closeThreshold) {
            Log.d(TAG, "  → TRIGGER STOP CHARGE ($level >= $openThreshold, send OFF 0x30)")
            val sent = sendSwitch(false)   // 关充电
            val event = TriggerEvent(TriggerType.OPEN, openThreshold, level,
                System.currentTimeMillis(), sent)
            recordEvent(event)
            openTriggered = true
        }
        // 高阈值复位：电量下降到 openThreshold - HYSTERESIS 以下，下次上升可再次触发
        if (level < openThreshold - HYSTERESIS) {
            if (openTriggered) Log.d(TAG, "  → high re-armed (level=$level < ${openThreshold - HYSTERESIS})")
            openTriggered = false
        }

        // 低阈值：电量下降越过阈值 → 发「开」（开始充电）
        if (level <= closeThreshold && !closeTriggered && closeThreshold < openThreshold) {
            Log.d(TAG, "  → TRIGGER START CHARGE ($level <= $closeThreshold, send ON 0x31)")
            val sent = sendSwitch(true)    // 开充电
            val event = TriggerEvent(TriggerType.CLOSE, closeThreshold, level,
                System.currentTimeMillis(), sent)
            recordEvent(event)
            closeTriggered = true
        }
        // 低阈值复位：电量上升到 closeThreshold + HYSTERESIS 以上，下次下降可再次触发
        if (level > closeThreshold + HYSTERESIS) {
            if (closeTriggered) Log.d(TAG, "  → low re-armed (level=$level > ${closeThreshold + HYSTERESIS})")
            closeTriggered = false
        }
    }

    private fun sendSwitch(on: Boolean): Boolean {
        if (!bleManager.isConnected()) {
            Log.w(TAG, "Threshold reached but BLE not connected, skip sending")
            return false
        }
        val sent = bleManager.sendSwitch(on)
        Log.d(TAG, "Sent ${if (on) "ON(0x31)" else "OFF(0x30)"}, result=$sent")
        return sent
    }

    private fun recordEvent(event: TriggerEvent) {
        eventLog.add(0, event)
        if (eventLog.size > 100) eventLog.removeLast()
        onTriggered?.invoke(event)
    }
}
