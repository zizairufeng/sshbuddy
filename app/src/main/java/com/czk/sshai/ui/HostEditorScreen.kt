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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.czk.sshai.SshConfig

/** 单台主机的编辑页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditorScreen(
    cfg: SshConfig,
    isNew: Boolean,
    onEdit: (SshConfig) -> Unit,
    onSave: () -> Unit,
    onSaveAndConnect: () -> Unit,
    onBack: () -> Unit,
) {
    // 端口用一份本地草稿：直接绑到 cfg.port 时，删空输入框会被默认值立刻顶回来，
    // 用户没法先清空再输入新端口。
    var portText by remember(cfg.id, cfg.isRdp) {
        mutableStateOf(
            if (cfg.isRdp) cfg.effectiveRdpPort.toString() else cfg.port.toString(),
        )
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新主机" else "编辑主机", fontSize = 17.sp) },
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
            SectionTitle("连接方式")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !cfg.isRdp,
                    onClick = { onEdit(cfg.copy(protocol = "ssh")) },
                    label = { Text("SSH 终端") },
                )
                FilterChip(
                    selected = cfg.isRdp,
                    onClick = { onEdit(cfg.copy(protocol = "rdp")) },
                    label = { Text("RDP 远程桌面") },
                )
            }

            SectionTitle("服务器")

            OutlinedTextField(
                value = cfg.name,
                onValueChange = { onEdit(cfg.copy(name = it)) },
                label = { Text("别名（随便起，方便记）") },
                placeholder = { Text("家里的主机 / 云服务器") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = cfg.host,
                onValueChange = { onEdit(cfg.copy(host = it.trim())) },
                label = { Text("主机 / IP") },
                placeholder = { Text("192.168.1.10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = cfg.user,
                    onValueChange = { onEdit(cfg.copy(user = it.trim())) },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { v ->
                        val digits = v.filter { it.isDigit() }.take(5)
                        portText = digits
                        if (digits.isEmpty()) {
                            // SSH 清空后校验会提示端口不对；RDP 清空表示用默认端口。
                            if (cfg.isRdp) onEdit(cfg.copy(rdpPort = 0))
                            else onEdit(cfg.copy(port = 0))
                        } else {
                            digits.toIntOrNull()?.let { n ->
                                if (cfg.isRdp) onEdit(cfg.copy(rdpPort = n))
                                else onEdit(cfg.copy(port = n))
                            }
                        }
                    },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.55f),
                )
            }

            SectionTitle("认证")

            if (!cfg.isRdp) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("用私钥登录", color = TermTextColor, fontSize = 14.sp)
                    Switch(checked = cfg.useKey, onCheckedChange = { onEdit(cfg.copy(useKey = it)) })
                }
            }

            if (cfg.useKey && !cfg.isRdp) {
                OutlinedTextField(
                    value = cfg.keyPem,
                    onValueChange = { onEdit(cfg.copy(keyPem = it)) },
                    label = { Text("私钥内容（-----BEGIN OPENSSH PRIVATE KEY-----）") },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cfg.passphrase,
                    onValueChange = { onEdit(cfg.copy(passphrase = it)) },
                    label = { Text("私钥口令（没有就留空）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "支持 ed25519 / rsa-sha2，直接粘贴整段私钥即可。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            } else {
                OutlinedTextField(
                    value = cfg.password,
                    onValueChange = { onEdit(cfg.copy(password = it)) },
                    label = { Text(if (cfg.isRdp) "Windows 密码" else "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (cfg.isRdp) {
                OutlinedTextField(
                    value = cfg.domain.orEmpty(),
                    onValueChange = { onEdit(cfg.copy(domain = it.trim())) },
                    label = { Text("域 / Domain（本地账户留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("安全层", color = TermTextColor, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "auto" to "自动",
                        "nla" to "NLA",
                        "tls" to "TLS",
                        "rdp" to "RDP",
                    ).forEach { (key, label) ->
                        val cur = cfg.rdpSecurity ?: "auto"
                        FilterChip(
                            selected = cur == key,
                            onClick = { onEdit(cfg.copy(rdpSecurity = key)) },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                Text(
                    "Windows 10/11 默认要求 NLA；xrdp 等 Linux 端通常选自动即可。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            Text(
                "主机配置保存在本机私有目录，并用 Android Keystore 的 AES-256-GCM 加密，不联网。AI 的 Key 是全局的，到主机列表右上角设置里改。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("仅保存")
                }
                Button(onClick = onSaveAndConnect, modifier = Modifier.weight(1f)) {
                    Text("保存并连接")
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("取消")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        color = AccentColor,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 6.dp),
    )
}
