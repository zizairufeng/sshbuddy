package com.czk.sshai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.czk.sshai.ui.GlobalSettingsScreen
import com.czk.sshai.ui.HostEditorScreen
import com.czk.sshai.ui.HostListScreen
import com.czk.sshai.ui.SshaiTheme
import com.czk.sshai.ui.TerminalScreen

class MainActivity : ComponentActivity() {

    private val vm: TerminalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SshaiTheme {
                App(vm)
            }
        }
    }
}

@Composable
private fun App(vm: TerminalViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // 通知权限（Android 13+ 需要，否则常驻通知不显示；不授予也能用，只是没有保活提示）
    val notiPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notiPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 把状态桥给前台服务：通知内容 + 通知上的「断开」按钮。
    // 只绑定一次，provider 每次读 vm.state.value，避免每次页签变化都重绑。
    DisposableEffect(Unit) {
        SessionBus.bind(
            tabs = { vm.state.value.tabs },
            hosts = { vm.state.value.hosts },
            onDisconnectAll = {
                vm.state.value.tabs.indices.reversed().forEach { vm.disconnect(it) }
            },
        )
        onDispose { SessionBus.unbind() }
    }

    // 有会话在线就拉起前台服务，全部断开就收掉。
    // 退避等待重连期间也算在线，否则服务会在等待的几十秒里被停掉。
    val anyLive = state.tabs.any { it.connected || it.connecting || it.reconnectingAt != null }
    LaunchedEffect(anyLive) {
        val i = Intent(ctx, SessionService::class.java)
        if (anyLive) {
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        } else {
            runCatching { ctx.stopService(i) }
        }
    }

    LaunchedEffect(state.toast) {
        state.toast?.let { msg ->
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showSettings || state.screen != Screen.Hosts) {
        when {
            showSettings -> showSettings = false
            state.screen == Screen.Editor -> vm.setScreen(Screen.Hosts)
            state.screen == Screen.Terminal -> vm.backToHosts()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            showSettings -> GlobalSettingsScreen(
                prefs = state.prefs,
                onEdit = vm::savePrefs,
                onForgetKnownHosts = vm::forgetKnownHosts,
                onSave = {
                    showSettings = false
                    vm.toast("设置已保存")
                },
                onBack = { showSettings = false },
            )

            state.screen == Screen.Editor && state.editing != null -> {
                val isNew = state.hosts.none { it.id == state.editing!!.id }
                HostEditorScreen(
                    cfg = state.editing!!,
                    isNew = isNew,
                    onEdit = vm::editDraft,
                    onSave = { vm.saveHost(andConnect = false) },
                    onSaveAndConnect = {
                        val saved = vm.saveHost(andConnect = true)
                        when {
                            saved?.isRdp == true ->
                                ctx.startActivity(RemoteActivity.intent(ctx, saved.id))
                        }
                    },
                    onBack = { vm.setScreen(Screen.Hosts) },
                )
            }

            state.screen == Screen.Terminal -> TerminalScreen(
                state = state,
                onConnect = { vm.connect() },
                onDisconnect = { vm.disconnect() },
                onSendRaw = vm::sendRaw,
                onOpenSettings = { showSettings = true },
                onAskAi = { vm.askAi(it) },
                onClearAi = vm::clearAi,
                onClearTerminal = { vm.clearTerminal() },
                onBack = { vm.backToHosts() },
                onSelectTab = vm::selectTab,
                onCloseTab = vm::closeTab,
                onFontSize = vm::setFontSize,
                onToggleKeyBar = vm::toggleKeyBar,
                onCancelReconnect = vm::cancelReconnect,
            )

            else -> HostListScreen(
                hosts = state.hosts,
                tabs = state.tabs,
                onOpen = { id ->
                    val h = state.hosts.find { it.id == id }
                    when {
                        h != null && h.isRdp ->
                            ctx.startActivity(RemoteActivity.intent(ctx, id))
                        else -> vm.openHost(id)
                    }
                },
                onEdit = vm::editHost,
                onDelete = { id -> confirmDelete = id },
                onDuplicate = vm::duplicateHost,
                onNew = vm::newHost,
                onSettings = { showSettings = true },
            )
        }
    }

    confirmDelete?.let { id ->
        val h = state.hosts.find { it.id == id }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除主机") },
            text = { Text("确定删除「${h?.label ?: ""}」？已开的会话会一起关掉。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteHost(id)
                    confirmDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}
