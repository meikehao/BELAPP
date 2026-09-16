package com.belapp.batteryble

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.belapp.batteryble.ble.BleManager
import com.belapp.batteryble.controller.ThresholdController
import com.belapp.batteryble.databinding.ActivityMainBinding
import com.belapp.batteryble.monitor.BatteryMonitor
import com.belapp.batteryble.service.BatteryBLEService
import com.belapp.batteryble.ui.BleDeviceAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: BleDeviceAdapter

    // 核心组件从 Foreground Service 拿（Service 在锁屏/退出后仍持续运行）
    // Activity 只做 UI 壳，不持有核心组件生命周期
    private val batteryMonitor: BatteryMonitor get() = BatteryBLEService.instance!!.batteryMonitor
    private val bleManager: BleManager get() = BatteryBLEService.instance!!.bleManager
    private val thresholdController: ThresholdController get() = BatteryBLEService.instance!!.thresholdController

    // 权限申请
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.all { it.value }
        if (!allGranted) {
            toast("部分权限被拒绝，蓝牙和电量信息可能无法工作")
        } else {
            toast("权限已授予")
        }
    }

    // 蓝牙开关申请
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bleManager.isBluetoothEnabled()) {
            toast("蓝牙已开启")
        } else {
            toast("请先开启蓝牙")
        }
    }

    @SuppressLint("SetTextI18n", "MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNecessaryPermissions()

        // 启动前台 Service（锁屏/退出后仍持续运行）
        BatteryBLEService.start(this)

        // 等 Service 就绪后再绑定 UI（组件都在 Service 里持有）
        lifecycleScope.launch {
            // 轮询等 Service 实例创建 + ready
            while (BatteryBLEService.instance == null || !BatteryBLEService.instance!!.serviceReady.value) {
                kotlinx.coroutines.delay(100)
            }
            bindUi()
        }
    }

    /** Service ready 后再绑定所有 UI 事件 + Flow collect */
    private fun bindUi() {
        // RecyclerView
        deviceAdapter = BleDeviceAdapter { dev ->
            bleManager.stopScan()
            bleManager.connect(dev.raw)
            toast("正在连接 ${dev.name ?: dev.address} ...")
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter

        // 蓝牙状态圆点
        (binding.vBleStatusDot.background as? GradientDrawable)?.let { it.shape = GradientDrawable.OVAL }

        // ===== 回填阈值到 EditText（Activity 重建时不会丢）=====
        val savedOpen = thresholdController.openThreshold
        val savedClose = thresholdController.closeThreshold
        binding.etOpenThreshold.setText(savedOpen.toString())
        binding.etCloseThreshold.setText(savedClose.toString())
        binding.tvThresholdInfo.text =
            "当前：充到 ${savedOpen}% 自动关（停充电），掉到 ${savedClose}% 自动开（开始充电）"

        // ===== 键盘自动收起 =====
        // 只有一处：点"应用阈值"时主动收键盘 + clearFocus，避免光标残留
        // Android 原生机制已处理好：点 EditText 聚焦弹键盘、点空白失焦自动收键盘

        bindButtonListeners()
        startObserving()

        toast("后台监控已启动（锁屏/退出后仍保持运行）")
    }

    private fun bindButtonListeners() {
        binding.btnScan.setOnClickListener {
            if (!checkBlePreconditions()) return@setOnClickListener
            binding.rvDevices.post {
                bleManager.startScan()
                toast("开始扫描蓝牙设备（10秒）")
            }
        }
        binding.btnDisconnect.setOnClickListener {
            bleManager.disconnect()
            toast("已断开")
        }
        binding.btnSetThreshold.setOnClickListener {
            // 先收起键盘、clear focus，避免光标残留
            hideKeyboard()
            currentFocus?.clearFocus()

            val openTxt = binding.etOpenThreshold.text?.toString()?.trim()
            val closeTxt = binding.etCloseThreshold.text?.toString()?.trim()
            val openV = openTxt?.toIntOrNull()?.coerceIn(1, 100)
            val closeV = closeTxt?.toIntOrNull()?.coerceIn(1, 100)
            if (openV == null || closeV == null) {
                toast("请输入 1-100 之间的整数")
                return@setOnClickListener
            }
            if (openV <= closeV) {
                toast("开阈值必须大于关阈值")
                return@setOnClickListener
            }
            thresholdController.openThreshold = openV
            thresholdController.closeThreshold = closeV
            // 持久化阈值，App 重启后自动恢复
            BatteryBLEService.saveThresholds(this, openV, closeV)
            binding.tvThresholdInfo.text =
                "当前：充到 ${openV}% 自动关（停充电），掉到 ${closeV}% 自动开（开始充电）"
            toast("已设置：高${openV}%关 / 低${closeV}%开")
        }
        // 电流单位切换（晨钟酱实践：让用户自己选最可靠）
        binding.btnUnitToggle.setOnClickListener {
            val next = if (batteryMonitor.currentUnit == BatteryMonitor.CurrentUnit.MA)
                BatteryMonitor.CurrentUnit.UA else BatteryMonitor.CurrentUnit.MA
            batteryMonitor.currentUnit = next
            binding.btnUnitToggle.text = "单位: ${if (next == BatteryMonitor.CurrentUnit.MA) "mA" else "μA"}"
            val hint = if (next == BatteryMonitor.CurrentUnit.MA)
                "已切换为 mA 模式（大部分机型适用）" else "已切换为 μA 模式（涓流/微小电流时更准确）"
            toast(hint)
        }

        binding.btnTestSend.setOnClickListener {
            if (!bleManager.isConnected()) {
                toast("蓝牙未连接")
                return@setOnClickListener
            }
            val ok = bleManager.sendSwitch(true)
            toast(if (ok) "已发送开充电指令" else "发送失败")
        }
        binding.btnSendStatus.setOnClickListener {
            if (!bleManager.isConnected()) {
                toast("蓝牙未连接")
                return@setOnClickListener
            }
            val ok = bleManager.sendSwitch(false)
            toast(if (ok) "已发送关充电指令" else "发送失败")
        }
    }

    private fun startObserving() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    batteryMonitor.statusFlow.collect { s ->
                        val pct = s.level
                        binding.tvBatteryLevel.text = "$pct"
                        binding.pbBattery.progress = pct

                        val chargingText = when {
                            s.isCharging && s.chargeType == "AC" -> "⚡ 充电中(AC)"
                            s.isCharging && s.chargeType == "USB" -> "⚡ 充电中(USB)"
                            s.isCharging && s.chargeType == "WIRELESS" -> "⚡ 无线充电"
                            else -> "未充电"
                        }
                        binding.tvChargingStatus.text = chargingText

                        // 功率：晨钟酱风格——W 为大数字居中
                        val powerW = batteryMonitor.getPowerW()
                        val pSign = if (powerW >= 0) "" else "-"
                        val colorP = if (s.isCharging) "#2B7D3A" else (if (powerW >= 0) "#222222" else "#C0392B")
                        binding.tvPowerW.text = "${pSign}%.2f W".format(Math.abs(powerW))
                        binding.tvPowerW.setTextColor(android.graphics.Color.parseColor(colorP))

                        // 电流：按用户选择的单位显示
                        val (curVal, curUnit) = batteryMonitor.getDisplayCurrent()
                        val cSign = if (curVal >= 0) "" else "-"
                        val curFmt = if (curUnit == "μA") "%.0f" else "%.1f"
                        binding.tvCurrent.text = "${cSign}${curFmt.format(Math.abs(curVal))} $curUnit"

                        // 电压
                        binding.tvVoltage.text = "${s.voltage} mV"

                        // 上下文说明（充电/放电时切换）
                        binding.tvPowerContext.text = if (s.isCharging)
                            "充电中 · 电池输入功率（< 整机输入）" else
                            "未充电 · 整机功耗"

                        // 电池信息（底部小字）
                        val healthText = when (s.health) {
                            "GOOD" -> "健康良好"
                            "OVERHEAT" -> "温度过高"
                            "DEAD" -> "电池损坏"
                            "COLD" -> "温度过低"
                            else -> s.health
                        }
                        val dualTag = if (s.sourceIsDualBattery) " · 双电芯" else ""
                        binding.tvBatteryTech.text =
                            "${s.technology} · $healthText$dualTag · ${s.temperature / 10.0}°C"
                    }
                }
                launch {
                    bleManager.scanResults.collect { list ->
                        deviceAdapter.update(list)
                        binding.tvDeviceCount.text = "已发现 ${list.size} 台设备"
                    }
                }
                launch {
                    bleManager.connectionState.collect { state ->
                        val (text, colorHex) = when (state) {
                            BleManager.ConnectionState.DISCONNECTED -> "未连接" to "#CCCCCC"
                            BleManager.ConnectionState.CONNECTING -> "连接中..." to "#E8A63F"
                            BleManager.ConnectionState.CONNECTED -> "已连接" to "#22A65B"
                            BleManager.ConnectionState.DISCOVERING_SERVICES -> "发现服务..." to "#E8A63F"
                        }
                        binding.tvBleStatus.text = text
                        (binding.vBleStatusDot.background as? GradientDrawable)
                            ?.setColor(android.graphics.Color.parseColor(colorHex))
                        // 连接/断开时刷新设备状态区
                        renderConnectedDeviceInfo()
                    }
                }
                launch {
                    bleManager.isScanning.collect { scanning ->
                        binding.btnScan.text = if (scanning) "扫描中..." else "扫描"
                        binding.btnScan.isEnabled = !scanning
                    }
                }
                // 连通性状态：Read F001 → "hello"，Notify → "connected"
                launch { bleManager.readText.collect { renderConnectedDeviceInfo() } }
                launch { bleManager.notifyText.collect { renderConnectedDeviceInfo() } }
            }
        }
    }

    /** 在设备状态区显示连接设备、Read(hello) 与 Notify(connected) 状态 */
    private fun renderConnectedDeviceInfo() {
        val r = bleManager.readText.value
        val n = bleManager.notifyText.value
        val sb = StringBuilder()
        if (bleManager.isConnected()) sb.append("已连接设备：${BleManager.DEVICE_NAME}")
        if (r.isNotEmpty()) sb.append("\n连通自检(Read)：$r")
        if (n.isNotEmpty()) sb.append("\n设备通知(Notify)：$n")
        binding.tvConnectedDevice.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        // Service 已经在跑，Activity 只做 UI 显示
        if (BatteryBLEService.instance == null) {
            BatteryBLEService.start(this)
        }
    }

    // onPause/onDestroy 都不停止 Service 的监控！
    // 电池监控 + BLE 连接 + 阈值触发 全部由 Foreground Service 持续运行

    override fun onDestroy() {
        super.onDestroy()
        // Activity 退出时 BLE GATT 连接由 Service 持有，不会断
        // 这里可以什么都不做，Service 继续跑
    }

    // ---- 权限 & 蓝牙开关 ----

    private fun requestNecessaryPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            // 去掉 neverForLocation 后，扫描完整设备需位置权限（部分 ROM 不授权会过滤扫描结果）
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        perms.add(Manifest.permission.FOREGROUND_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkBlePreconditions(): Boolean {
        if (!bleManager.isBluetoothAvailable()) {
            toast("设备不支持低功耗蓝牙")
            return false
        }
        if (!bleManager.isBluetoothEnabled()) {
            promptEnableBluetooth()
            return false
        }
        if (!bleManager.hasPermission()) {
            requestNecessaryPermissions()
            return false
        }
        return true
    }

    private fun promptEnableBluetooth() {
        runCatching {
            val intent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(intent)
        }.onFailure { toast("无法打开蓝牙开关界面，请手动开启蓝牙") }
    }

    // ---- UI 辅助 ----

    private fun hideKeyboard(v: View? = null) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val target = v ?: currentFocus
        target?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
