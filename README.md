# BELAPP — Android BLE 继电器充电自动控制

配合 ESP32-C3 继电器模块，实现手机电量阈值自动开关充电。

## 硬件

| 项目 | 详情 |
|------|------|
| 模块 | ESP32-C3 开发板 + 继电器 |
| BLE Service | `0000F000-0000-1000-8000-00805F9B34FB` |
| BLE Char | `0000F001-0000-1000-8000-00805F9B34FB` |
| 开充电（GPIO 高电平） | 写 `0x31` |
| 关充电（GPIO 低电平） | 写 `0x30` |
| 写类型 | `WRITE_TYPE_DEFAULT`（request + response） |

## 功能

- **双阈值自动控制**：电量充到「停充阈值」自动发关指令，掉到「开充阈值」自动发开指令
- **前台服务后台运行**：锁屏、App 切后台、甚至被系统回收后（只要没 force-stop）持续监控
- **阈值持久化**：SharedPrefs 存储，Activity 重建自动回填
- **BLE MAC 持久化 + 自动重连**：启动 2 秒后自动连接上次成功的设备
- **边沿触发 + 滞后**：每个阈值独立追踪状态，只有电量穿越阈值 + 5% 滞后后才允许再次触发

```
        ↑ 充到 80% 发 0x30（停充电）
        │  ←──────── 5% 滞后区 ────────→
100 ────┼──────────────────────────────┼──── 0
        │                              │
        └────── 掉到 20% 发 0x31（开充电） ──┘
```

## 权限

| 权限 | 用途 |
|------|------|
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | Android 12+ BLE 扫描与连接 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |
| `FOREGROUND_SERVICE` | 前台服务保持运行 |
| `READ_PHONE_STATE` | 读取手机电量（非必需，兼容旧版本） |

## 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug
```

APK 路径：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```
app/src/main/java/com/belapp/batteryble/
├── MainActivity.kt              # UI 壳 + 事件绑定（核心组件从 Service 获取）
├── ble/
│   └── BleManager.kt            # BLE GATT 连接/发现/读写/自动重连
├── controller/
│   └── ThresholdController.kt   # 双阈值边沿触发 + 状态追踪
├── monitor/
│   └── BatteryMonitor.kt       # 电量/电流/电压 tick + SharedFlow
├── service/
│   └── BatteryBLEService.kt     # 前台服务，持有所有核心组件生命周期
└── ui/
    └── BleDeviceAdapter.kt      # 扫描列表适配器
```

## 工作流

1. App 启动 → `BatteryBLEService` 前台服务启动
2. 读取 SharedPrefs 恢复阈值 + 上次 BLE MAC
3. `BatteryMonitor` 每 5 秒 tick → 主动读电量（sticky broadcast）→ emit `statusFlow`
4. `ThresholdController.onBatteryStatus()` 收到电量 → 检查阈值 → 满足条件调用 `BleManager.sendSwitch()`
5. `BleManager` 写入 `0x31` 或 `0x30` 到 F001 characteristic → ESP32-C3 继电器动作

## 常见问题

**问：App 退出后还会触发吗？**
答：会。前台服务会持续运行。但 Android Doze 深度休眠时 AlarmManager 会被批处理（约 9 分钟），恢复后立即补 tick。

**问：为什么我电量到了阈值没反应？**
答：先看蓝牙是否已连接（UI 显示"已连接"）。ThresholdController 里有守卫 `if (!bleManager.isConnected()) skip`。

**问：停充阈值和开充阈值怎么设？**
答：停充阈值（高值，如 80%）= 充到这个值自动停充电；开充阈值（低值，如 20%）= 掉到这个值自动开充电。两者必须严格递增。
