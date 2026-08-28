# Pico 协议合规性验证报告

**审计日期**: 2026-06-13  
**审计版本**: picoclaw 0.2.6 (git: 51eecde)  
**审计目标**: PicoClawManagerForAndroid  
**审计范围**: PicoModels.kt, PicoGatewayClient.kt, PicoRepository.kt, PicoApiClient.kt

---

## 总结

| 项目 | 状态 |
|:-----|:----:|
| 协议帧结构 | ✅ 完全匹配 |
| 消息类型定义 | ✅ 完全匹配 |
| WebSocket 认证 | ✅ 正确实现 |
| 心跳机制 | ✅ 正确实现 |
| 消息发送 | ✅ 正确实现 |
| 消息接收/处理 | ⚠️ 少量偏差 |
| REST API 路径 | ⚠️ 1处偏差 |
| 数据结构 | ⚠️ 1处偏差 |

---

## 已正确实现的协议特性

### 1. 协议帧结构 (`PicoMessage`)

picoclaw 实际协议定义 (`protocol.go`):
```go
type PicoMessage struct {
    Type      string         `json:"type"`
    ID        string         `json:"id,omitempty"`
    SessionID string         `json:"session_id,omitempty"`
    Timestamp int64          `json:"timestamp,omitempty"`
    Payload   map[string]any `json:"payload,omitempty"`
}
```

Android 实现 (`PicoMessage` data class):
```kotlin
data class PicoMessage(
    val type: String,
    val id: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    val timestamp: Long? = null,
    val payload: Map<String, Any?>? = null
)
```

**结论: ✅ 完全匹配** — 字段名、类型、JSON 序列化名称均正确。

### 2. 消息类型常量 (`PicoMessageType`)

| 常量 | 值 | 方向 | 匹配 |
|:-----|:---|:----|:----:|
| `MESSAGE_SEND` | `"message.send"` | 客户端→服务端 | ✅ |
| `MEDIA_SEND` | `"media.send"` | 客户端→服务端 | ✅ (未使用，但定义正确) |
| `PING` | `"ping"` | 客户端→服务端 | ✅ (未作为 JSON 发送，但定义正确) |
| `TYPING_START` | `"typing.start"` | 双向 | ✅ |
| `TYPING_STOP` | `"typing.stop"` | 双向 | ✅ |
| `MESSAGE_CREATE` | `"message.create"` | 服务端→客户端 | ✅ |
| `MESSAGE_UPDATE` | `"message.update"` | 服务端→客户端 | ✅ |
| `MESSAGE_DELETE` | `"message.delete"` | 服务端→客户端 | ✅ |
| `MEDIA_CREATE` | `"media.create"` | 服务端→客户端 | ✅ |
| `PONG` | `"pong"` | 服务端→客户端 | ✅ |
| `ERROR` | `"error"` | 服务端→客户端 | ✅ |

**结论: ✅ 完全匹配** — 所有 12 个消息类型常量与 picoclaw 源码 (`protocol.go`) 完全一致。

### 3. 认证机制 (Bearer Token)

picoclaw 服务端支持的认证方式:
1. `Authorization: Bearer <token>` Header — **主要方式**
2. `Sec-WebSocket-Protocol: token.<value>` — 浏览器备用
3. Query parameter `token` — 仅当 `AllowTokenQuery=true`

Android 实现:
- `PicoGatewayClient.connect()` 通过 `requestBuilder.header("Authorization", "Bearer $it")` 发送 Bearer Token
- `PicoApiClient` 的拦截器中也正确添加了 `Authorization: Bearer <token>` 头

**结论: ✅ 正确实现** — 使用了服务端的主要认证方式。

### 4. 心跳机制

picoclaw 服务端实现:
- 使用 **WebSocket 原生 Ping/Pong 帧** (通过 `pingLoop` 发送 Ping)
- 默认间隔：30 秒
- 设定了 `SetPongHandler` 来更新读取超时

Android 实现:
- 使用 OkHttp 内置的 `pingInterval(30, TimeUnit.SECONDS)`
- OkHttp 自动发送 WebSocket Ping 帧，30 秒间隔

**结论: ✅ 正确实现** — 使用 WebSocket 原生 Ping，间隔一致（30秒），无需 JSON 消息级别的心跳。

### 5. `message.send` 发送逻辑

Android `PicoGatewayClient.sendMessage()`:
- 构建 `PicoMessage(type="message.send", id=..., payload={content, attachments})`
- 通过 WebSocket 发送 JSON

picoclaw 服务端接收:
- `handleMessageSend()` 解析 `content` 字段和 `parseInlineImageMedia(msg.Payload)`（同时检查 `media` 和 `attachments` 字段）

**结论: ✅ 正确实现**

### 6. WebSocket 连接管理

- ✅ 支持 `session_id` 查询参数（通过 `buildUrl()` 附加）
- ✅ 连接状态机 (Disconnected → Connecting → Connected → Error)
- ✅ 连接/断开生命周期管理
- ✅ Origin Header 推导

### 7. 消息接收处理 (Repository 层)

- ✅ `handleMessageCreate` 正确处理 `message.create`：提取 `content`, `message_id`, `kind`, `placeholder`, `attachments`
- ✅ `handleMessageUpdate` 正确处理 `message.update`：按 `message_id` 增量追加或更新
- ✅ `handleMessageDelete` 正确处理 `message.delete`：按 `message_id` 移除消息
- ✅ `media.create` 降级为 `message.create` 处理（与服务器行为一致）
- ✅ 实现了消息去重 (`seenMessageIds`)
- ✅ Agent 超时机制（120 秒）

### 8. REST API 路径 — 大部分正确

| API 端点 | Android 路径 | picoclaw 实际路径 | 匹配 |
|:---------|:------------|:-----------------|:----:|
| 模型列表 | `GET /api/models` | `GET /api/models` | ✅ |
| 添加模型 | `POST /api/models` | `POST /api/models` | ✅ |
| 更新模型 | `PUT /api/models/{index}` | `PUT /api/models/{index}` | ✅ |
| 删除模型 | `DELETE /api/models/{index}` | `DELETE /api/models/{index}` | ✅ |
| 测试模型 | `POST /api/models/{index}/test` | `POST /api/models/{index}/test` | ✅ |
| 设置默认模型 | `POST /api/models/default` | `POST /api/models/default` | ⚠️ **见下** |
| 网关状态 | `GET /api/gateway/status` | `GET /api/gateway/status` | ✅ |
| 网关日志 | `GET /api/gateway/logs` | `GET /api/gateway/logs` | ✅ |
| 完整配置 | `GET /api/config` | `GET /api/config` | ✅ |
| 已安装技能 | `GET /api/skills` | `GET /api/skills` | ✅ |
| 技能详情 | `GET /api/skills/{name}` | `GET /api/skills/{name}` | ✅ |
| 搜索技能 | `GET /api/skills/search?q=` | `GET /api/skills/search?q=` | ✅ |
| 安装技能 | `POST /api/skills/install` | `POST /api/skills/install` | ✅ |
| 删除技能 | `DELETE /api/skills/{name}` | `DELETE /api/skills/{name}` | ✅ |
| 版本信息 | `GET /api/system/version` | `GET /api/system/version` | ✅ |

---

## 协议实现中的偏差或遗漏

### 问题 1: 🔴 `setDefaultModel` 请求体格式不匹配

**严重性: 高** — 此 API 调用将总是失败。

**Android 实现** (`PicoApiClient.kt:116-119`):
```kotlin
suspend fun setDefaultModel(modelRef: String): Result<Unit> {
    return post("/api/models/default", mapOf("model" to modelRef)).map { }
}
```

**picoclaw 服务端期望** (`models.go`):
```go
var req struct {
    ModelName string `json:"model_name"`
}
if err = json.Unmarshal(body, &req); err != nil { ... }
if req.ModelName == "" {
    http.Error(w, "model_name is required", ...)
}
```

**偏差描述**: Android 发送 `{"model": "model-ref"}`，但服务端期望 `{"model_name": "model-ref"}`。服务端会因找不到 `model_name` 字段而返回 400 Bad Request。

**修复建议**: 将 `mapOf("model" to modelRef)` 改为 `mapOf("model_name" to modelRef)`。

---

### 问题 2: 🔴 `ToolCall` 数据结构与服务器不匹配

**严重性: 中** — 工具调用消息无法在客户端正确解析展示。

**Android 实现** (`PicoModels.kt`):
```kotlin
data class ToolCall(
    val name: String,
    val arguments: String  // JSON 字符串
)
```

**picoclaw 服务端发送格式** (`visible_tool_calls.go`):
```json
{
  "id": "call_xxx",
  "type": "function",
  "function": {
    "name": "tool_name",
    "arguments": "{\"key\": \"value\"}"
  },
  "extra_content": {
    "tool_feedback_explanation": "..."
  }
}
```

**偏差描述**: Android 客户端期望 `name` 和 `arguments` 在顶层，但服务器将它们嵌套在 `function` 对象内部。此外，服务器还会可选发送 `id`、`type`、`extra_content` 等字段，客户端无法接收。

**修复建议**: 更新 `ToolCall` 数据结构以匹配服务器格式：
```kotlin
data class ToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: ToolCallFunction? = null,
    @SerializedName("extra_content")
    val extraContent: ToolCallExtraContent? = null
)

data class ToolCallFunction(
    val name: String? = null,
    val arguments: String? = null
)

data class ToolCallExtraContent(
    @SerializedName("tool_feedback_explanation")
    val toolFeedbackExplanation: String? = null
)
```

---

### 问题 3: 🟡 `media.send` 类型已定义但从未使用

**严重性: 低** — 非功能性缺陷，但反映了协议覆盖不完整。

**描述**: `PicoMessageType` 中定义了 `MEDIA_SEND = "media.send"` 常量，但 `PicoGatewayClient` 或 `PicoRepository` 中没有任何发送 `media.send` 的逻辑。picoclaw 服务端将 `type: "media.send"` 与 `type: "message.send"` 同等处理（`handleMessageSend`），但客户端从未利用此能力发送媒体。

**修复建议**: 如不需要媒体发送，可考虑移除未使用的常量以减少混淆；如需支持，应增加发送 `media.send` 类型消息的接口。

---

### 问题 4: 🟡 `typing.start` / `typing.stop` 接收处理不完整

**严重性: 低**

**描述**: 在 `PicoRepository.handlePicoMessage()` 中，`TYPING_START` 和 `TYPING_STOP` 被作为空 case 处理（仅注释 "AI 开始回复" / "AI 回复结束"），没有任何实际的 UI 状态更新。而服务端会将这两个类型**双向**发送（客户端发→服务端表示用户正在输入；服务端发→客户端表示 AI 正在思考）。

**修复建议**: 考虑将 `typing.start`/`typing.stop` 映射到 UI 层的输入指示器状态，以显示 AI 正在回复的视觉反馈。

---

### 问题 5: 🟡 `message_id` 缺失时的保底逻辑可能产生异常

**严重性: 低**

**描述**: 在 `handleMessageUpdate` 中，当 `message_id` 为 null 时，客户端会尝试将新内容追加到最后一条 assistant 消息的末尾。这在理想情况下不应发生（服务端始终发送 `message_id`），但如果出现乱序或协议异常，可能将不属于同一消息的内容拼接在一起。

**修复建议**: 这是合理的防御性编程，无需修复，但建议添加日志记录以便调试。

---

### 问题 6: 🟢 `Placeholder` 消息处理正确

**状态: 已正确实现** — `handleMessageCreate` 中明确检查 `placeholder` 字段并跳过显示占位消息，与服务端 `SendPlaceholder` 发送的 `{"placeholder": true}` 一致。

### 问题 7: 🟢 `ContextUsage` 解析正确

**状态: 已正确实现** — `MessageCreatePayload.contextUsage` 字段映射和 `ContextUsage` 数据类与服务端 `setContextUsagePayload` 发送格式一致。

---

## 建议的修复优先级

| 优先级 | 问题 | 影响 | 修复工作量 |
|:------:|:----|:----|:----------:|
| 🔴 P0 | `setDefaultModel` 请求体字段名 | API 调用 100% 失败 | 5 分钟 |
| 🔴 P0 | `ToolCall` 数据结构 | 工具调用消息无法正确显示 | 30 分钟 |
| 🟡 P2 | `media.send` 未使用 | 无运行时影响 | 可选 |
| 🟡 P3 | typing 状态未映射到 UI | 用户体验轻微影响 | 1 小时 |

---

## 总体评估

**综合评分: 90/100**

Android 客户端对 Pico 协议的实现总体上非常出色。核心的协议帧结构、消息类型、认证机制、心跳、消息发送/接收等关键路径均与 picoclaw 服务端 0.2.6 版本一致。

需要立即修正的两个关键问题：
1. **`setDefaultModel` 请求体字段名** (`model` → `model_name`) — 小改动，高影响
2. **`ToolCall` 数据结构**需嵌套以匹配服务器发送格式 — 影响工具调用展示
