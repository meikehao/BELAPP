package com.belapp.batteryble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.belapp.batteryble.MainActivity
import com.belapp.batteryble.R
import com.belapp.batteryble.ble.BleManager
import com.belapp.batteryble.controller.ThresholdController
import com.belapp.batteryble.monitor.BatteryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 前台服务：锁屏/应用退出后，电池监控 + BLE 连接 + 阈值触发仍在后台持续运行。
 *
 * 启动：MainActivity 在 onCreate 时 startForegroundService()
 * 停止：用户在 UI 上点"停止后台监控"或系统回收
 *
 * 后台持续能力：
 * - WakeLock(PARTIAL_WAKE_LOCK) 保持 CPU 运行，Doze 模式下电池广播仍能送达
 * - Foreground Notification 使服务优先级提升，避免系统被杀
 * - BLE GATT 连接在 Service 生命周期内持续持有，Activity 退出不影响
 */
class BatteryBLEService : Service() {

    companion object {
        private const val TAG = "BatteryBLEService"
        private const val CHANNEL_ID = "battery_ble_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "BatteryBLEService:MonitorWakeLock"

        // ===== SharedPrefs 持久化 =====
        private const val PREFS_NAME = "battery_ble_prefs"
        private const val KEY_OPEN_THRESHOLD = "open_threshold"
        private const val KEY_CLOSE_THRESHOLD = "close_threshold"
        private const val KEY_LAST_BLE_ADDR = "last_ble_address"

        fun saveThresholds(context: Context, open: Int, close: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_OPEN_THRESHOLD, open)
                .putInt(KEY_CLOSE_THRESHOLD, close)
                .apply()
        }
        fun saveLastBleAddress(context: Context, addr: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_BLE_ADDR, addr)
                .apply()
        }
        fun getLastBleAddress(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_BLE_ADDR, null)
        }

        // 全局单例引用（Activity 通过它获取 Service 持有的核心对象）
        @Volatile var instance: BatteryBLEService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, BatteryBLEService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryBLEService::class.java))
        }
    }

    // 核心组件（Service 生命周期内一直存活）
    lateinit var batteryMonitor: BatteryMonitor
    lateinit var bleManager: BleManager
    lateinit var thresholdController: ThresholdController

    // WakeLock：锁屏时保持 CPU 运行
    private var wakeLock: PowerManager.WakeLock? = null

    // 后台协程 Scope（Activity 退出后仍存活）
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BatteryBLEService = this@BatteryBLEService
    }

    // 服务状态（让 Activity 知道服务是否已就绪）
    private val _serviceReady = MutableStateFlow(false)
    val serviceReady: StateFlow<Boolean> = _serviceReady.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BatteryBLEService.onCreate START")
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("初始化后台监控..."))

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire() }
        Log.d(TAG, "WakeLock acquired, held=${wakeLock?.isHeld}")

        batteryMonitor = BatteryMonitor(applicationContext)
        bleManager = BleManager(applicationContext)
        thresholdController = ThresholdController(batteryMonitor, bleManager)

        // ===== 恢复持久化的阈值 =====
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedOpen = prefs.getInt(KEY_OPEN_THRESHOLD, 0)   // 0=没保存过
        val savedClose = prefs.getInt(KEY_CLOSE_THRESHOLD, 0)
        if (savedOpen > 0 && savedClose > 0 && savedOpen > savedClose) {
            thresholdController.openThreshold = savedOpen
            thresholdController.closeThreshold = savedClose
            Log.d(TAG, "Restored thresholds: open=$savedOpen%, close=$savedClose%")
        }

        // Doze 下 BleManager 的 Handler 会被冻结，
        // 用 BatteryMonitor 的 AlarmManager tick（Doze 下可靠）每 ~30秒检查一次 BLE
        batteryMonitor.bleReconnectCheck = {
            bleManager.tryReconnectIfNeeded()
        }

        batteryMonitor.startMonitoring(2000)
        Log.d(TAG, "BatteryMonitor.startMonitoring called, bleReconnectCheck injected")

        thresholdController.start()
        Log.d(TAG, "ThresholdController.start called")

        // 【自动连接】优先连用户上次手动连的设备（持久化 MAC）
        val fallbackMac = "D6:1F:CE:F7:F9:39"
        val autoMac = prefs.getString(KEY_LAST_BLE_ADDR, fallbackMac) ?: fallbackMac
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Auto-connecting to saved device $autoMac")
            bleManager.connectByAddress(autoMac)
        }, 2000)

        // 阈值触发回调 → 更新通知
        thresholdController.onTriggered = { ev ->
            runCatching {
                val type = if (ev.type == ThresholdController.TriggerType.OPEN) "开" else "关"
                val status = if (ev.sentSuccess) "✅已发送" else "⚠未连接"
                updateNotification("阈值触发：$type ($status) @ ${ev.level}%")
            }
        }

        // 电量变化时更新通知
        batteryMonitor.statusFlow
            .onEach { status ->
                val pct = status.level
                val pSign = if (status.powerMw >= 0) "+" else ""
                val pW = status.powerMw / 1000.0
                updateNotification("电量 $pct% · ${pSign}%.2f W · 阈值开${thresholdController.openThreshold}%/关${thresholdController.closeThreshold}%")
            }
            .launchIn(serviceScope)

        _serviceReady.value = true
        Log.d(TAG, "BatteryBLEService started, WakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：如果服务被杀，系统尝试重新创建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "BatteryBLEService destroyed")
        serviceScope.coroutineContext[Job]?.cancel()
        thresholdController.stop()
        batteryMonitor.stopMonitoring()
        bleManager.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "电量蓝牙后台监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "锁屏/退出后仍持续监控电量并自动触发蓝牙开关"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("电量蓝牙监控运行中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    // ===== 便捷方法（Activity 通过 service 实例直接调用） =====

    fun setThresholds(open: Int, close: Int) {
        thresholdController.openThreshold = open
        thresholdController.closeThreshold = close
    }

    fun sendManualSwitch(on: Boolean): Boolean = bleManager.sendSwitch(on)

    fun connectDevice(address: String) {
        // 从已知地址重连
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val device = adapter?.getRemoteDevice(address)
        device?.let { bleManager.connect(it) }
    }

    fun isServiceAlive(): Boolean = instance != null && _serviceReady.value
}
