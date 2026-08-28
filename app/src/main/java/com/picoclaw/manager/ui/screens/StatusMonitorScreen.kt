package com.picoclaw.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.picoclaw.manager.data.pico.PicoConnectionState
import com.picoclaw.manager.ui.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun StatusMonitorScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val gatewayStatus by viewModel.gatewayStatus.collectAsState()
    val systemVersion by viewModel.systemVersion.collectAsState()

    // 轮询状态
    LaunchedEffect(connectionState) {
        if (connectionState is PicoConnectionState.Connected) {
            while (true) {
                viewModel.refreshGatewayStatus()
                delay(5000)  // 每 5 秒刷新一次
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "状态监控",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Connection status card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "连接状态",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (connectionState) {
                        is PicoConnectionState.Connecting -> CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                        else -> Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    when (connectionState) {
                                        is PicoConnectionState.Connected -> MaterialTheme.colorScheme.primary
                                        is PicoConnectionState.Error -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                        )
                    }
                    Text(
                        text = when (connectionState) {
                            is PicoConnectionState.Connected -> "已连接"
                            is PicoConnectionState.Disconnected -> "未连接"
                            is PicoConnectionState.Connecting -> "连接中…"
                            is PicoConnectionState.Disconnecting -> "断开中…"
                            is PicoConnectionState.Error -> "错误: ${(connectionState as PicoConnectionState.Error).message}"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Gateway Status card (from REST API)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "picoclaw 状态",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (gatewayStatus.isEmpty()) {
                    Text(
                        text = "暂无数据（请先连接）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 优先显示关键字段
                    val priorityKeys = listOf("gateway_status", "pid", "gateway_version", "boot_default_model", "config_default_model", "gateway_restart_required")
                    priorityKeys.forEach { key ->
                        gatewayStatus[key]?.let { value ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = value.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    // 其余字段
                    val otherKeys = gatewayStatus.keys - priorityKeys.toSet()
                    otherKeys.take(8).forEach { key ->
                        val value = gatewayStatus[key]
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = value?.toString()?.take(30) ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }

        // Version card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "版本信息",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (systemVersion.isEmpty()) {
                    Text(
                        text = "暂无数据（请先连接）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    systemVersion.entries.take(6).forEach { (k, v) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = k,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = v?.toString() ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
