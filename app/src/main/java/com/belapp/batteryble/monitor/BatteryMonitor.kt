package com.belapp.batteryble.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.FileReader

/**
 * 电池状态监控器
 * 负责获取：电量、功率（充电/放电电流）、SOC使用率
 *
 * 【锁屏/Doze 模式兼容方案】
 *   很多 OEM（OPPO/小米/华为）锁屏后进入深度 Doze，前台 Service 的协程 Dispatchers 会被限流。
 *   因此本类用 AlarmManager + setAndAllowWhileIdle() 做定时触发，配合 PARTIAL_WAKE_LOCK
 *   确保 CPU 被强制唤醒后再执行采样。这是 Android Doze 模式下最可靠的后台定时方案。
 */
class BatteryMonitor(private val context: Context) {

    companion object {
        private const val TAG = "BatteryMonitor"
        private const val ACTION_TICK = "com.belapp.batteryble.MONITOR_TICK"
        private const val REQUEST_CODE_TICK = 0x1234
    }

    /** 电流原始单位 */
    enum class CurrentUnit { UA, MA }

    data class BatteryStatus(
        val level: Int = 0,
        val scale: Int = 100,
        val isCharging: Boolean = false,
        val chargeType: String = "NONE",
        val voltage: Int = 0,
        val temperature: Int = 0,
        val currentMicroAmp: Long = 0L,
        val powerMw: Double = 0.0,
        val socUsage: Float = 0f,
        val health: String = "UNKNOWN",
        val technology: String = "Li-ion",
        val sourceIsDualBattery: Boolean = false
    )

    private val _statusFlow = MutableStateFlow(BatteryStatus())
    val statusFlow: StateFlow<BatteryStatus> = _statusFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    var currentUnit: CurrentUnit = CurrentUnit.MA

    // 当前采样间隔（ms）
    private var intervalMs: Long = 2000L

    // ===== BLE 重连回调（由 BatteryBLEService 注入）=====
    // Doze 下 BleManager 的 Handler.postDelayed 被冻结，改用 AlarmManager tick 检查
    var bleReconnectCheck: (() -> Unit)? = null
    private var tickCount = 0
    private val RECONNECT_CHECK_INTERVAL = 5  // 每 5 个 tick（约 10 秒）检查一次 BLE

    // 闹钟用的 PendingIntent（Doze 下 AlarmManager 触发后，receiver 自动收这个 action）
    private val tickIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_TICK).apply {
            setPackage(context.packageName)  // 仅限本 App 接收
        }
        PendingIntent.getBroadcast(
            context, REQUEST_CODE_TICK, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // 闹钟触发的 BroadcastReceiver（Doze 下能被 AlarmManager 唤醒）
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_TICK) return
            Log.d(TAG, "tickReceiver.onReceive WAKEUP")
            // 拿短 WakeLock 确保采样期间 CPU 不进入休眠
            val wl = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BatteryMonitor:TickWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(3000)
            }
            try {
                updateHardwareMetrics()
            } catch (e: Exception) {
                Log.e(TAG, "tick update error", e)
            } finally {
                if (wl.isHeld) wl.release()
                // 每 5 个 tick（~10秒）检查一次 BLE 连接状态
                tickCount++
                if (tickCount % RECONNECT_CHECK_INTERVAL == 0) {
                    Log.d(TAG, "tick #$tickCount → bleReconnectCheck")
                    try {
                        bleReconnectCheck?.invoke()
                            ?: Log.d(TAG, "tick #$tickCount → bleReconnectCheck is null (not injected yet)")
                    } catch (e: Exception) {
                        Log.e(TAG, "bleReconnectCheck error", e)
                    }
                }
                scheduleNextTick()
            }
        }
    }

    // 电池状态变化广播（电量百分比/充电状态变化时立即更新）
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            updateFromBatteryIntent(intent)
        }
    }

    // ===== Mock 模式 =====
    var mockMode: Boolean = false
        private set

    fun setMockStatus(
        level: Int,
        isCharging: Boolean = true,
        chargeType: String = if (isCharging) "USB" else "NONE",
        voltage: Int = 4200,
        currentMicroAmp: Long = if (isCharging) 800_000L else -300_000L,
        temperature: Int = 320
    ) {
        val capacity = 3000.0
        val voltageV = voltage / 1000.0
        val currentMa = currentMicroAmp / 1000.0
        val powerMw = currentMa * voltageV
        val socUsage = (Math.abs(currentMa) / capacity * 100).coerceIn(0.0, 100.0).toFloat()
        _statusFlow.value = BatteryStatus(
            level = level.coerceIn(0, 100),
            scale = 100,
            isCharging = isCharging,
            chargeType = chargeType,
            voltage = voltage,
            temperature = temperature,
            currentMicroAmp = currentMicroAmp,
            powerMw = powerMw,
            socUsage = socUsage,
            health = "GOOD",
            technology = "Li-ion (Mock)"
        )
    }

    fun setMockMode(enabled: Boolean) {
        mockMode = enabled
        if (enabled) stopMonitoring()
    }

    /**
     * 启动后台监控：
     * 1. 注册 ACTION_BATTERY_CHANGED 广播（电量百分比变化时立即更新）
     * 2. AlarmManager.setAndAllowWhileIdle() 定时触发（Doze 下强制唤醒 CPU 采样电流/功率）
     *    setAndAllowWhileIdle 在 Doze 期间间隔会被系统限制到 ≥9分钟；
     *    如果需要更频繁（比如 2 秒），需要用 setExactAndAllowWhileIdle，
     *    但系统会对 exact alarm 做批处理限制，非应用特别重要不推荐。
     *    结论：前台 Service + setAndAllowWhileIdle + PARTIAL_WAKE_LOCK 已足够。
     */
    fun startMonitoring(intervalMs: Long = 2000) {
        android.util.Log.d(TAG, "startMonitoring called interval=$intervalMs, mock=$mockMode")
        if (mockMode) return
        this.intervalMs = intervalMs

        // 1. 电池状态广播
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(batteryReceiver, filter)
            }
            sticky?.let { updateFromBatteryIntent(it) }
        } catch (e: Exception) {
            Log.e(TAG, "register batteryReceiver failed", e)
        }

        // 2. 闹钟触发（Doze 下可靠）
        try {
            val filter = IntentFilter(ACTION_TICK)
            context.registerReceiver(tickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.e(TAG, "register tickReceiver failed", e)
        }
        scheduleNextTick()
        Log.d(TAG, "startMonitoring interval=${intervalMs}ms, using AlarmManager+WakeLock")
    }

    private fun scheduleNextTick() {
        val triggerTime = System.currentTimeMillis() + intervalMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, tickIntent
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, tickIntent)
            }
            Log.d(TAG, "Alarm scheduled in ${intervalMs}ms (setAndAllowWhileIdle)")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleNextTick failed", e)
        }
    }

    fun stopMonitoring() {
        try {
            alarmManager.cancel(tickIntent)
        } catch (_: Exception) {}
        try {
            context.unregisterReceiver(tickReceiver)
        } catch (_: Exception) {}
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
    }

    private fun updateFromBatteryIntent(intent: Intent) {
        val current = _statusFlow.value
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val healthCode = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: current.technology

        val isCharging = plugged != 0
        val chargeType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "NONE"
        }

        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }

        _statusFlow.value = current.copy(
            level = level,
            scale = scale,
            isCharging = isCharging,
            chargeType = chargeType,
            voltage = voltage,
            temperature = temperature,
            health = health,
            technology = tech
        )
    }

    private fun updateHardwareMetrics() {
        val current = _statusFlow.value

        // ===== 先主动读一次最新 level（不管 ACTION_BATTERY_CHANGED 有没有发）=====
        // 关键：ThresholdController 只看 level。如果电量卡在一个值不动，
        // ACTION_BATTERY_CHANGED 广播不会发 → statusFlow 不更新 → 阈值永远不触发
        val latestLevel = runCatching {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, current.level) ?: current.level
        }.getOrDefault(current.level)
        val latestCharging = runCatching {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }.getOrDefault(current.isCharging)
        val latestVoltage = runCatching {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, current.voltage) ?: current.voltage
        }.getOrDefault(current.voltage)

        // ===== 电流/功率（保留原有逻辑）=====
        val unsignedMicro = readCurrentRaw()
        val currentMicroAmp = if (latestCharging) Math.abs(unsignedMicro) else -Math.abs(unsignedMicro)

        val voltageV = latestVoltage / 1000.0
        val currentMa = currentMicroAmp / 1000.0
        val powerMw = if (voltageV > 0) currentMa * voltageV else 0.0

        val capacity = readDesignCapacity().takeIf { it > 0 } ?: 3000.0
        val socUsage = if (capacity > 0) {
            (Math.abs(currentMa) / capacity * 100).coerceIn(0.0, 100.0).toFloat()
        } else 0f

        val newStatus = current.copy(
            level = latestLevel,
            isCharging = latestCharging,
            voltage = latestVoltage,
            currentMicroAmp = currentMicroAmp,
            powerMw = powerMw,
            socUsage = socUsage,
            sourceIsDualBattery = checkDualBattery()
        )
        val changed = newStatus != current
        Log.d(TAG, "tick: ${latestLevel}%, ${powerMw / 1000.0}W, emit=$changed")
        _statusFlow.value = newStatus
    }

    private fun readCurrentRaw(): Long {
        val raw = runCatching {
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(0L)

        val useValue = raw.takeIf { it != 0L }
            ?: readSysfsRaw("/sys/class/power_supply/battery/current_now")
            ?: readSysfsRaw("/sys/class/power_supply/bms/current_now")
            ?: 0L

        return when (currentUnit) {
            CurrentUnit.UA -> useValue
            CurrentUnit.MA -> useValue * 1000
        }
    }

    private fun checkDualBattery(): Boolean {
        // 双电芯唯一可靠证据：/sys/class/power_supply/ 下有 >= 2 颗 battery 节点
        // (例：battery + battery2，华为 Pura70 Pro 双电芯是这样)
        // 注意：bms 目录高通单电芯也会有，不能作为双电芯证据！
        return runCatching {
            val count = java.io.File("/sys/class/power_supply").listFiles()
                ?.count { it.name.startsWith("battery") }
                ?: 0
            count >= 2
        }.getOrDefault(false)
    }

    fun getDisplayCurrent(unit: CurrentUnit = this.currentUnit): Pair<Double, String> {
        val micro = _statusFlow.value.currentMicroAmp
        return when (unit) {
            CurrentUnit.UA -> micro.toDouble() to "μA"
            CurrentUnit.MA -> micro / 1000.0 to "mA"
        }
    }

    fun getPowerW(): Double = _statusFlow.value.powerMw / 1000.0

    private fun readDesignCapacity(): Double {
        val fromManager = runCatching {
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrDefault(0L)
        if (fromManager > 0) return fromManager.toDouble()

        val microAh = readSysfsRaw("/sys/class/power_supply/battery/charge_full_design")
            ?: readSysfsRaw("/sys/class/power_supply/battery/full_design_charge")
            ?: return 3000.0
        return microAh / 1000.0
    }

    private fun readSysfsRaw(path: String): Long? {
        return try {
            BufferedReader(FileReader(path)).use { br ->
                br.readLine()?.trim()?.toLongOrNull()
            }
        } catch (_: Exception) { null }
    }
}
