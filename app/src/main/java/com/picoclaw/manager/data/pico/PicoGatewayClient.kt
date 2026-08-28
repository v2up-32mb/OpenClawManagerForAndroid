package com.picoclaw.manager.data.pico

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pico 协议 WebSocket 客户端。
 *
 * 与 picoclaw 的 PicoChannel 通信，使用简单 JSON 帧协议。
 * 认证方式：Authorization Bearer Token（或 WebSocket 子协议）。
 *
 * 消息流：
 * - 客户端发送: message.send, ping, typing.start/stop
 * - 服务端推送: message.create, message.update, message.delete, media.create, pong, error, typing.start/stop
 */
class PicoGatewayClient(
    private val gatewayUrl: String,
    private val authToken: String? = null,
    private val sessionId: String? = null
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // 利用 OkHttp 内置心跳
        .build()

    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<PicoConnectionState>(PicoConnectionState.Disconnected)
    val connectionState: StateFlow<PicoConnectionState> = _connectionState.asStateFlow()

    /** 消息事件监听器： (PicoMessage) -> Unit */
    private var messageListener: ((PicoMessage) -> Unit)? = null

    /** 连接失败监听器： (message) -> Unit */
    private var onConnectFailedListener: ((String) -> Unit)? = null

    fun setMessageListener(listener: (PicoMessage) -> Unit) {
        messageListener = listener
    }

    fun setOnConnectFailedListener(listener: (String) -> Unit) {
        onConnectFailedListener = listener
    }

    fun connect() {
        if (_connectionState.value is PicoConnectionState.Connecting) return
        webSocket?.close(1000, null)
        _connectionState.value = PicoConnectionState.Connecting

        val url = buildUrl()
        val origin = originForUrl(gatewayUrl)

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Origin", origin)

        // 认证方式 1: Authorization Bearer Header
        authToken?.let {
            if (it.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $it")
            }
        }

        // 认证方式 2: WebSocket Subprotocol（浏览器场景备用）
        // 此处用 Header 即可

        webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to $gatewayUrl")
                _connectionState.value = PicoConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = PicoConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = PicoConnectionState.Error(t.message ?: "连接失败")
                onConnectFailedListener?.invoke(t.message ?: "连接失败")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = PicoConnectionState.Disconnected
    }

    /**
     * 构建 WebSocket URL，附加 session_id 参数。
     */
    private fun buildUrl(): String {
        val baseUrl = gatewayUrl.trimEnd('/')
        val sb = StringBuilder(baseUrl)
        sb.append("?")
        if (sessionId != null && sessionId.isNotBlank()) {
            sb.append("session_id=${java.net.URLEncoder.encode(sessionId, "UTF-8")}&")
        }
        // 移除末尾的 & 或 ?
        return sb.toString().trimEnd('&', '?')
    }

    /**
     * 处理收到的 JSON 消息。
     */
    private fun handleMessage(text: String) {
        try {
            val message = gson.fromJson(text, PicoMessage::class.java) ?: return
            Log.d(TAG, "recv: type=${message.type} id=${message.id}")

            when (message.type) {
                PicoMessageType.PONG -> {
                    // 心跳响应，无需处理
                }
                PicoMessageType.ERROR -> {
                    val errPayload = message.payload
                    val errMsg = errPayload?.get("message")?.toString() ?: "未知错误"
                    Log.e(TAG, "Server error: $errMsg")
                }
                else -> {
                    // 分发消息给监听器
                    messageListener?.invoke(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleMessage parse error", e)
        }
    }

    /**
     * 发送 JSON 消息。
     */
    private fun send(message: PicoMessage): Boolean {
        val json = gson.toJson(message)
        Log.d(TAG, "send: type=${message.type}")
        return webSocket?.send(json) ?: false
    }

    // ==================== 对外 API ====================

    /**
     * 发送聊天消息。
     */
    fun sendMessage(content: String, attachments: List<Attachment>? = null): String? {
        val msgId = "msg-${UUID.randomUUID()}"
        val payload = mutableMapOf<String, Any?>(
            "content" to content
        )
        if (!attachments.isNullOrEmpty()) {
            payload["attachments"] = attachments.map { mapOf(
                "type" to (it.type ?: "file"),
                "url" to (it.url ?: ""),
                "filename" to (it.filename ?: ""),
                "content_type" to (it.contentType ?: "application/octet-stream")
            )}
        }
        val message = PicoMessage(
            type = PicoMessageType.MESSAGE_SEND,
            id = msgId,
            payload = payload
        )
        return if (send(message)) msgId else null
    }

    /**
     * 发送媒体消息。
     * picoclaw 服务端将 media.send 与 message.send 同等处理。
     */
    fun sendMedia(content: String, media: List<Map<String, Any?>>): String? {
        val msgId = "msg-${UUID.randomUUID()}"
        val payload = mutableMapOf<String, Any?>(
            "content" to content,
            "media" to media
        )
        val message = PicoMessage(
            type = PicoMessageType.MEDIA_SEND,
            id = msgId,
            payload = payload
        )
        return if (send(message)) msgId else null
    }

    /**
     * 发送 typing.start 指示。
     */
    fun sendTypingStart(sessionId: String) {
        send(PicoMessage(
            type = PicoMessageType.TYPING_START,
            payload = emptyMap()
        ))
    }

    /**
     * 发送 typing.stop 指示。
     */
    fun sendTypingStop(sessionId: String) {
        send(PicoMessage(
            type = PicoMessageType.TYPING_STOP,
            payload = emptyMap()
        ))
    }

    companion object {
        private const val TAG = "PicoGatewayClient"

        /**
         * 从 ws:// 地址推导 Origin header。
         */
        private fun originForUrl(url: String): String {
            return try {
                val uri = URI(url)
                val scheme = when (uri.scheme?.lowercase()) {
                    "ws" -> "http"
                    "wss" -> "https"
                    "http" -> "http"
                    "https" -> "https"
                    else -> "http"
                }
                val authority = uri.rawAuthority ?: uri.host ?: "localhost"
                "$scheme://$authority"
            } catch (_: Exception) {
                when {
                    url.startsWith("wss://", ignoreCase = true) -> "https://" + url.removePrefix("wss://").substringBefore("/")
                    url.startsWith("ws://", ignoreCase = true) -> "http://" + url.removePrefix("ws://").substringBefore("/")
                    else -> "http://localhost"
                }
            }
        }
    }
}
