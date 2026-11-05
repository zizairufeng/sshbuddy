package com.czk.sshai.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.czk.sshai.AppPrefs

/** 全局设置：AI 接口 + 行为开关 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(
    prefs: AppPrefs,
    onEdit: (AppPrefs) -> Unit,
    onForgetKnownHosts: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgColor,
                    titleContentColor = TermTextColor,
                    navigationIconContentColor = TermTextColor,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("AI 助手（任意 OpenAI 兼容接口）")

            OutlinedTextField(
                value = prefs.aiBaseUrl,
                onValueChange = { onEdit(prefs.copy(aiBaseUrl = it.trim())) },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prefs.aiModel,
                onValueChange = { onEdit(prefs.copy(aiModel = it.trim())) },
                label = { Text("模型名") },
                placeholder = { Text("deepseek-chat") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prefs.aiKey,
                onValueChange = { onEdit(prefs.copy(aiKey = it.trim())) },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "DeepSeek：api.deepseek.com/v1 + deepseek-chat；通义：dashscope.aliyuncs.com/compatible-mode/v1；" +
                    "Kimi：api.moonshot.cn/v1；本机 Ollama：http://你的IP:11434/v1（Key 随便填）。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(6.dp))
            SectionTitle("会话")

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("断线自动重连", color = TermTextColor, fontSize = 14.sp)
                    Text(
                        "2s / 4s / 8s / 16s / 30s 退避重试（每次断线最多 5 次，连上后重新计数）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = prefs.autoReconnect,
                    onCheckedChange = { onEdit(prefs.copy(autoReconnect = it)) },
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示特殊按键条", color = TermTextColor, fontSize = 14.sp)
                    Text(
                        "Ctrl / Esc / Tab / 方向键，vim 与 htop 必需",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = prefs.keyBarVisible,
                    onCheckedChange = { onEdit(prefs.copy(keyBarVisible = it)) },
                )
            }

            Spacer(Modifier.height(6.dp))
            SectionTitle("说明")

            Text(
                "· 连接时后台会挂一条常驻通知，防止 App 进后台被系统冻结导致断线，断开后自动消失。\n" +
                    "· 主机密码、私钥口令、RDP 密码和 AI Key 都使用 Android Keystore 的 AES-256-GCM 加密后再落盘，密钥不随应用数据导出。\n" +
                    "· SSH 主机指纹首次连接时记录（TOFU），之后指纹变化会直接拒绝连接，防止中间人接管。\n" +
                    "· RDP 为了兼容自签证书目前会自动信任，只建议连接可信内网。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                lineHeight = 18.sp,
            )

            OutlinedButton(
                onClick = onForgetKnownHosts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清除已信任的 SSH 主机指纹", fontSize = 13.sp)
            }

            Text(
                "终端内核：Termux terminal-emulator / terminal-view（Apache-2.0）。" +
                    "完整许可证见 APK 内 assets/licenses/Apache-2.0.txt。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
            )

            Spacer(Modifier.height(8.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("保存")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
