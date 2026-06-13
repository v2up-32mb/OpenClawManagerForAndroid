# PicoClawManagerForAndroid 🦞

一个使用 **pico 协议**（WebSocket JSON）连接与管理多个 **picoclaw** 实例的 Android 客户端（Jetpack Compose）。

> 本项目是 [OpenClawManagerForAndroid](https://github.com/btlaosiji/OpenClawManagerForAndroid) 的改造版本，通信协议从 OpenClaw Gateway 协议改为 pico 协议，适用于 [picoclaw](https://github.com/sipeed/picoclaw) 实例。

## 功能

- **多实例连接**：可保存多个 picoclaw 实例（实例1/实例2…），在首页顶部一键切换；每个实例各自保持独立连接状态与聊天会话
- **对话**：通过 pico 协议（WebSocket）与 picoclaw 的 AI 助手交互，支持流式回复、思考过程（thought）、工具调用（tool_calls）
- **状态监控**：通过 REST API 监控 picoclaw 网关状态（运行状态、版本、PID 等）
- **模型配置**：通过 REST API 查看/切换默认模型（`/api/models/*`）
- **Skill 管理**：搜索、安装、卸载 picoclaw Skill（`/api/skills/*`）
- **合规页面**：内置《隐私政策》《用户协议》HTML

## 架构

```
┌────────────────────────────────────────────────────┐
│                  UI Layer (Compose)                │
│  ┌─────────┐ ┌──────────┐ ┌─────────┐ ┌────────┐  │
│  │ Chat    │ │ Status   │ │ Model   │ │ Skill  │  │
│  │ Screen  │ │ Monitor  │ │ Config  │ │ Manage │  │
│  └────▲────┘ └────▲─────┘ └────▲────┘ └───▲────┘  │
│       └───────────┴─────┬──────┴───────────┘       │
│                    MainViewModel                     │
└───────────────────────────┼─────────────────────────┘
                            │
┌───────────────────────────┼─────────────────────────┐
│                Data Layer                            │
│                            │                         │
│  ┌────────────────────────┴──────────────────┐      │
│  │           PicoRepository                   │      │
│  │  (混合架构: WebSocket + REST API)          │      │
│  └────────┬──────────────────────────┬────────┘      │
│           │                          │               │
│  ┌────────▼──────────┐    ┌─────────▼───────┐        │
│  │ PicoGatewayClient │    │  PicoApiClient   │        │
│  │ (pico 协议 WS)    │    │ (REST API)      │        │
│  │ message.send      │    │ /api/models/*   │        │
│  │ message.create    │    │ /api/skills/*   │        │
│  │ message.update    │    │ /api/gateway/*  │        │
│  └───────────────────┘    └─────────────────┘        │
└──────────────────────────────────────────────────────┘
```

## 协议对比

| 特性 | 原版 (OpenClaw Gateway) | 本版 (pico 协议) |
|:-----|:------------------------|:-----------------|
| 认证方式 | Ed25519 挑战-响应 | Bearer Token |
| 消息模型 | RPC (req/res/event) | 简单 JSON 帧 |
| 心跳 | req: health | WebSocket 原生 Ping |
| 流式回复 | chat 事件 | message.create + update |
| 管理功能 | 全部走 WebSocket | REST API |

## 环境要求

- Android Studio（建议最新稳定版）
- Android SDK：`compileSdk 34` / `minSdk 24`
- picoclaw：需启用 Pico Channel

## 快速开始

1. 用 Android Studio 打开项目根目录
2. 连接设备或启动模拟器
3. 运行 `app` 模块
4. 在主页填写 picoclaw 的 WebSocket 地址：`ws://host:9090`
5. 点击连接即可开始对话

## 技术栈

- **语言**：Kotlin 1.9
- **UI**：Jetpack Compose + Material3 (BOM 2024.04)
- **架构**：MVVM (ViewModel + StateFlow)
- **WebSocket**：OkHttp 4.12
- **HTTP**：OkHttp
- **JSON**：Gson 2.10
- **协程**：kotlinx-coroutines 1.7.3

## 许可

Apache-2.0
