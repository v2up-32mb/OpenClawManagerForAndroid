package com.picoclaw.manager.ui

/**
 * PicoClaw 连接常见问题文案。
 */
object FaqContent {

    const val TITLE = "常见问题"

    val sections: List<Pair<String, String>> = listOf(
        "picoclaw 是什么？" to """
            picoclaw 是一个用 Go 编写的超轻量 AI 个人助手。
            它支持 Web UI、飞书、Telegram 等多种通道，通过 pico 协议（WebSocket JSON）与客户端通信。
            本应用（PicoClawManagerForAndroid）是一个 Android 客户端，支持同时连接多个 picoclaw 实例。
        """.trimIndent(),
        "如何配置 picoclaw 的 Pico Channel？" to """
            在 picoclaw 的配置文件中设置 Pico Channel：
            
            channel_list:
              pico:
                enabled: true
                settings:
                  token: "your-secret-token"  # 可选，安全认证
                  max_connections: 100
                  ping_interval: 30
            
            然后启动 picoclaw，WebSocket 服务默认在 9090 端口。
        """.trimIndent(),
        "如何连接？" to """
            在主页填写：
            • WebSocket 地址: ws://你的picoclaw地址:9090
            • Token: 与 picoclaw 配置中的 pico.token 一致（可选）
            
            填写后点击「连接」即可。
            
            注：picoclaw 默认监听端口为 9090。
        """.trimIndent(),
        "连接被拒绝或超时？" to """
            • 确认手机和 picoclaw 服务器在同一网络
            • 确认 picoclaw 已启动且 Pico Channel 已启用
            • 确认地址和端口正确（默认 ws://host:9090）
            • 确认防火墙未阻止连接
            • 若 picoclaw 仅监听 localhost，需改为监听 0.0.0.0
        """.trimIndent(),
        "如何管理模型？" to """
            picoclaw 的模型配置通过 Web UI 的 REST API 管理：
            • GET/POST/PUT/DELETE /api/models/*
            在「模型配置」Tab 中可选择默认模型并保存。
            
            API Key 需要在 picoclaw 的 Dashboard Web 界面或配置文件中设置。
        """.trimIndent(),
        "如何安装 Skill？" to """
            picoclaw 支持自定义 Skill，安装方式：
            1. 通过本应用的「Skill」Tab 搜索 Registry 中的 Skill
            2. 点击安装后，picoclaw 会自动下载并安装
            3. 安装后可能需要重启 picoclaw 才能生效
            
            也可以通过 picoclaw 的 Web UI 管理 Skill。
        """.trimIndent(),
        "本应用与 OpenClawManagerForAndroid 的区别？" to """
            PicoClawManagerForAndroid 是 OpenClawManagerForAndroid 的改造版本：
            • 协议层: OpenClaw Gateway 协议 → pico 协议（简单 JSON 帧）
            • 认证: Ed25519 挑战-响应 → Bearer Token
            • 管理 API: Gateway RPC → picoclaw REST API
            • 适用于连接 picoclaw 实例，而非 OpenClaw Gateway
        """.trimIndent()
    )
}
