现在我已经掌握了所有核心文件的完整内容。下面给出详细的代码质量审查报告。

---

# PicoClawManagerForAndroid 代码质量审查报告

## 0. 审查概览

| 维度 | 评价 |
|---|---|
| 总体架构 | 单 Repository + 多 Profile 缓存的思路合理，但分层与生命周期管理有缺陷 |
| Kotlin 风格 | 中等，存在较多 Java 习惯写法 |
| 协程/资源管理 | **多处严重问题**（Job 泄漏、CoroutineScope 未关闭、WebSocket 监听器持有） |
| 并发安全 | **多处 MutableStateFlow 跨线程写入风险** |
| 空安全 | 协议层大量可空字段 + `as?` / `!!` 混用，部分路径存在 NPE 风险 |
| 可测试性 | **较差**：静态工具类、共享可变状态、依赖硬编码 |

---

## 1. 高优先级问题（必须修复）

### 🔴 H1. `PicoRepository` 的 `CoroutineScope` 永远不会关闭 → 内存/资源泄漏
**位置**：`PicoRepository.kt:30`
```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
```
**问题**：
- `PicoRepository` 由 `MainViewModel` 用 `ConcurrentHashMap` 长期持有（`MainViewModel.kt:46`），整个 App 生命周期内常驻。
- `scope` 从未调用 `cancel()`，`agentRunTimeoutJob`、连接状态 `collect` 协程等都会一直挂着。
- `MainViewModel.onCleared()` 只调用了 `disconnect()`，没有 cancel 任何 Repository 内部的 scope。Repository 内部 `scope.launch { wsClient?.connectionState?.collect ... }`（`PicoRepository.kt:111`）的协程会因 `wsClient = null` 而悬挂等待，永远不会退出。
- 切换 Profile 时旧的 Repository 不会被 GC（因为 `repositories` map 持续持有），其内部协程仍在跑。

**修复建议**：
```kotlin
// PicoRepository 改为接收外部 scope（推荐）
class PicoRepository(private val scope: CoroutineScope) { ... }

// 或者提供 close() 方法
fun close() {
    scope.cancel()
    disconnect()
}
```
并在 `MainViewModel.removeActiveProfile()` / `onCleared()` 中调用。

---

### 🔴 H2. WebSocket 监听器持有外部可变状态 + 跨线程写入 `MutableStateFlow`
**位置**：`PicoGatewayClient.kt:71-93`
```kotlin
override fun onMessage(webSocket: WebSocket, text: String) {
    handleMessage(text)   // OkHttp 的 WebSocket 回调在 OkHttp 内部线程池
}
```
**问题**：
- `OkHttp WebSocketListener` 的回调运行在 OkHttp 内部的 `Dispatcher` 线程池（非主线程）。
- `handleMessage` 触发 `messageListener?.invoke(message)` → `PicoRepository.handlePicoMessage` → **直接对 `_connectionState`、`_chatMessages`、`_aiTyping` 等 `MutableStateFlow.value` 赋值**（`PicoRepository.kt:166, 175, 235, 269` 等）。
- `MutableStateFlow.value` 的 setter 本身是线程安全的，但**业务上的"读-改-写"**（如 `seenMessageIds.add(messageId)`、`_chatMessages.value = _chatMessages.value + chatMsg`）**不是原子的**，会引发竞态。
- 典型竞态场景：服务端在极短时间内连发 `MESSAGE_CREATE` 和 `MESSAGE_UPDATE`（同 `message_id`），两个回调在不同线程同时跑 `handleMessageCreate` 和 `handleMessageUpdate`，都执行 `seenMessageIds.add` + `_chatMessages.value = ... +`，最终列表里会出现两条同 ID 的消息或顺序错乱。

**修复建议**：
```kotlin
// PicoRepository 内部统一一个 single-thread dispatcher 处理 WS 消息
private val wsDispatcher = Dispatchers.Default.limitedParallelism(1)
private val wsScope = CoroutineScope(SupervisorJob() + wsDispatcher)

private fun handlePicoMessage(message: PicoMessage) = wsScope.launch {
    // 所有消息处理串行化
    ...
}
```
或者在 `PicoGatewayClient` 内部用 `Channel<PicoMessage>` 把回调转成 Flow，由 Repository 端 `launchIn(scope)` 消费。

---

### 🔴 H3. `agentRunTimeoutJob` 的 Job 泄漏与超时后状态污染
**位置**：`PicoRepository.kt:340-345`
```kotlin
agentRunTimeoutJob?.cancel()
agentRunTimeoutJob = scope.launch {
    delay(120_000)  // 2 分钟超时
    _agentRunInProgress.value = false
}
```
**问题**：
- 每次发消息都启动一个 120s 的超时任务，但**正常完成时（`clearAgentRunInProgress`）只是 cancel 了它**，逻辑没问题；**真正的问题在异常路径**：
  - 如果用户连续发消息 10 次，理论上每次都会 cancel 上一个，看似 OK。
  - 但 `clearAgentRunInProgress` 不会重置 `_aiTyping` 的超时：如果 AI 在 typing.start 后卡死（服务端没发 typing.stop），`_aiTyping` 会永远停在 `true`，UI 上"AI 正在输入…"指示器不会消失。
- 更严重：`scope` 永远不会 cancel（见 H1），所以这些 Job 即使逻辑上 cancel 了，其父 scope 仍存活。

**修复建议**：
- 给 `_aiTyping` 也加一个独立的超时 Job。
- `clearAgentRunInProgress` 应该在所有"agent 停止"路径（连接断开、收到 stop、超时）都被调用。

---

### 🔴 H4. `MainViewModel._activeRepo` 切换时旧 Repository 的协程仍在运行
**位置**：`MainViewModel.kt:46-50, 156-167`
```kotlin
private val _activeRepo = MutableStateFlow<PicoRepository>(PicoRepository())
...
fun setActiveProfile(profileId: String) {
    ...
    _activeRepo.value = repoFor(profileId)  // 切换 active repo
    ...
}
```
**问题**：
- `flatMapLatest` 会自动取消上一个 repo 的 flow 收集，**但不会取消 repo 内部 `scope.launch { wsClient?.connectionState?.collect ... }`**（见 H1）。
- 切到新 Profile 后，旧 Repository 仍持有 `wsClient`、WebSocket 连接、`OkHttpClient`（虽然 OkHttpClient 共享了，但 dispatcher 还在跑）。
- `repositories` map 持续累积，永远不释放。

**修复建议**：
- 给 `PicoRepository` 加 `close()`，在 `removeActiveProfile` / `onCleared` 中调用。
- 或者改用 `viewModelScope` 注入到 Repository，避免它自己持有 scope。

---

### 🔴 H5. `MessageCreatePayload` / `PicoMessage.payload` 解析时 `as?` 强转的 NPE 风险
**位置**：`PicoRepository.kt:189-194`（`handleMessageCreate`）
```kotlin
val content = extractDisplayText(payload["content"])
val messageId = payload["message_id"]?.toString() ?: UUID.randomUUID().toString()
val kind = payload["kind"]?.toString() ?: "normal"
val placeholder = payload["placeholder"] as? Boolean ?: false
```
**问题**：
- `payload["content"]` 可能是 `null`、`String`、嵌套 `List/Map`（多模态内容），`extractDisplayText` 处理了这些但**没有处理 `Number`/`Boolean`**——会走 `any.toString()`，把 `123` 转成 `"123"` 没问题，但语义上可能不正确（数字 content 不应该当文本）。
- `payload["attachments"]` 在 `parseAttachments` 中 `as? Map<*, *>`，但 `item as? Map<*, *>` 在 `item` 是 `JSONObject`（来自 Gson 默认反序列化）时**可能不是 `Map`**——Gson 默认把 JSON 对象解析为 `LinkedTreeMap`，但如果上游用 `JSONObject` 手动塞进来就会失败。
- `payload["context_usage"]`（`PicoRepository.kt:235`）直接当对象使用，没有类型校验；如果服务端发的是 `String` 或 `null`，后续 `seenMessageIds.add(messageId)` 仍可工作，但 `clearAgentRunInProgress` 的判断条件失效。

**修复建议**：
- 所有 `payload["xxx"]?.toString()` 都应通过专门的解析函数（如 `payload.stringOrNull("content")`），并对关键字段做强类型校验。
- 用 sealed class 包装解析结果（成功/失败/缺失字段）。

---

### 🔴 H6. `PicoApiClient` 中 OkHttp 同步调用在 `Dispatchers.IO` 上执行但未做取消传播
**位置**：`PicoApiClient.kt:43-55`
```kotlin
private fun get(path: String): Result<String> = runCatching {
    val request = Request.Builder().url(...).get().build()
    val response = client.newCall(request).execute()  // 阻塞
    ...
}
```
**问题**：
- `client.newCall(request).execute()` 是**阻塞调用**，在 `Dispatchers.IO` 上跑没问题，但**当协程被取消时 OkHttp 不会自动中断**——协程虽然被取消，线程仍会阻塞到 HTTP 完成才返回。
- 如果用户在网络慢时快速切换 Profile / 关闭页面，已经发出去的请求仍会执行完，浪费资源并可能写入已 dispose 的 StateFlow（虽然 StateFlow 写入安全，但语义上错乱）。
- `runCatching` 还会**吞掉 `CancellationException`**，导致协程取消失效！

```kotlin
runCatching {
    ...
}.onFailure { e ->
    Log.w(TAG, "refreshGatewayStatus failed: ${e.message}")
}
```
`CancellationException` 会被 `runCatching` 捕获并包装成失败结果，**协程取消语义被破坏**。这是 Kotlin 协程的经典反模式。

**修复建议**：
```kotlin
suspend fun refreshGatewayStatus() = withContext(Dispatchers.IO) {
    try {
        apiClient?.getGatewayStatus()?.getOrNull()?.let {
            _gatewayStatus.value = it
        }
    } catch (e: CancellationException) {
        throw e  // 必须重新抛出
    } catch (e: Exception) {
        Log.w(TAG, "refreshGatewayStatus failed: ${e.message}")
    }
}
```
或者改用 `OkHttp.enqueue` + `suspendCancellableCoroutine`，让 OkHttp 监听协程取消。

---

## 2. 中优先级问题

### 🟠 M1. `PicoGatewayClient` 的 `OkHttpClient` 与 `PicoApiClient` 的 `OkHttpClient` 未共享
**位置**：`PicoGatewayClient.kt:30-36`、`PicoApiClient.kt:21-29`
**问题**：每个 Repository 实例化都会新建一个 `OkHttpClient`，导致：
- 切换 Profile 时旧的 OkHttp 线程池未释放（每个 client 有自己的 `Dispatcher` 和连接池）。
- 浪费资源。

**修复建议**：通过 DI（`di/ViewModelFactory.kt` 已有）注入共享 `OkHttpClient` 单例。

---

### 🟠 M2. `PicoGatewayClient.disconnect()` 后 listener 未清理
**位置**：`PicoGatewayClient.kt:100-104`
```kotlin
fun disconnect() {
    webSocket?.close(1000, "User disconnect")
    webSocket = null
    _connectionState.value = PicoConnectionState.Disconnected
}
```
**问题**：`messageListener` 和 `onConnectFailedListener` 仍持有 Repository 引用。如果 Repository 被 GC，OkHttp 内部线程池仍可能在 `onMessage` 时调用这些 listener，导致**对已 dispose 的 Repository 写入**。

**修复建议**：
```kotlin
fun disconnect() {
    webSocket?.close(1000, "User disconnect")
    webSocket = null
    messageListener = null
    onConnectFailedListener = null
    _connectionState.value = PicoConnectionState.Disconnected
}
```

---

### 🟠 M3. `WebSocketListener.onClosing` 与 `onClosed` 的状态机不一致
**位置**：`PicoGatewayClient.kt:87-95`
```kotlin
override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
    Log.d(TAG, "WebSocket closing: $code $reason")
    // 没有更新 _connectionState
}

override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
    Log.d(TAG, "WebSocket closed: $code $reason")
    _connectionState.value = PicoConnectionState.Disconnected
}
```
**问题**：在 `onClosing` 期间，UI 仍显示 `Connected`，但其实服务端已经发起关闭。中间这段窗口期用户可能发送消息，`webSocket.send()` 会返回 `false` 但 `_chatSendError` 不会被设置（`sendMessage` 返回 `null` → `sendChatMessage` 设置"发送失败"，但用户感知滞后）。

**修复建议**：在 `onClosing` 中立即将状态设为 `PicoConnectionState.Disconnecting`（新增 sealed class 子类）。

---

### 🟠 M4. `PicoGatewayClient` 的 `connect()` 缺少重入保护
**位置**：`PicoGatewayClient.kt:65`
```kotlin
fun connect() {
    if (_connectionState.value is PicoConnectionState.Connecting) return
    webSocket?.close(1000, null)
    _connectionState.value = PicoConnectionState.Connecting
    ...
}
```
**问题**：从 `Disconnected` → `Connecting` 的判断只能挡住"正在连接中"的并发调用，但**不能挡住"已连接后再点连接"**——此时会先 `close(1000)` 再创建新 WebSocket，旧的 `onClosed` 回调可能在新的 `onOpen` 之后到达，导致状态机错乱。

**修复建议**：用单调递增的 `connectGeneration: AtomicInteger` 标记每次连接尝试，回调里校验 generation。

---

### 🟠 M5. `PicoRepository.handleMessageUpdate` 的"无 message_id 追加到最后一条"逻辑脆弱
**位置**：`PicoRepository.kt:230-242`
```kotlin
if (messageId == null) {
    val list = _chatMessages.value
    if (list.isNotEmpty() && list.last().role == "assistant") {
        val last = list.last()
        val newContent = when {
            content.length > last.content.length && content.startsWith(last.content) -> content
            else -> last.content + content
        }
        _chatMessages.value = list.dropLast(1) + last.copy(content = newContent)
    } else if (content.isNotBlank()) {
        _chatMessages.value = list + ChatMessage(role = "assistant", content = content)
    }
}
```
**问题**：
- 跨线程读写 `_chatMessages.value`（见 H2），`list.last()` 取到的可能不是真正的"最后一条"。
- `content.startsWith(last.content)` 判断在多线程下也不可靠：刚取到 `last.content = "Hel"`，另一个线程把消息更新成 `"Hello world"`，本线程再判断 `content.startsWith("Hel")` 仍为 true，于是覆盖成 `"Hello world"`——看似 OK，但**如果另一个线程改成 `"HelloX"`，本线程的 `content` 是 `"Hello"`，startsWith 为 true，会覆盖丢失 `"X"`**。
- `last.content + content` 在 `content` 是完整新内容时会重复追加。

**修复建议**：流式更新必须**有 message_id**，并在 Repository 端用单一线程串行处理（见 H2）。

---

### 🟠 M6. `PicoModels.kt` 中 `MessageSendPayload` 定义了但未使用
**位置**：`PicoModels.kt:39-43`
```kotlin
data class MessageSendPayload(
    val content: String? = null,
    val media: List<String>? = null,
    val attachments: List<Attachment>? = null
)
```
**问题**：定义了 `MessageSendPayload`、`MessageDeletePayload`、`PicoErrorPayload`、`ToolCall*`、`ContextUsage` 等多个 data class，但 `PicoGatewayClient` 和 `PicoRepository` 全部用 `Map<String, Any?>` 手动构造/解析 payload，**这些 data class 完全未被使用**。

**修复建议**：
- 要么删掉这些未使用的 data class；
- 要么在 `sendMessage` / `handleMessageCreate` 等处使用它们，享受 Gson 类型安全的反序列化。

---

### 🟠 M7. `PicoModels.kt` 中 `ChatMessage` 缺少 `equals/hashCode` 一致性所需的字段
**位置**：`PicoModels.kt:128-134`
```kotlin
data class ChatMessage(
    val role: String,
    val content: String,
    val id: String? = null,
    val kind: String? = null,
    val attachments: List<Attachment>? = null
)
```
**问题**：`Attachment` 也是 data class，但 `ChatMessage` 用作 `MutableStateFlow.value` 的元素、`distinctUntilChanged` 比较时（如果将来用上）会触发不必要的重组——`List<Attachment>` 的 `equals` 是引用比较，但 `data class` 的 `equals` 是结构比较，**这里没问题**。但 `ChatMessage` 在 `_chatMessages.value = list + chatMsg` 这种"全列表替换"模式下，每次都创建新 list，Compose 会全量重组。

**修复建议**：在 UI 层用 `key(msg.id)` 包裹 `ChatBubble`，并考虑改用 `LazyColumn`（见 M11）。

---

### 🟠 M8. `MainViewModel.repoFor` 在 `init` 块里立即创建 Repository 但未触发 connect
**位置**：`MainViewModel.kt:107-109`
```kotlin
init {
    _activeRepo.value = repoFor(_activeProfileId.value)
}
```
**问题**：`init` 里创建了 Repository 实例，但 `connect()` 必须由用户显式调用。这意味着：
- App 启动时已经创建了一个 `OkHttpClient`（`PicoApiClient` 内部）和一个 `PicoGatewayClient`（虽然没 connect，但 client 已实例化）。
- 用户首次连接前，这些对象已经占着内存。

**修复建议**：延迟到 `connect()` 时再创建 Repository。

---

### 🟠 M9. `MainViewModel.connect()` 缺少对 `_wsUrl` / `_apiUrl` 有效性的校验
**位置**：`MainViewModel.kt:228-238`
```kotlin
fun connect() {
    val repo = _activeRepo.value
    repo.clearError()
    repo.connect(
        wsUrl = _wsUrl.value,
        apiUrl = _apiUrl.value,
        token = _authToken.value.takeIf { it.isNotBlank() },
        sessionId = null
    )
}
```
**问题**：
- 默认
