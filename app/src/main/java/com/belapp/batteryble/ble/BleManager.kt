package com.belapp.batteryble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE（低功耗蓝牙）管理器
 * 负责：扫描设备、连接GATT、写入特征值（发送电量ID）
 *
 * 默认 GATT Profile（如需对接特定硬件，请修改下方 UUID）：
 *   Service UUID:        0000FFE0-0000-1000-8000-00805F9B34FB  (通用自定义服务)
 *   Write Characteristic: 0000FFE1-0000-1000-8000-00805F9B34FB  (可写特征)
 *   Notify Characteristic:0000FFE2-0000-1000-8000-00805F9B34FB  (通知特征，可选)
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_TIMEOUT_MS = 10_000L

        // ===== ESP32-C3 蓝牙开关协议（对接文档 v1.0） =====
        // 设备广播名，按名称扫描过滤 / 重连匹配。注意：地址是 Random Static，不要持久化 MAC。
        const val DEVICE_NAME = "C3_BLE_01"

        // 服务 0xF000（Unknown Service）
        val DEFAULT_SERVICE_UUID: UUID = UUID.fromString("0000F000-0000-1000-8000-00805F9B34FB")
        // 控制特征 0xF001（READ | WRITE | NOTIFY 三合一）
        val DEFAULT_WRITE_CHAR_UUID: UUID = UUID.fromString("0000F001-0000-1000-8000-00805F9B34FB")
        val DEFAULT_NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000F001-0000-1000-8000-00805F9B34FB")
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // 开 / 关 指令（HEX '1'=31, '0'=30；必须用 Write Request 带响应）
        const val CMD_ON: Byte = 0x31   // '1' → GPIO3 3.3V（高电平触发模块吸合）
        const val CMD_OFF: Byte = 0x30  // '0' → GPIO3 0V（释放）
        const val STATE_TEXT_READY_READ = "hello"     // Read F001 的连通性自检值
        const val STATE_TEXT_CONNECTED = "connected"  // 使能 Notify 当刻回发的通知
    }

    data class BleDevice(
        val name: String?,
        val address: String,
        val rssi: Int,
        val raw: BluetoothDevice
    )

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCOVERING_SERVICES }

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults: StateFlow<List<BleDevice>> = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastSentData = MutableStateFlow<ByteArray?>(null)
    val lastSentData: StateFlow<ByteArray?> = _lastSentData.asStateFlow()

    // Read F001 返回的连通性字符串（期望 "hello"）
    private val _readText = MutableStateFlow("")
    val readText: StateFlow<String> = _readText.asStateFlow()

    // Notify 收到的文本（使能订阅当刻回发 "connected"）
    private val _notifyText = MutableStateFlow("")
    val notifyText: StateFlow<String> = _notifyText.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ===== Mock 模式：模拟外部蓝牙设备接收端 =====
    var mockMode: Boolean = false
        private set

    data class MockReceivedFrame(
        val rawHex: String,
        val decoded: String,
        val timeMs: Long
    )

    private val _mockReceivedLog = MutableStateFlow<List<MockReceivedFrame>>(emptyList())
    val mockReceivedLog: StateFlow<List<MockReceivedFrame>> = _mockReceivedLog.asStateFlow()

    fun setMockMode(enabled: Boolean) {
        mockMode = enabled
        if (enabled) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _mockReceivedLog.value = emptyList()
        }
    }

    /** 清空模拟接收日志 */
    fun clearMockReceivedLog() {
        _mockReceivedLog.value = emptyList()
    }

    /** 模拟连接外部蓝牙设备 */
    fun mockConnect() {
        if (!mockMode) return
        _connectionState.value = ConnectionState.CONNECTED
        writeCharacteristic = null  // mock 模式不需要真实特征值
        Log.d(TAG, "[MOCK] BLE device connected (simulated)")
    }

    /** 模拟断开 */
    fun mockDisconnect() {
        if (!mockMode) return
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.d(TAG, "[MOCK] BLE device disconnected (simulated)")
    }

    /**
     * 解析模拟接收到的协议帧（模拟外部设备的串口解析逻辑）
     */
    private fun parseMockFrame(data: ByteArray): String {
        // 单字节开关指令（文档 4.1）：0x31 开 / 0x30 关
        if (data.size == 1) {
            val hex = "%02X".format(data[0])
            return when (data[0]) {
                CMD_ON -> "开关指令：ON（$hex，GPIO3→3.3V 吸合）"
                CMD_OFF -> "开关指令：OFF（$hex，GPIO3→0V 释放）"
                else -> "$hex | 未知单字节指令"
            }
        }
        if (data.size < 2) return "数据过短"
        val hex = data.joinToString(" ") { "%02X".format(it) }
        return when (data[0]) {
            0xAA.toByte() -> {
                if (data.size < 4) return "$hex | 阈值帧不完整"
                val idByte = data[1]
                val level = data[2].toInt() and 0xFF
                val checksum = data[3]
                val expected = (data[0].toInt() xor idByte.toInt() xor level.toInt()).toByte()
                val ok = if (checksum == expected) "校验OK" else "校验错误(期望${"%02X".format(expected)})"
                "$hex | 阈值触发帧 ID=${"%02X".format(idByte)} 电量=${level}% $ok"
            }
            0xAB.toByte() -> {
                if (data.size < 6) return "$hex | 状态帧不完整"
                val level = data[1].toInt() and 0xFF
                val powerLow = data[2].toInt() and 0xFF
                val powerHigh = data[3].toInt() and 0xFF
                val soc = data[4].toInt() and 0xFF
                val powerShort = (powerHigh shl 8 or powerLow).toShort().toInt()
                val checksum = data[5]
                var expected: Byte = 0
                for (i in 0 until data.size - 1) expected = (expected.toInt() xor data[i].toInt()).toByte()
                val ok = if (checksum == expected) "校验OK" else "校验错误(期望${"%02X".format(expected)})"
                "$hex | 状态上报帧 电量=${level}% 功率=${powerShort}mW SOC=${soc}% $ok"
            }
            else -> "$hex | 未知帧类型"
        }
    }

    private var serviceUuid: UUID = DEFAULT_SERVICE_UUID
    private var writeCharUuid: UUID = DEFAULT_WRITE_CHAR_UUID
    private var notifyCharUuid: UUID = DEFAULT_NOTIFY_CHAR_UUID

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    // 服务发现是否已完成，未完成前不允许写入（避免与 discovery 期间的 GATT 操作冲突）
    private var discoveredReady = false
    // 单写串行队列：Android 12 旧 API 必须等前一次 onCharacteristicWrite 回调后才能发起下一次写，
    // 否则栈可能直接 REJECT（表现为 writeType 被覆盖、返回 false）
    private var isWriting = false
    private val pendingWrites = ArrayDeque<ByteArray>()
    private val handler = Handler(Looper.getMainLooper())
    private val scanResultMap = linkedMapOf<String, BleDevice>()

    // ===== 自动重连 =====
    // 锁屏 Doze 后系统可能主动断开 GATT，需要自动恢复
    private var shouldAutoReconnect = true          // 是否自动重连（默认开）
    private var lastConnectedAddress: String? = null // 上次连接过的设备地址
    private val reconnectDelayMs = 3000L             // 断开后等几秒重连
    private var reconnectRunnable: Runnable? = null

    fun setAutoReconnect(enabled: Boolean) {
        shouldAutoReconnect = enabled
        if (!enabled) reconnectRunnable?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "autoReconnect set to $enabled")
    }

    fun getAutoReconnect(): Boolean = shouldAutoReconnect

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val dev = result.device
            val key = dev.address
            val displayName = resolveDeviceName(result)
            Log.d(TAG, "Scan: addr=${dev.address}, resolvedName=\"$displayName\", " +
                    "scanRecordName=${result.scanRecord?.deviceName}, " +
                    "deviceName=${runCatching { dev.name }.getOrNull()}, rssi=${result.rssi}")
            val bleDev = BleDevice(
                name = displayName,
                address = dev.address,
                rssi = result.rssi,
                raw = dev
            )
            scanResultMap[key] = bleDev
            _scanResults.value = scanResultMap.values.toList()
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed, errorCode=$errorCode")
            _isScanning.value = false
        }
    }

    /**
     * 解析设备名称，优先级与系统蓝牙设置保持一致：
     * 1) device.getName() —— 已配对/连接过的设备返回缓存名（Android 12+ 需 BLUETOOTH_CONNECT）
     * 2) ScanRecord.deviceName —— 当前广播包里的 Complete/Shortened Local Name
     * 3) 原始广告字节解析 Local Name（hidden API，容错）
     * 4) 已配对设备列表匹配
     * 5) 兜底：BLE设备_ + MAC 后 4 位
     */
    private fun resolveDeviceName(result: ScanResult): String {
        val dev = result.device

        // 1) device.getName()（系统设置用此值，对已配对设备有缓存）
        runCatching { dev.name?.trim()?.takeIf { it.isNotEmpty() } }
            .getOrNull()?.let { return it }

        // 2) ScanRecord.deviceName（public API，解析广播包 Local Name）
        val record = result.scanRecord
        record?.deviceName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        // 3) 尝试解析原始广告字节找 Local Name（ScanRecord.getBytes 是 hidden API，需容错）
        runCatching {
            val bytes = record?.bytes
            if (bytes != null && bytes.isNotEmpty()) {
                var i = 0
                var shortName: String? = null
                while (i < bytes.size) {
                    val length = bytes[i].toInt() and 0xFF
                    if (length <= 1) break
                    if (i + 1 + length > bytes.size) break
                    val type = bytes[i + 1].toInt() and 0xFF
                    val payload = bytes.copyOfRange(i + 2, i + 1 + length)
                    when (type) {
                        0x09 -> {  // Complete Local Name
                            val name = String(payload, Charsets.UTF_8).trim()
                            if (name.isNotEmpty()) return name
                        }
                        0x08 -> {  // Shortened Local Name
                            shortName = String(payload, Charsets.UTF_8).trim()
                        }
                    }
                    i += 1 + length
                }
                shortName?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }

        // 4) 从已配对设备列表查找
        runCatching {
            bluetoothAdapter?.bondedDevices?.find { it.address == dev.address }?.name
                ?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()?.let { return it }

        // 5) 兜底：BLE_ + MAC 后 4 位
        val suffix = dev.address.replace(":", "").takeLast(4)
        return "BLE设备_$suffix"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    discoveredReady = false
                    isWriting = false
                    pendingWrites.clear()
                    Log.d(TAG, "BLE connected, discovering services...")
                    gatt?.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    writeCharacteristic = null
                    discoveredReady = false
                    isWriting = false
                    pendingWrites.clear()
                    Log.d(TAG, "BLE disconnected, shouldAutoReconnect=$shouldAutoReconnect")
                    // 自动重连：3 秒后重新 connect（autoConnect=true 会让 GATT 自动恢复）
                    if (shouldAutoReconnect && lastConnectedAddress != null) {
                        reconnectRunnable?.let { handler.removeCallbacks(it) }
                        reconnectRunnable = Runnable {
                            val addr = lastConnectedAddress ?: return@Runnable
                            Log.d(TAG, "Auto-reconnecting to $addr (Doze wakeup)")
                            runCatching {
                                val dev = bluetoothAdapter?.getRemoteDevice(addr) ?: return@Runnable
                                disconnect(keepAutoConnect = true)
                                connect(dev)
                            }
                        }
                        handler.postDelayed(reconnectRunnable!!, reconnectDelayMs)
                    }
                }
                BluetoothGatt.STATE_CONNECTING -> {
                    _connectionState.value = ConnectionState.CONNECTING
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            _connectionState.value = ConnectionState.CONNECTED
            val svc = gatt?.getService(serviceUuid)
            // 0xF001：READ | WRITE | NOTIFY 三合一
            val chr = svc?.getCharacteristic(writeCharUuid)
            writeCharacteristic = chr
            if (chr == null) {
                Log.w(TAG, "Characteristic 0xF001 not found under service $serviceUuid")
                return
            }
            discoveredReady = false  // 等 CCCD 写完再开放
            Log.d(TAG, "Services discovered OK, char 0xF001 ready (props=${chr.properties})")
            // 串行化初始化：先请求 MTU → 等 onMtuChanged → 再写 CCCD
            runCatching { g?.requestMtu(247) }
            Log.d(TAG, "requestMtu(247) issued, waiting onMtuChanged...")
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu")
            } else {
                Log.w(TAG, "MTU change failed (status=$status), continue anyway")
            }
            // MTU 协商完，开始写 CCCD 开 Notify（下一个串行步骤）
            writeCccd()
        }

        private fun writeCccd() {
            val g = gatt ?: return
            val chr = writeCharacteristic ?: return
            runCatching {
                g.setCharacteristicNotification(chr, true)
                chr.getDescriptor(CCC_DESCRIPTOR_UUID)?.let { desc ->
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val ok = g.writeDescriptor(desc)
                    if (!ok) {
                        Log.e(TAG, "writeDescriptor(CCCD) REJECTED")
                    } else {
                        Log.d(TAG, "writeDescriptor(CCCD) issued, waiting onDescriptorWrite...")
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int
        ) {
            if (descriptor?.uuid == CCC_DESCRIPTOR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "CCCD written OK, Notify enabled. GATT init fully ready.")
                } else {
                    Log.w(TAG, "CCCD write failed (status=$status), open for writes anyway")
                }
                discoveredReady = true  // 无论成功与否，都开放写入
                // 初始化期间可能有排队的写入，现在开始处理
                drainWriteQueue()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int
        ) {
            isWriting = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic write success")
            } else {
                Log.e(TAG, "Characteristic write failed: $status")
            }
            drainWriteQueue()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic?.uuid == writeCharUuid) {
                val text = characteristic.value?.toString(Charsets.UTF_8) ?: ""
                _readText.value = text
                Log.d(TAG, "Read F001: \"$text\"")
            } else {
                Log.e(TAG, "Read F001 failed: status=$status")
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?
        ) {
            val value = characteristic?.value ?: return
            val text = String(value, Charsets.UTF_8)
            _notifyText.value = text
            val hex = value.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "Notify F001: \"$text\" ($hex)")
        }
    }

    /**
     * 自定义 GATT UUID（如外部硬件使用自定义 UUID 请先调用此方法）
     */
    fun setGattProfile(
        serviceUuidStr: String,
        writeCharUuidStr: String,
        notifyCharUuidStr: String? = null
    ) {
        this.serviceUuid = UUID.fromString(serviceUuidStr)
        this.writeCharUuid = UUID.fromString(writeCharUuidStr)
        notifyCharUuidStr?.let { this.notifyCharUuid = UUID.fromString(it) }
    }

    fun isBluetoothAvailable(): Boolean {
        val hasAdapter = bluetoothAdapter != null
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        return hasAdapter && hasFeature
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasPermission(): Boolean {
        val ctx = context
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermission()) {
            Log.w(TAG, "No BLE permission, cannot start scan")
            return
        }
        val adapter = bluetoothAdapter ?: return
        scanResultMap.clear()
        _scanResults.value = emptyList()
        _readText.value = ""
        _notifyText.value = ""
        _isScanning.value = true

        // 按设备广播名 C3_BLE_01 过滤（文档 1.1 推荐，地址是随机静态地址不可依赖）
        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            val filter = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
                .onFailure { Log.e(TAG, "startScan(LE) failed: $it") }
        }
        handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
        Log.d(TAG, "Scan started, filter name=$DEVICE_NAME")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasPermission()) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d(TAG, "Scan stopped")
    }

    fun connect(device: BluetoothDevice) {
        if (!hasPermission()) return
        disconnect(keepAutoConnect = true)  // 断开但保留自动重连标志
        lastConnectedAddress = device.address
        // 持久化 MAC，下次 App 启动自动连这个设备
        runCatching {
            val prefs = context.getSharedPreferences("battery_ble_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_ble_address", device.address).apply()
        }
        _connectionState.value = ConnectionState.CONNECTING
        // autoConnect=true：系统在后台会自动重连这个设备（Doze/锁屏断开后恢复）
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, true, gattCallback)
        }
        Log.d(TAG, "connect() addr=${device.address}, autoConnect=true")
    }

    fun connectByAddress(address: String) {
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return
        val device = adapter.getRemoteDevice(address)
        connect(device)
    }

    /**
     * 断开 GATT 连接。
     * @param keepAutoConnect true=只是主动 disconnect() 但保留 autoConnect 状态（系统 autoConnect 仍会重连）
     *                        false=彻底断开，取消 autoConnect 和所有 pending reconnect
     */
    fun disconnect(keepAutoConnect: Boolean = false) {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        if (!keepAutoConnect) {
            shouldAutoReconnect = false
        }
        if (_isScanning.value) stopScan()
        try {
            gatt?.disconnect()
            if (!keepAutoConnect) gatt?.close()
        } catch (_: Exception) {}
        if (!keepAutoConnect) {
            gatt = null
            writeCharacteristic = null
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        if (!keepAutoConnect) {
            Log.d(TAG, "disconnect() fully closed")
        } else {
            Log.d(TAG, "disconnect() called but autoConnect kept alive")
        }
    }

    /**
     * 彻底关闭 GATT（包括 autoConnect）。Activity 退出时调用。
     */
    fun closeAll() {
        shouldAutoReconnect = false
        lastConnectedAddress = null
        disconnect()
    }

    /**
     * 手动触发重连（由 BatteryMonitor 的 AlarmManager tick 调用，Doze 下可靠）
     * 每 ~10 秒被调一次。即使状态显示 CONNECTED 也做一次轻量级健康检查——
     * 因为 Doze 下 BLE GATT 可能静默断开但 DISCONNECTED 回调被延迟。
     */
    fun tryReconnectIfNeeded() {
        val addr = lastConnectedAddress
        val adapter = bluetoothAdapter
        if (addr == null || adapter == null) return

        val isConnected = _connectionState.value == ConnectionState.CONNECTED && writeCharacteristic != null
        if (isConnected) return  // 已连，什么都不做

        // 没连，尝试恢复
        Log.d(TAG, "tryReconnectIfNeeded: RECONNECTING to $addr (state=${_connectionState.value})")
        runCatching {
            if (_connectionState.value != ConnectionState.CONNECTING) {
                val dev = adapter.getRemoteDevice(addr)
                disconnect(keepAutoConnect = true)
                connect(dev)
            }
        }
    }

    fun isConnected(): Boolean {
        if (mockMode) return _connectionState.value == ConnectionState.CONNECTED
        return _connectionState.value == ConnectionState.CONNECTED && writeCharacteristic != null
    }

    /**
     * 发送字节数组到外部蓝牙设备（写入特征值）
     * Mock 模式下：不通过真实蓝牙发送，而是解析协议帧并记录到 mockReceivedLog
     */
    fun sendData(data: ByteArray): Boolean {
        if (mockMode) {
            val hex = data.joinToString(" ") { "%02X".format(it) }
            val decoded = parseMockFrame(data)
            val frame = MockReceivedFrame(hex, decoded, System.currentTimeMillis())
            val current = _mockReceivedLog.value.toMutableList()
            current.add(0, frame)
            _mockReceivedLog.value = current.take(50)
            _lastSentData.value = data
            Log.d(TAG, "[MOCK] Received ${data.size} bytes: $hex → $decoded")
            return true
        }
        if (!hasPermission()) { Log.e(TAG, "sendData: no permission"); return false }
        if (gatt == null) { Log.e(TAG, "sendData: gatt null"); return false }
        if (!discoveredReady) { Log.e(TAG, "sendData: services not discovered yet"); return false }
        // 串行写：若上一次写入尚未收到 onCharacteristicWrite 回调，则排队等待，避免 Android 12 并发写被拒
        if (isWriting) {
            pendingWrites.addLast(data)
            Log.d(TAG, "sendData: queued (busy), pending=${pendingWrites.size}")
            return true
        }
        return writeNow(data)
    }

    /**
     * 真正下发一次写（带响应）。每次写入都从 service 重新获取特征对象，
     * 避免复用旧对象时栈把 writeType 覆盖为 NO_RESPONSE(2)，导致设备（只支持 Write Request）REJECT。
     */
    private fun writeNow(data: ByteArray): Boolean {
        val g = gatt ?: run { Log.e(TAG, "writeNow: gatt null"); return false }
        val wc = g.getService(serviceUuid)?.getCharacteristic(writeCharUuid)
            ?: run { Log.e(TAG, "writeNow: wc null (service/char not found)"); return false }
        isWriting = true
        return try {
            // 强制带响应写（WRITE_TYPE_DEFAULT=0）。设备 props 不含 WRITE_NO_RESPONSE，只能用 Write Request。
            @Suppress("DEPRECATION")
            wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            Log.d(TAG, "writeNow: writeType=${wc.writeType}, props=${wc.properties}")
            @Suppress("DEPRECATION")
            wc.value = data
            @Suppress("DEPRECATION")
            val ok = g.writeCharacteristic(wc)
            if (ok) {
                _lastSentData.value = data
                Log.d(TAG, "Sent ${data.size} bytes: ${data.joinToString { "%02X".format(it) }}")
            } else {
                // 立即失败：栈可能不会再回调，需释放写锁并继续排队的后续写
                isWriting = false
                Log.e(TAG, "writeCharacteristic REJECTED: uuid=${wc.uuid}, writeType=${wc.writeType}, props=${wc.properties}")
                drainWriteQueue()
            }
            ok
        } catch (e: Exception) {
            isWriting = false
            Log.e(TAG, "writeCharacteristic EXCEPTION: ${e.message}")
            drainWriteQueue()
            false
        }
    }

    /**
     * 依靠 onCharacteristicWrite 回调信号串行驱动写队列。
     */
    private fun drainWriteQueue() {
        while (!isWriting) {
            val next = pendingWrites.removeFirstOrNull() ?: break
            if (!writeNow(next)) break
        }
    }

    /**
     * 发送开关指令（对接文档 4.1）：
     *   开 → 写 0x31 ('1')，GPIO3 输出 3.3V（高电平触发模块吸合）
     *   关 → 写 0x30 ('0')，GPIO3 输出 0V（释放）
     * 必须使用 Write Request（带响应），故统一走 sendData 的 WRITE_TYPE_DEFAULT。
     * Mock 模式下会解析进模拟接收日志。
     */
    fun sendSwitch(on: Boolean): Boolean {
        val cmd = if (on) CMD_ON else CMD_OFF
        return sendData(byteArrayOf(cmd))
    }
}
