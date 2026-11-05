package com.czk.sshai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.czk.sshai.AiClient
import com.czk.sshai.AiMsg
import com.czk.sshai.KeyPad
import com.czk.sshai.Tab
import com.czk.sshai.UiState

/** 单次复制到剪贴板的最大字符数，避免 Binder TransactionTooLargeException。 */
private const val MAX_COPY_CHARS = 200_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: UiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
    onOpenSettings: () -> Unit,
    onAskAi: (String?) -> Unit,
    onClearAi: () -> Unit,
    onClearTerminal: () -> Unit,
    onBack: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onFontSize: (Float) -> Unit,
    onToggleKeyBar: () -> Unit,
    onCancelReconnect: () -> Unit,
) {
    var aiOpen by remember { mutableStateOf(false) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var keyboardSignal by remember { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val tab = state.tab
    val host = state.host
    val status = when {
        tab?.reconnectingAt != null -> "断线，${tab.reconnectingAt} 次重连中…"
        tab?.connecting == true -> "连接中…"
        tab?.connected == true -> "已连接 ${host?.user}@${host?.host}"
        else -> "未连接"
    }

    fun insertCommand(cmd: String) {
        // 不补回车：先落到远端 shell 的输入行，用户确认后再自己按 Enter。
        onSendRaw(cmd.toByteArray(Charsets.UTF_8))
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(host?.host ?: "SSHBuddy", fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                            if (state.tabs.size > 1) {
                                Text("  (${state.activeTab + 1}/${state.tabs.size})", fontSize = 11.sp, color = Color(0xFF5A6B7B))
                            }
                        }
                        Text(
                            status,
                            fontSize = 10.5.sp,
                            color = when {
                                tab?.connected == true -> AccentColor
                                tab?.reconnectingAt != null -> Color(0xFFE0A030)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "主机列表", tint = TermTextColor)
                    }
                },
                actions = {
                    if (tab?.connecting == true) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color = AccentColor,
                        )
                    } else {
                        IconButton(onClick = { if (tab?.connected == true) onDisconnect() else onConnect() }) {
                            Icon(
                                Icons.Filled.PowerSettingsNew,
                                contentDescription = if (tab?.connected == true) "断开" else "连接",
                                tint = if (tab?.connected == true) AccentColor else TermTextColor,
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置", tint = TermTextColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor, titleContentColor = TermTextColor),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding(),
        ) {
            // 多会话页签（开过 2 个以上主机才显示）
            if (state.tabs.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SurfaceColor)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.tabs.forEachIndexed { i, t ->
                        TabChip(
                            tab = t,
                            active = i == state.activeTab,
                            onClick = { onSelectTab(i) },
                            onClose = { onCloseTab(i) },
                        )
                    }
                }
            }

            if (tab?.reconnectingAt != null) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF2A2110)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("连接断了，正在自动重连…", color = Color(0xFFE0A030), fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelReconnect) { Text("停止", fontSize = 11.5.sp, color = Color(0xFFE0A030)) }
                }
            }

            // 真正的终端：Termux terminal-view，原生渲染 ANSI/UTF-8/回看，长按可选中复制。
            TerminalPane(
                session = tab?.session,
                fontSize = state.prefs.fontSize,
                ctrlArmed = ctrlArmed,
                altArmed = altArmed,
                modifier = Modifier.weight(1f),
                onModifiersConsumed = {
                    ctrlArmed = false
                    altArmed = false
                },
                onFontSizeDelta = { delta -> onFontSize(state.prefs.fontSize + delta) },
                keyboardSignal = keyboardSignal,
            )

            HorizontalDivider(color = Color(0xFF1E2A36))

            // 特殊按键条：Ctrl / Alt 是粘滞的，点亮后下一次从软键盘输入的字符按组合键发送。
            if (state.prefs.keyBarVisible) {
                KeyBar(
                    enabled = tab?.connected == true,
                    ctrlArmed = ctrlArmed,
                    altArmed = altArmed,
                    onCtrl = { ctrlArmed = !ctrlArmed; altArmed = false },
                    onAlt = { altArmed = !altArmed; ctrlArmed = false },
                    onTap = { onSendRaw(it.bytes) },
                )
            }

            // 工具行：AI / 软键盘 / 按键条 / 复制全部 / 字号
            Row(
                Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { aiOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 助手", tint = AccentColor, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { keyboardSignal++ }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "软键盘", tint = TermTextColor, modifier = Modifier.size(20.dp))
                }
                TextButton(onClick = onToggleKeyBar) {
                    Text(
                        "按键条",
                        fontSize = 11.sp,
                        color = if (state.prefs.keyBarVisible) AccentColor else Color(0xFF5A6B7B),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        val full = tab?.session?.screenText().orEmpty()
                        if (full.isEmpty()) {
                            android.widget.Toast.makeText(ctx, "终端还没有内容", android.widget.Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        // Binder 事务上限约 1MB，整块回看直接塞剪贴板会抛
                        // TransactionTooLargeException 把 App 崩掉，这里只取最近的部分。
                        val copied = if (full.length > MAX_COPY_CHARS) full.takeLast(MAX_COPY_CHARS) else full
                        val ok = runCatching { clipboard.setText(AnnotatedString(copied)) }.isSuccess
                        val msg = when {
                            !ok -> "复制失败"
                            copied.length < full.length -> "已复制最近 ${copied.length} 个字符"
                            else -> "已复制全部"
                        }
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制全部", tint = Color(0xFF5A6B7B), modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onClearTerminal, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "清屏", tint = Color(0xFF5A6B7B), modifier = Modifier.size(18.dp))
                }
                FontSizeButton(size = state.prefs.fontSize, onChange = onFontSize)
            }
        }
    }

    if (aiOpen) {
        ModalBottomSheet(
            onDismissRequest = { aiOpen = false },
            sheetState = sheetState,
            containerColor = SurfaceColor,
            dragHandle = null,
        ) {
            AiPanel(
                ai = state.ai,
                busy = state.aiBusy,
                aiReady = state.prefs.aiKey.isNotBlank(),
                onAsk = onAskAi,
                onClear = onClearAi,
                onUseCommand = { cmd ->
                    insertCommand(cmd)
                    aiOpen = false
                },
                onClose = { aiOpen = false },
            )
        }
    }
}

@Composable
private fun TabChip(tab: Tab, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .background(if (active) Color(0xFF1D2A36) else Color(0xFF141B22), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(6.dp).background(
                if (tab.connected) AccentColor else Color(0xFF5A6B7B),
                RoundedCornerShape(3.dp),
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            tab.title,
            fontSize = 11.5.sp,
            color = if (active) TermTextColor else Color(0xFF8A97A6),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 2.dp),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "关闭页签", tint = Color(0xFF5A6B7B), modifier = Modifier.size(13.dp))
        }
    }
}

/** 特殊按键条：Ctrl / Alt 粘滞 + 常用控制键，可横向滑动 */
@Composable
private fun KeyBar(
    enabled: Boolean,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onTap: (KeyPad.Tap) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1319))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        KeyChip("Ctrl", armed = ctrlArmed, enabled = enabled, onClick = onCtrl)
        KeyChip("Alt", armed = altArmed, enabled = enabled, onClick = onAlt)
        KeyPad.plain.forEach { tap ->
            KeyChip(
                label = tap.label,
                armed = false,
                enabled = enabled,
                onClick = { onTap(tap) },
            )
        }
    }
}

@Composable
private fun KeyChip(label: String, armed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (armed) AccentColor else Color(0xFF1B2530),
                RoundedCornerShape(6.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = when {
                armed -> Color(0xFF04140F)
                enabled -> TermTextColor
                else -> Color(0xFF3E4C5A)
            },
        )
    }
}

@Composable
private fun FontSizeButton(size: Float, onChange: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = Color(0xFF5A6B7B), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("${size.toInt()}", fontSize = 11.sp, color = Color(0xFF5A6B7B))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(10f, 11f, 12.5f, 14f, 16f, 18f, 20f).forEach { s ->
                DropdownMenuItem(
                    text = { Text("${s.toInt()} sp", fontSize = 13.sp) },
                    onClick = { onChange(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun AiPanel(
    ai: List<AiMsg>,
    busy: Boolean,
    aiReady: Boolean,
    onAsk: (String?) -> Unit,
    onClear: () -> Unit,
    onUseCommand: (String) -> Unit,
    onClose: () -> Unit,
) {
    var ask by remember { mutableStateOf("") }
    val lastCommands = ai.lastOrNull { it.role == "assistant" }
        ?.let { AiClient.extractCommands(it.text) }
        .orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .imePadding()
            .padding(horizontal = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AccentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("AI 助手", color = TermTextColor, fontSize = 15.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "清空对话", tint = Color(0xFF4A5866), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color(0xFF4A5866), modifier = Modifier.size(18.dp))
            }
        }

        if (!aiReady) {
            Text(
                "还没配 AI。返回主机列表 → 右上角设置，填 Base URL 与 API Key（DeepSeek / 通义 / Kimi / 本地 Ollama 都行）。",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SuggestionChip(
                onClick = { onAsk("看上面的终端输出，现在是什么情况？下一步该敲什么命令？只给必要的一条命令和一句话解释。") },
                label = { Text("下一步", fontSize = 11.sp) },
            )
            SuggestionChip(
                onClick = { onAsk("终端里有没有报错？逐条解释错误原因，并给出修复命令。") },
                label = { Text("解释报错", fontSize = 11.sp) },
            )
            SuggestionChip(
                onClick = { onAsk("帮我排查这台机器的磁盘、内存、负载情况，给出要敲的检查命令。") },
                label = { Text("查健康", fontSize = 11.sp) },
            )
        }

        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = AccentColor,
                trackColor = Color(0xFF1B2530),
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
        ) {
            if (ai.isEmpty()) {
                item {
                    Text(
                        "让 AI 看一眼当前终端 —— 它会解释情况，并把能直接用的命令抽成按钮。\n" +
                            "命令只会插入到 shell 输入行，不会自动执行；发送前请自己确认。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            items(ai) { msg ->
                val mine = msg.role == "user"
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth(if (mine) 0.86f else 0.98f)
                            .background(
                                if (mine) Color(0xFF17323A) else Color(0xFF161E27),
                                RoundedCornerShape(10.dp),
                            )
                            .padding(10.dp),
                    ) {
                        Text(
                            msg.text,
                            color = TermTextColor,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            fontFamily = if (msg.role == "assistant") FontFamily.Monospace else FontFamily.Default,
                        )
                    }
                }
            }
        }

        if (lastCommands.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text("点一下插入终端输入行（不会自动回车）：", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                lastCommands.take(6).forEach { cmd ->
                    AssistChip(
                        onClick = { onUseCommand(cmd) },
                        label = {
                            Text(
                                cmd,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(labelColor = BlueColor),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = ask,
                onValueChange = { ask = it },
                placeholder = { Text("问点什么…", fontSize = 13.sp) },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (ask.isNotBlank()) {
                        onAsk(ask)
                        ask = ""
                    }
                },
                enabled = !busy && aiReady,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = AccentColor)
            }
        }
    }
}
