package com.picoclaw.manager.data.repository

import android.util.Log
import com.google.gson.Gson
import com.picoclaw.manager.data.pico.ChatMessage
import com.picoclaw.manager.data.pico.PicoConnectionState
import com.picoclaw.manager.data.pico.PicoGatewayClient
import com.picoclaw.manager.data.pico.PicoMessage
import com.picoclaw.manager.data.pico.PicoMessageType
import com.picoclaw.manager.data.pico.PicoSession
import com.picoclaw.manager.data.remote.PicoApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Pico 协议数据仓库。
 *
 * 混合架构：
 * - Chat 功能通过 WebSocket（PicoGatewayClient）与 pico 协议通信
 * - 模型/状态/Skill 管理通过 REST API（PicoApiClient）通信
 */
class PicoRepository {

    companion object {
        private const val TAG = "PicoRepository"
    }

    private val gson = Gson()
    private var wsClient: PicoGatewayClient? = null
    private var apiClient: PicoApiClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ==================== 连接状态 ====================

    private val _connectionState = MutableStateFlow<PicoConnectionState>(PicoConnectionState.Disconnected)
    val connectionState: StateFlow<PicoConnectionState> = _connectionState.asStateFlow()

    // ==================== 网关状态 ====================

    private val _gatewayStatus = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val gatewayStatus: StateFlow<Map<String, Any?>> = _gatewayStatus.asStateFlow()

    private val _systemVersion = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val systemVersion: StateFlow<Map<String, Any?>> = _systemVersion.asStateFlow()

    // ==================== 模型管理 ====================

    private val _models = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
    val models: StateFlow<List<Map<String, Any?>>> = _models.asStateFlow()

    private val _defaultModel = MutableStateFlow<String?>(null)
    val defaultModel: StateFlow<String?> = _defaultModel.asStateFlow()

    private val _configSetError = MutableStateFlow<String?>(null)
    val configSetError: StateFlow<String?> = _configSetError.asStateFlow()

    // ==================== Skill 管理 ====================

    private val _skills = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
    val skills: StateFlow<List<Map<String, Any?>>> = _skills.asStateFlow()

    // ==================== 聊天 ====================

    /** 当前使用的 sessionId。 */
    private var currentSessionId: String = UUID.randomUUID().toString()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    /** 切换 Tab 时缓存消息避免 Composer 崩溃。 */
    private var cachedChatMessages: List<ChatMessage> = emptyList()
    fun cacheAndClearChatMessages() {
        cachedChatMessages = _chatMessages.value
        _chatMessages.value = emptyList()
        Log.d(TAG, "cached ${cachedChatMessages.size} messages")
    }
    fun restoreChatMessages() {
        if (cachedChatMessages.isNotEmpty()) {
            _chatMessages.value = cachedChatMessages
            cachedChatMessages = emptyList()
            Log.d(TAG, "restored ${_chatMessages.value.size} messages")
        }
    }

    private val _chatSendError = MutableStateFlow<String?>(null)
    val chatSendError: StateFlow<String?> = _chatSendError.asStateFlow()

    /** Agent 正在回复中的状态。 */
    private val _agentRunInProgress = MutableStateFlow(false)
    val agentRunInProgress: StateFlow<Boolean> = _agentRunInProgress.asStateFlow()

    private var agentRunTimeoutJob: Job? = null

    /** 上一轮回复的最后一条 message_id，用于判断流式更新结束状态。 */
    private var lastAssistantMessageId: String? = null
    /** 已收到的 message_id 集合，用于去重。 */
    private val seenMessageIds = mutableSetOf<String>()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ==================== 连接管理 ====================

    fun connect(
        wsUrl: String,
        apiUrl: String,
        token: String? = null,
        sessionId: String? = null
    ) {
        disconnect()
        _errorMessage.value = null

        currentSessionId = sessionId ?: UUID.randomUUID().toString()

        // 初始化 API 客户端
        apiClient = PicoApiClient(apiUrl, token)

        // 初始化 WebSocket 客户端
        wsClient = PicoGatewayClient(wsUrl, token, currentSessionId).apply {
            setMessageListener { message -> handlePicoMessage(message) }
            setOnConnectFailedListener { msg ->
                _errorMessage.value = msg
            }
            connect()
        }

        // 监听连接状态
        scope.launch {
            wsClient?.connectionState?.collect { state ->
                _connectionState.value = state
                if (state is PicoConnectionState.Connected) {
                    Log.d(TAG, "Connected to picoclaw via pico protocol")
                    // 连接成功后刷新信息
                    refreshAll()
                }
            }
        }
    }

    fun disconnect() {
        wsClient?.disconnect()
        wsClient = null
        apiClient = null
        _connectionState.value = PicoConnectionState.Disconnected
        _gatewayStatus.value = emptyMap()
        _systemVersion.value = emptyMap()
        _models.value = emptyList()
        _defaultModel.value = null
        _configSetError.value = null
        _skills.value = emptyList()
        _chatMessages.value = emptyList()
        _chatSendError.value = null
        _errorMessage.value = null
        clearAgentRunInProgress()
        seenMessageIds.clear()
        lastAssistantMessageId = null
    }

    // ==================== 消息处理 ====================

    /**
     * 处理从 Pico 协议 WebSocket 收到的消息。
     */
    private fun handlePicoMessage(message: PicoMessage) {
        val payload = message.payload ?: return

        when (message.type) {
            PicoMessageType.MESSAGE_CREATE -> {
                handleMessageCreate(payload)
            }
            PicoMessageType.MESSAGE_UPDATE -> {
                handleMessageUpdate(payload)
            }
            PicoMessageType.MESSAGE_DELETE -> {
                handleMessageDelete(payload)
            }
            PicoMessageType.MEDIA_CREATE -> {
                handleMessageCreate(payload)  // 同 message.create 处理
            }
            PicoMessageType.TYPING_START -> {
                // AI 开始回复
            }
            PicoMessageType.TYPING_STOP -> {
                // AI 回复结束
            }
            PicoMessageType.ERROR -> {
                val errMsg = payload["message"]?.toString() ?: "服务器错误"
                val code = payload["code"]?.toString()
                Log.e(TAG, "Pico error: $code $errMsg")
            }
        }
    }

    /**
     * 处理 message.create。
     * pico 协议中，AI 回复的首帧为 create，后续流式更新为 update。
     */
    private fun handleMessageCreate(payload: Map<String, Any?>) {
        val content = extractDisplayText(payload["content"])
        val messageId = payload["message_id"]?.toString() ?: UUID.randomUUID().toString()
        val kind = payload["kind"]?.toString() ?: "normal"
        val placeholder = payload["placeholder"] as? Boolean ?: false

        // 去重
        if (messageId in seenMessageIds) return
        seenMessageIds.add(messageId)

        if (placeholder) {
            // 占位消息不显示
            return
        }

        // 处理 attachments
        val attachments = parseAttachments(payload["attachments"])

        val chatMsg = ChatMessage(
            role = "assistant",
            content = content,
            id = messageId,
            kind = kind,
            attachments = attachments
        )

        when (kind) {
            "thought" -> {
                // 思考消息可以特殊渲染
                _chatMessages.value = _chatMessages.value + chatMsg
            }
            "tool_calls" -> {
                // 工具调用消息
                _chatMessages.value = _chatMessages.value + chatMsg
            }
            else -> {
                // 普通消息——可能是流式首帧
                _chatMessages.value = _chatMessages.value + chatMsg
                lastAssistantMessageId = messageId
                clearAgentRunInProgress()
            }
        }
    }

    /**
     * 处理 message.update（流式增量更新）。
     * pico 协议中，AI 回复的后续片段通过 update 推送。
     */
    private fun handleMessageUpdate(payload: Map<String, Any?>) {
        val content = extractDisplayText(payload["content"])
        val messageId = payload["message_id"]?.toString()
        val contextUsage = payload["context_usage"]

        if (messageId == null) {
            // 无 message_id 的直接追加到最后一条
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
        } else {
            // 按 message_id 更新或追加
            val list = _chatMessages.value.toMutableList()
            val idx = list.indexOfLast { it.id == messageId }
            if (idx >= 0) {
                val existing = list[idx]
                val newContent = when {
                    content.length > existing.content.length && content.startsWith(existing.content) -> content
                    else -> existing.content + content
                }
                list[idx] = existing.copy(content = newContent)
                _chatMessages.value = list
            } else {
                // 新消息
                seenMessageIds.add(messageId)
                val chatMsg = ChatMessage(
                    role = "assistant",
                    content = content,
                    id = messageId,
                    kind = payload["kind"]?.toString()
                )
                _chatMessages.value = _chatMessages.value + chatMsg
            }
        }

        // 如果携带 context_usage，表示回复结束
        if (contextUsage != null) {
            lastAssistantMessageId = messageId ?: lastAssistantMessageId
            clearAgentRunInProgress()
        }
    }

    /**
     * 处理 message.delete。
     */
    private fun handleMessageDelete(payload: Map<String, Any?>) {
        val messageId = payload["message_id"]?.toString() ?: return
        _chatMessages.value = _chatMessages.value.filter { it.id != messageId }
    }

    /**
     * 解析 attachments 字段。
     */
    private fun parseAttachments(attachments: Any?): List<com.picoclaw.manager.data.pico.Attachment>? {
        if (attachments == null) return null
        val list = when (attachments) {
            is List<*> -> attachments
            is String -> {
                try {
                    gson.fromJson(attachments, Array<Map<String, Any?>>::class.java)?.toList()
                } catch (_: Exception) { null }
            }
            else -> null
        } ?: return null
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            com.picoclaw.manager.data.pico.Attachment(
                type = m["type"]?.toString(),
                url = m["url"]?.toString(),
                filename = m["filename"]?.toString(),
                contentType = m["content_type"]?.toString()
            )
        }.takeIf { it.isNotEmpty() }
    }

    // ==================== 数据刷新 ====================

    /**
     * 刷新所有数据。
     */
    suspend fun refreshAll() {
        refreshGatewayStatus()
        refreshModels()
        refreshSkills()
        refreshVersion()
    }

    /**
     * 刷新网关状态。
     */
    suspend fun refreshGatewayStatus() = withContext(Dispatchers.IO) {
        runCatching {
            apiClient?.getGatewayStatus()?.getOrNull()?.let {
                _gatewayStatus.value = it
            }
        }.onFailure { e ->
            Log.w(TAG, "refreshGatewayStatus failed: ${e.message}")
        }
    }

    /**
     * 刷新版本信息。
     */
    suspend fun refreshVersion() = withContext(Dispatchers.IO) {
        runCatching {
            apiClient?.getVersion()?.getOrNull()?.let {
                _systemVersion.value = it
            }
        }.onFailure { e ->
            Log.w(TAG, "refreshVersion failed: ${e.message}")
        }
    }

    /**
     * 刷新模型列表。
     */
    suspend fun refreshModels() = withContext(Dispatchers.IO) {
        runCatching {
            apiClient?.getModels()?.getOrNull()?.let { modelList ->
                _models.value = modelList
                // 尝试从 status 中获取默认模型
                val status = _gatewayStatus.value
                val defaultModelName = status["config_default_model"]?.toString()
                    ?: status["boot_default_model"]?.toString()
                if (defaultModelName != null) {
                    _defaultModel.value = defaultModelName
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "refreshModels failed: ${e.message}")
        }
    }

    /**
     * 刷新 Skill 列表。
     */
    suspend fun refreshSkills() = withContext(Dispatchers.IO) {
        runCatching {
            apiClient?.getSkills()?.getOrNull()?.let {
                _skills.value = it
            }
        }.onFailure { e ->
            Log.w(TAG, "refreshSkills failed: ${e.message}")
        }
    }

    // ==================== 模型操作 ====================

    /**
     * 设置默认模型。
     */
    suspend fun setDefaultModel(modelRef: String): Result<Unit> = withContext(Dispatchers.IO) {
        _configSetError.value = null
        val client = apiClient ?: return@withContext Result.failure(IllegalStateException("未连接"))
        val result = client.setDefaultModel(modelRef)
        if (result.isSuccess) {
            _defaultModel.value = modelRef
            runCatching { refreshModels() }
            Result.success(Unit)
        } else {
            val errMsg = result.exceptionOrNull()?.message ?: "保存失败"
            _configSetError.value = errMsg
            Result.failure(Exception(errMsg))
        }
    }

    fun clearConfigSetError() { _configSetError.value = null }

    // ==================== Skill 操作 ====================

    /**
     * 搜索 Registry 中的 Skill。
     */
    suspend fun searchSkills(query: String): Result<List<Map<String, Any?>>> {
        return apiClient?.searchSkills(query) ?: Result.success(emptyList())
    }

    /**
     * 安装 Skill。
     */
    suspend fun installSkill(slug: String, registry: String = "clawhub"): Result<Unit> {
        return apiClient?.installSkill(slug, registry) ?: Result.failure(IllegalStateException("未连接"))
    }

    /**
     * 删除 Skill。
     */
    suspend fun uninstallSkill(name: String): Result<Unit> {
        return apiClient?.uninstallSkill(name) ?: Result.failure(IllegalStateException("未连接"))
    }

    // ==================== 聊天操作 ====================

    /**
     * 发送聊天消息。
     */
    suspend fun sendChatMessage(body: String): Result<Unit> = withContext(Dispatchers.IO) {
        _chatSendError.value = null
        val client = wsClient ?: run {
            _chatSendError.value = "未连接"
            return@withContext Result.failure(IllegalStateException("未连接"))
        }

        // 添加到本地消息列表
        val userMsg = ChatMessage(role = "user", content = body, id = "user-${UUID.randomUUID()}")
        _chatMessages.value = _chatMessages.value + userMsg

        // 通过 WebSocket 发送
        val msgId = client.sendMessage(body)
        if (msgId != null) {
            _agentRunInProgress.value = true
            agentRunTimeoutJob?.cancel()
            agentRunTimeoutJob = scope.launch {
                delay(120_000)  // 2 分钟超时
                _agentRunInProgress.value = false
            }
            Result.success(Unit)
        } else {
            _chatSendError.value = "发送失败"
            Result.failure(Exception("发送失败"))
        }
    }

    // ==================== 工具方法 ====================

    fun clearError() { _errorMessage.value = null }
    fun clearChatSendError() { _chatSendError.value = null }

    private fun clearAgentRunInProgress() {
        agentRunTimeoutJob?.cancel()
        agentRunTimeoutJob = null
        _agentRunInProgress.value = false
    }

    /**
     * 提取文本显示内容（处理各种嵌套格式）。
     */
    private fun extractDisplayText(any: Any?): String {
        if (any == null) return ""
        if (any is String) return any
        if (any is List<*>) {
            return any.joinToString("") { extractDisplayText(it) }
        }
        if (any is Map<*, *>) {
            return (any["text"] ?: any["content"] ?: any["body"])?.toString() ?: any.toString()
        }
        return any.toString()
    }
}
