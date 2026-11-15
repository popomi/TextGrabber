# TextGrabber

[English](#english) | [中文](#中文)

---

## English

### Overview
TextGrabber is an Android application that automatically captures and stores all visible text on your screen using Android's Accessibility Service API. It features intelligent deduplication, local SQLite storage, and a real-time statistics dashboard.

### Features
- 🔍 **Automatic Text Capture**: Monitors screen content changes and extracts text from all visible UI elements
- 🧠 **Smart Deduplication**: Dual-layer deduplication using LRU cache (400 entries) + database UNIQUE constraints
- 💾 **Local Storage**: SQLite database for persistent text storage
- 📊 **Real-time Dashboard**: Live counter and recent 30 items display
- 🌍 **Internationalization**: Supports English and Chinese (auto-detected)
- 🚫 **Self-Exclusion**: Automatically excludes capturing text from TextGrabber itself
- 🎯 **ViewID Tracking**: Captures Android View resource IDs for better context

### Requirements
- Android 7.0 (API 24) or higher
- Accessibility Service permission (requires manual activation in system settings)

### Installation
1. Download the latest APK from [Releases](../../releases)
2. Install the APK on your device
3. Enable the Accessibility Service:
   - Settings → Accessibility → TextGrabber → Toggle ON
4. Open TextGrabber and toggle the switch to start capturing

### Usage
1. **Enable Service**: Turn on the toggle switch in the main interface
2. **Navigate to other apps**: The service will automatically capture text as you scroll/interact
3. **View Statistics**: Real-time counter shows total saved items
4. **Browse Recent Captures**: Scrollable list displays the most recent 30 text entries

### Database Inspection
You can inspect the captured data using Android Studio's Database Inspector:
1. Connect your device/emulator
2. Open **App Inspection** → **Database Inspector**
3. Select `com.screen.textgrabber` process
4. View `text_grabber.db` → `captured_text` table

### Building from Source
```bash
# Clone the repository
git clone https://github.com/yourusername/TextGrabber.git
cd TextGrabber

# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Architecture
```
MyAccessibilityService (Core)
    ├─ Event Listening (typeAllMask)
    ├─ Node Traversal (Recursive)
    ├─ DeduplicationLogic (LRU Cache: 400)
    └─ DBManager (SQLite)

MainActivity (UI)
    ├─ Service Toggle
    ├─ Counter Display (Auto-refresh: 1s)
    └─ Recent Texts List (Auto-refresh: 5s)
```

### Privacy & Security
- ⚠️ **Sensitive Permission**: Accessibility Service can read ALL screen content
- 🔒 **Local Only**: All data is stored locally on your device
- 🚫 **No Network**: This app does not transmit any data over the network
- 📝 **Transparency**: All source code is open and auditable

### License
This project is licensed under the MIT License.

---

## 中文

### 项目简介
TextGrabber 是一个 Android 应用程序，使用 Android 无障碍服务 API 自动捕获并存储屏幕上的所有可见文本。具备智能去重、本地 SQLite 存储和实时统计面板等功能。

### 主要功能
- 🔍 **自动文本抓取**：监控屏幕内容变化，提取所有可见 UI 元素的文本
- 🧠 **智能去重**：双层去重机制（LRU 缓存 400 条 + 数据库唯一约束）
- 💾 **本地存储**：使用 SQLite 数据库持久化存储文本
- 📦 **全量抓包存档展示（v1.2.0 新增）**：解除 50 条与 50 字截断限制，支持「按时间抓包」「数据库无障碍包」「纯文本全文」三种视图模式切换、按无障碍包名筛选、时间正倒序切换与实时搜索
- 🎯 **悬浮窗一键提取（v1.3.0 新增）**：无需额外悬浮窗权限，依托无障碍服务直接显示可拖动悬浮按钮；点击即刻提取当前屏幕全部可见无障碍文字，最开头自动加上 `详细总结`（支持主界面自定义前缀）后一键复制到剪贴板
- 📋 **一键复制（v1.2.0 新增）**：支持单条/单包一键复制、一键复制全部存档、仅复制纯文本，内置剪贴板超限自动转存保护
- 📤 **一键导出（v1.2.0 新增）**：一键导出当前抓包全部文本到 `Download/TextGrabber/`，并支持另存纯文字 `.txt`、UTF-8 BOM `.csv` 明细表、原始 `.db` SQLite 数据库及系统分享
- 🧹 **一键清空（v1.2.0 新增）**：同步清空界面、SQLite 数据库表（含 `VACUUM` 压缩回收空间）及无障碍服务内存去重缓存，无需重启服务即可重新抓取
- 🌍 **国际化支持**：支持中英文（自动检测系统语言）
- 🚫 **自我排除**：自动排除抓取 TextGrabber 自身的文本
- 🎯 **ViewID 追踪**：捕获 Android 视图资源 ID，提供更好的上下文信息

### 系统要求
- Android 7.0 (API 24) 及以上
- 无障碍服务权限（需在系统设置中手动激活）

### 安装步骤
1. 从 [Releases](../../releases) 下载最新 APK
2. 在设备上安装 APK
3. 启用无障碍服务：
   - 设置 → 无障碍 → TextGrabber → 开启
4. 打开 TextGrabber，点击开关开始抓取

### 使用说明
1. **启用服务**：在主界面打开开关
2. **浏览其他应用**：服务会自动在你滚动/交互时抓取文本
3. **查看统计**：实时计数器显示已保存的总条目数
4. **浏览最近记录**：可滚动列表显示最近 30 条文本条目

### 数据库查看
可以使用 Android Studio 的 Database Inspector 查看捕获的数据：
1. 连接设备/模拟器
2. 打开 **App Inspection** → **Database Inspector**
3. 选择 `com.screen.textgrabber` 进程
4. 查看 `text_grabber.db` → `captured_text` 表

### 从源码构建
```bash
# 克隆仓库
git clone https://github.com/yourusername/TextGrabber.git
cd TextGrabber

# 构建 debug APK
./gradlew assembleDebug

# 输出位置: app/build/outputs/apk/debug/app-debug.apk
```

### 架构设计
```
MyAccessibilityService (核心)
    ├─ 事件监听 (typeAllMask)
    ├─ 节点遍历 (递归)
    ├─ DeduplicationLogic (LRU 缓存: 400)
    └─ DBManager (SQLite)

MainActivity (UI)
    ├─ 服务开关
    ├─ 计数器显示 (自动刷新: 1秒)
    └─ 最近文本列表 (自动刷新: 5秒)
```

### 隐私与安全
- ⚠️ **敏感权限**：无障碍服务可以读取屏幕上的所有内容
- 🔒 **仅本地存储**：所有数据仅存储在您的设备本地
- 🚫 **无网络传输**：本应用不会通过网络传输任何数据
- 📝 **透明开源**：所有源代码公开可审计

### 许可证
本项目使用 MIT 许可证。
