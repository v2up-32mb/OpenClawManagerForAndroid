package com.picoclaw.manager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.picoclaw.manager.data.pico.PicoConnectionState
import com.picoclaw.manager.ui.MainViewModel

/** 常用模型选项（格式 provider/model，与 picoclaw REST API 兼容） */
private val PRESET_MODELS = listOf(
    "openai/gpt-5.4" to "openai/gpt-5.4",
    "openai/gpt-5-mini" to "openai/gpt-5-mini",
    "google/gemini-3.1-pro-preview" to "google/gemini-3.1-pro-preview",
    "google/gemini-3-flash-preview" to "google/gemini-3-flash-preview",
    "anthropic/claude-opus-4-6" to "anthropic/claude-opus-4-6",
    "anthropic/claude-sonnet-4-6" to "anthropic/claude-sonnet-4-6",
    "deepseek/deepseek-chat" to "deepseek/deepseek-chat",
    "deepseek/deepseek-reasoner" to "deepseek/deepseek-reasoner",
    "openrouter/deepseek/deepseek-chat" to "openrouter/deepseek/deepseek-chat",
    "ollama/llama3.3" to "ollama/llama3.3",
)

@Composable
fun ModelConfigScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val defaultModel by viewModel.defaultModel.collectAsState()
    val configSetError by viewModel.configSetError.collectAsState()
    val saveSuccessToast by viewModel.saveSuccessToast.collectAsState()
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }
    var selectedLabel by remember(defaultModel) {
        mutableStateOf(
            PRESET_MODELS.find { it.second == defaultModel }?.first ?: defaultModel ?: ""
        )
    }
    val selectedRef = PRESET_MODELS.find { it.first == selectedLabel }?.second ?: defaultModel ?: ""

    LaunchedEffect(connectionState) {
        if (connectionState is PicoConnectionState.Connected) viewModel.refreshModels()
    }
    LaunchedEffect(defaultModel) {
        selectedLabel = PRESET_MODELS.find { it.second == defaultModel }?.first ?: defaultModel ?: ""
    }
    LaunchedEffect(saveSuccessToast) {
        saveSuccessToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearSaveSuccessToast()
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
            text = "模型配置",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (connectionState !is PicoConnectionState.Connected) {
            Text(
                text = "请先连接 picoclaw 后再查看或修改模型。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 选择模型
            Text(
                text = "选择默认模型",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = selectedLabel.ifEmpty { "请选择模型" },
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { Text("▼", style = MaterialTheme.typography.bodySmall) }
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { expanded = true }
                    )
                }
                Button(
                    onClick = {
                        if (selectedRef.isNotBlank()) viewModel.setDefaultModel(selectedRef)
                    },
                    enabled = selectedRef.isNotBlank()
                ) {
                    Text("保存")
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                PRESET_MODELS.forEach { (label, ref) ->
                    DropdownMenuItem(
                        text = { Text("$label  ($ref)") },
                        onClick = {
                            selectedLabel = label
                            expanded = false
                        }
                    )
                }
            }

            configSetError?.let { err ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::clearConfigSetError) { Text("清除") }
                }
            }

            Text(
                text = "切换默认模型后点「保存」即可。picoclaw 的 API Key 通过 Dashboard 配置页面或环境变量管理，不在本应用中设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
