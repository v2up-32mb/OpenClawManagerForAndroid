package com.picoclaw.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.picoclaw.manager.ui.MainViewModel

@Composable
fun SkillManageScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val searchLoading by viewModel.searchLoading.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val skills by viewModel.skills.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Skill 管理",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 已安装的 Skill
        Text(
            text = "已安装的 Skill",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (skills.isEmpty()) {
            Text(
                text = "暂无已安装的 Skill，点击「刷新」获取最新列表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            skills.forEach { skill ->
                val name = skill["name"]?.toString() ?: skill["id"]?.toString() ?: "?"
                val description = skill["description"]?.toString() ?: ""
                SkillCard(
                    name = name,
                    description = description,
                    onUninstall = {
                        viewModel.uninstallSkill(name)
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.refreshSkills() }) { Text("刷新列表") }
        }

        Spacer(Modifier.height(16.dp))

        // 搜索 Skill
        Text(
            text = "搜索并安装 Skill",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "从 Registry 搜索并安装 Skill。安装后需要重启 picoclaw 才能生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var searchQuery by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("关键词，如 search、api、browser") },
                singleLine = true
            )
            Button(
                onClick = { viewModel.searchSkills(searchQuery.trim()) },
                enabled = !searchLoading
            ) { Text("搜索") }
        }

        if (searchLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        searchError?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
        searchResults.forEach { item ->
            val slug = item["slug"]?.toString() ?: item["name"]?.toString() ?: "?"
            val name = item["display_name"]?.toString() ?: item["name"]?.toString() ?: slug
            val description = item["summary"]?.toString() ?: item["description"]?.toString() ?: ""
            SearchResultCard(
                name = name,
                slug = slug,
                description = description,
                onInstall = {
                    viewModel.installSkill(slug)
                }
            )
        }
    }
}

@Composable
private fun SkillCard(
    name: String,
    description: String,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = description.take(150),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onUninstall) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    name: String,
    slug: String,
    description: String,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = slug,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = description.take(150),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onInstall) { Text("安装") }
            }
        }
    }
}
