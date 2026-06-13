package com.picoclaw.manager.ui

/**
 * PicoClaw 连接错误中文说明及解决提示。
 */
object ConnectErrorHelper {

    /**
     * @param message 服务端返回的 error.message
     * @return Pair(中文简短描述, 解决提示)
     */
    fun toChinese(message: String?): Pair<String, String?> {
        val msg = (message ?: "").trim().lowercase()

        // Token / 认证类
        if (msg.contains("token", ignoreCase = true) && (msg.contains("invalid") || msg.contains("missing") || msg.contains("unauthorized"))) {
            return "认证失败（Token 错误或未提供）" to """
                • 检查 picoclaw 配置中的 pico.token 是否正确
                • 检查输入框中填写的 Token 是否匹配
                • 若未配置 Token，请在 picoclaw 的 .security.yml 中配置 pico.token
            """.trimIndent()
        }

        if (msg.contains("connection") || msg.contains("refused") || msg.contains("failed") || msg.contains("network")) {
            return "连接失败（网络或地址不可达）" to """
                • 确认手机与 picoclaw 服务器在同一网络
                • 确认地址和端口正确（默认 ws://host:9090）
                • 若服务器仅监听 127.0.0.1，需改为监听 0.0.0.0
            """.trimIndent()
        }

        // 默认
        val brief = when {
            msg.isBlank() -> "连接失败"
            msg.contains("invalid") -> "请求无效"
            msg.contains("timeout") -> "连接超时"
            msg.contains("cors") || msg.contains("origin") -> "来源不允许（CORS）"
            else -> message ?: "连接失败"
        }
        return brief to null
    }
}
