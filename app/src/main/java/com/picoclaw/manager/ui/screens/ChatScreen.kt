package com.picoclaw.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.picoclaw.manager.data.pico.ChatMessage
import com.picoclaw.manager.data.pico.PicoConnectionState
import com.picoclaw.manager.ui.MainViewModel

@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val chatSendError by viewModel.chatSendError.collectAsState()
    val agentRunInProgress by viewModel.agentRunInProgress.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val maxItems = 64
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isEmpty()) return@LaunchedEffect
        try {
            scrollState.animateScrollTo(scrollState.maxValue)
        } catch (_: Exception) { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (connectionState !is PicoConnectionState.Connected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "请先连接 picoclaw 后再使用对话",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            if (chatMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无消息，在下方输入发送",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val displayList = if (chatMessages.size <= maxItems) chatMessages else chatMessages.takeLast(maxItems)
                val showTruncateHint = chatMessages.size > maxItems
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showTruncateHint) {
                        Text(
                            text = "仅显示最近 $maxItems 条，共 ${chatMessages.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    displayList.forEach { msg ->
                        ChatBubble(message = msg)
                    }
                }
            }
            if (agentRunInProgress) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = "AI 正在回复…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            chatSendError?.let { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::clearChatSendError) { Text("清除") }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…") },
                    singleLine = false,
                    maxLines = 4
                )
                Spacer(Modifier.padding(8.dp))
                TextButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotBlank()) {
                            viewModel.sendChatMessage(text)
                            inputText = ""
                        }
                    }
                ) { Text("发送") }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role.equals("user", ignoreCase = true)
    val isThought = message.kind == "thought"
    val isToolCall = message.kind == "tool_calls"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .background(
                    color = when {
                        isUser -> MaterialTheme.colorScheme.primaryContainer
                        isThought -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        isToolCall -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // 角色标签
            val label = when {
                isUser -> "我"
                isThought -> "💭 思考"
                isToolCall -> "🔧 工具调用"
                else -> "AI"
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            // 内容
            if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 附件
            message.attachments?.forEach { attachment ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "📎 ${attachment.filename ?: attachment.url ?: "附件"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
