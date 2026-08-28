package com.picoclaw.manager.data.pico

/**
 * Pico Protocol — 原生 picoclaw WebSocket 协议数据模型。
 *
 * 协议基于 JSON 帧，运行在 WebSocket (ws:// / wss://) 之上。
 * 所有消息均为根级 JSON 对象，包含 type、payload 等字段。
 *
 * 参考: https://github.com/sipeed/picoclaw (PicoChannel)
 */

// ==================== 协议帧 ====================

/**
 * Pico 协议通用消息帧。
 */
data class PicoMessage(
    val type: String,
    val id: String? = null,
    @com.google.gson.annotations.SerializedName("session_id")
    val sessionId: String? = null,
    val timestamp: Long? = null,
    val payload: Map<String, Any?>? = null
)

// ==================== 消息类型常量 ====================

object PicoMessageType {
    // 客户端 → 服务端
    const val MESSAGE_SEND = "message.send"
    const val MEDIA_SEND = "media.send"
    const val PING = "ping"
    const val TYPING_START = "typing.start"
    const val TYPING_STOP = "typing.stop"

    // 服务端 → 客户端
    const val MESSAGE_CREATE = "message.create"
    const val MESSAGE_UPDATE = "message.update"
    const val MESSAGE_DELETE = "message.delete"
    const val MEDIA_CREATE = "media.create"
    const val PONG = "pong"
    const val ERROR = "error"
}

// ==================== 消息 Payload ====================

/**
 * message.send 的 payload。
 */
data class MessageSendPayload(
    val content: String? = null,
    val media: List<String>? = null,
    val attachments: List<Attachment>? = null
)

/**
 * message.create / message.update 的 payload。
 */
data class MessageCreatePayload(
    val content: String? = null,
    @com.google.gson.annotations.SerializedName("message_id")
    val messageId: String? = null,
    val kind: String? = null,       // "normal", "thought", "tool_calls"
    val thought: Boolean? = null,   // 兼容旧版客户端
    val placeholder: Boolean? = null,
    @com.google.gson.annotations.SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @com.google.gson.annotations.SerializedName("model_name")
    val modelName: String? = null,
    @com.google.gson.annotations.SerializedName("context_usage")
    val contextUsage: ContextUsage? = null,
    val attachments: List<Attachment>? = null
)

/**
 * message.delete 的 payload。
 */
data class MessageDeletePayload(
    @com.google.gson.annotations.SerializedName("message_id")
    val messageId: String
)

/**
 * 错误消息 payload。
 */
data class PicoErrorPayload(
    val code: String? = null,
    val message: String? = null,
    @com.google.gson.annotations.SerializedName("request_id")
    val requestId: String? = null
)

// ==================== 子类型 ====================

/**
 * 工具调用信息。
 */
data class ToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: ToolCallFunction? = null,
    @com.google.gson.annotations.SerializedName("extra_content")
    val extraContent: ToolCallExtraContent? = null
)

data class ToolCallFunction(
    val name: String? = null,
    val arguments: String? = null
)

data class ToolCallExtraContent(
    @com.google.gson.annotations.SerializedName("tool_feedback_explanation")
    val toolFeedbackExplanation: String? = null
)

/**
 * 上下文使用统计。
 */
data class ContextUsage(
    @com.google.gson.annotations.SerializedName("used_tokens")
    val usedTokens: Long? = null,
    @com.google.gson.annotations.SerializedName("total_tokens")
    val totalTokens: Long? = null,
    @com.google.gson.annotations.SerializedName("compress_at_tokens")
    val compressAtTokens: Long? = null,
    @com.google.gson.annotations.SerializedName("used_percent")
    val usedPercent: Double? = null
)

/**
 * 附件（媒体文件）。
 */
data class Attachment(
    val type: String? = null,       // "image", "audio", "video", "file"
    val url: String? = null,
    val filename: String? = null,
    @com.google.gson.annotations.SerializedName("content_type")
    val contentType: String? = null
)

// ==================== 聊天消息 ====================

/**
 * 客户端内部使用的聊天消息模型。
 */
data class ChatMessage(
    val role: String,          // "user", "assistant", "system"
    val content: String,
    val id: String? = null,
    val kind: String? = null,  // "normal", "thought", "tool_calls"
    val attachments: List<Attachment>? = null
)

// ==================== 连接状态 ====================

/**
 * Pico 协议连接状态。
 */
sealed class PicoConnectionState {
    object Disconnected : PicoConnectionState()
    object Connecting : PicoConnectionState()
    object Connected : PicoConnectionState()
    /** 服务端正在关闭连接，UI 应禁止发送消息（M3 修复）。 */
    object Disconnecting : PicoConnectionState()
    data class Error(val message: String) : PicoConnectionState()
}

// ==================== 会话 ====================

/**
 * Pico 会话信息。
 */
data class PicoSession(
    val id: String,
    val name: String? = null
)

// ==================== 网关 Profile ====================

/**
 * 连接的 picoclaw 实例配置。
 */
data class PicoProfile(
    val id: String,
    val name: String,
    val url: String,         // ws://host:port
    val apiUrl: String,      // http://host:port (REST API)
    val token: String
)
