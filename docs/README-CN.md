# DroidNode

[English README](../README.md)

<p align="center">
  <img src="./assets/droidnode-logo.png" alt="DroidNode Logo" width="280" />
</p>

**DroidNode** 是运行在 Android 侧的轻量级运行时。它基于无线调试能力（ADB over Wi-Fi）将手机底层控制能力 API 化，使设备可通过 HTTP 接口在局域网内被远程调用。

DroidNode 专注于**基础设施层**，不绑定任何特定 AI 模型或工作流，可为 LLM/VLM Agent、自动化测试与远程设备管理提供可扩展的低阶原语接口。

⚠️ 免责声明与安全警告

**本项目目前处于 AI PoC 阶段，尚未进行工程化与安全加固。**

* **无鉴权**：当前版本未开启 Token 校验，任何局域网内的设备均可控制该手机。
* **公网风险**：严禁将 API 端口直接映射到公网。
* **法律责任**：使用者必须确保行为符合当地法律及平台规则。作者不对因滥用导致的任何损失负责。


## 🚀 核心特性

* **全内置 ADB 通信**：参考 Shizuku ，利用 `mDNS` 自动发现端口，支持在手机端直接完成无线配对与连接，无需外部 PC 介入。
* **嵌入式 API Server**：内置基于 Ktor 的嵌入式服务器，默认监听 `17171` 端口。
* **低阶原语操控**：提供点击、滑动、文本输入、UI 树抓取（XML）及截图等标准化 API。
* **原生输入增强**：内置 `ActlImeService` 输入法，支持 UTF-8 字符注入，解决远程输入乱码与焦点问题。
* **模块化架构**：API 采用插件化注册机制，开发者可扩展自定义的功能模块。

---

## 🛠 快速上手

### 1. 环境准备

* Android 11+（需支持无线调试）。
* 手机与控制端处于同一 Wi-Fi 环境。

### 2. 构建与安装

```bash
# 克隆仓库
git clone https://github.com/your-username/droidnode.git
cd droidnode

# 使用本地 Gradle 构建
./gradlew :app:assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk

```

### 3. 激活节点

1. 在手机上启动 **DroidNode App**。
2. 进入“开发者选项” -> “无线调试” -> “使用配对码配对设备”。
3. 在 DroidNode 通知栏输入配对码，完成本地 ADB 授权。
4. 点击 **Start API Server**。

---

## 📖 API 文档概览

服务默认地址：`http://<device-ip>:17171`

| 路径 | 方法 | 描述 |
| --- | --- | --- |
| `/v1/health` | `GET` | 检查节点存活状态 |
| `/v1/system/info` | `GET` | 获取设备硬件与系统版本信息 |
| `/v1/control/click` | `POST` | 模拟点击，参数：`{"x": int, "y": int}` |
| `/v1/control/swipe` | `POST` | 模拟滑动，参数：`{"startX": int, "startY": int, "endX": int, "endY": int, "durationMs": int}` |
| `/v1/control/input` | `POST` | 文本输入，参数：`{"text": "...", "pressEnter": bool, "enterAction": "auto/search/send/done/go/next/enter/none"}` |
| `/v1/ui/xml` | `POST` | 获取当前页面的 UI 层次结构 (XML) |
| `/v1/ui/screenshot` | `POST` | 获取屏幕截图 (PNG 二进制流) |

> **提示**：详细的 API 调用示例请参考 [tools/api_tester.sh](../tools/api_tester.sh)。

---

## 📊 API 性能快照

最新基准测试时间（2026-02-13）：

* 目标地址：`http://192.168.0.105:17175`
* 测试配置：warmup=2，samples=20，timeout=30s
* 结果：140/140 请求成功（100%）

| API | 方法 | 平均延迟 (ms) | P95 (ms) |
| --- | --- | ---: | ---: |
| `/v1/health` | `GET` | 48.35 | 65.26 |
| `/v1/system/info` | `GET` | 113.95 | 125.19 |
| `/v1/control/click` | `POST` | 101.06 | 185.11 |
| `/v1/control/swipe` | `POST` | 211.42 | 224.06 |
| `/v1/control/input` | `POST` | 807.88 | 908.69 |
| `/v1/ui/xml` | `POST` | 2234.79 | 2272.72 |
| `/v1/ui/screenshot` | `POST` | 414.51 | 450.69 |

完整报告见：[`docs/API_PERFORMANCE_REPORT.md`](./API_PERFORMANCE_REPORT.md)

---

## 📂 项目结构

```text
.
├── app/src/main/java/com/actl/mvp/
│   ├── api/            # Ktor 框架与 API 接口实现 (v1/)
│   ├── startup/        # mDNS 发现与无线 ADB 握手逻辑
│   │   └── directadb/  # 纯 Kotlin 实现的 ADB 协议封装
│   └── ime/            # 自定义输入法服务
├── tools/              # 客户端测试脚本 (Python/Shell)
└── LICENSE             # 开源许可证

```

---

## 🤝 路线

* [ ] 标准化设计
* [ ] 代码规范与基础设施搭建
* [ ] App 自适应的语义化点击 API
* [ ] 截屏等 API 的性能优化
* [ ] 增加基于 Token 的请求鉴权
* [ ] 操作拟人化
* [ ] 实现基于 ZeroTier 的原生虚拟局域网络

欢迎提交 Issue 或 Pull Request ！
