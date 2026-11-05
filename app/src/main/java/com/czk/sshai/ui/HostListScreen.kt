package com.czk.sshai.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.czk.sshai.SshConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    hosts: List<SshConfig>,
    tabs: List<com.czk.sshai.Tab>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val filtered = if (q.isBlank()) hosts else hosts.filter { it.searchKey.contains(q.trim().lowercase()) }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("主机", fontSize = 18.sp)
                        Text(
                            "${hosts.size} 台已保存" + if (tabs.any { it.connected }) " · ${tabs.count { it.connected }} 个会话在线" else "",
                            fontSize = 11.sp,
                            color = if (tabs.any { it.connected }) AccentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "全局设置", tint = TermTextColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor, titleContentColor = TermTextColor),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                containerColor = AccentColor,
                contentColor = Color(0xFF04140F),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新主机", fontSize = 14.sp) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                placeholder = { Text("搜索主机 / IP / 用户…", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (hosts.isEmpty()) "还没有主机" else "没有匹配的主机",
                            color = TermTextColor,
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (hosts.isEmpty()) "点右下角「新主机」加第一台，之后一点就进终端。" else "换个关键字试试",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 4.dp, 14.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { h ->
                        HostCard(
                            host = h,
                            live = tabs.any { it.hostId == h.id && it.connected },
                            onOpen = { onOpen(h.id) },
                            onEdit = { onEdit(h.id) },
                            onDelete = { onDelete(h.id) },
                            onDuplicate = { onDuplicate(h.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: SshConfig,
    live: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Dns,
                contentDescription = null,
                tint = if (live) AccentColor else Color(0xFF5A6B7B),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(host.label, color = TermTextColor, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    if (live) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).background(AccentColor, RoundedCornerShape(4.dp)))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (host.useKey) Icons.Filled.VpnKey else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(host.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color(0xFF5A6B7B), modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("复制一份") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
