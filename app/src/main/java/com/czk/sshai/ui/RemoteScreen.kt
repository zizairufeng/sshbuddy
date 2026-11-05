package com.czk.sshai.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.czk.sshai.remote.RemoteUiState

/**
 * **唯一的**远端会话主界面（v1.8.0 起只服务 RDP）。
 *
 * 布局故意分成上下两块：**上面是远端画面**（高度按剩余空间自适应），
 * **下面是触摸板 + 常用按键**。手机屏幕小，手指直接点远端桌面里的按钮
 * 精度很差（1920 宽的桌面缩到 400dp，一个菜单项可能只有 3dp 高），
 * 所以把指针操作收进一块相对移动的触摸板，配合 Esc/Tab/方向键比戳屏幕可靠得多。
 *
 * 控制台可以收起（[deckVisible] = false），此时画面恢复铺满，只是少了下半部分。
 */
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    title: String,
    toolbarVisible: Boolean,
    inputMode: RemoteInputMode,
    numpadVisible: Boolean,
    deckVisible: Boolean,
    /** 控制台占屏幕高度的比例（0 = 用默认值）。拖动分隔条可改，按屏幕方向给不同默认值 */
    deckRatio: Float,
    onDeckRatioChange: (Float) -> Unit,
    /** 触摸板指针速度档位文案（慢/标准/快），显示在控制台底栏 */
    pointerSpeedLabel: String,
    onCycleSpeed: () -> Unit,
    /** 打开远端应用启动器（GNOME 活动视图 / Windows 开始菜单） */
    onShowApps: () -> Unit,
    /** 当前按住不放的修饰键（Android keycode），用于高亮 Ctrl/Alt/Shift/Win */
    modifiers: Set<Int>,
    onSurfaceReady: (RemoteSurfaceView) -> Unit,
    onToggleToolbar: () -> Unit,
    onToggleInputMode: () -> Unit,
    onKeyboard: () -> Unit,
    onToggleNumpad: () -> Unit,
    onToggleDeck: () -> Unit,
    onNumpadKey: (Int) -> Unit,
    onToggleModifier: (Int) -> Unit,
    onKeyTap: (Int) -> Unit,
    onMovePointer: (Float, Float) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onMiddleClick: () -> Unit,
    onScroll: (Int, Boolean) -> Unit,
    onHoldLeft: () -> Unit,
    onReleaseLeft: () -> Unit,
    onCtrlAltDel: () -> Unit,
    onPasteClipboard: () -> Unit,
    onResetZoom: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    var helpOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 刘海/挖孔：远端桌面是全屏画面，不能让摄像头开孔压住远端内容。
            // 状态栏在 Activity 里已经隐藏，所以这里只需要处理挖孔。
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        // 画面 : 控制台 = (1 - ratio) : ratio。
        // 用比例而不是固定 dp：竖屏 6.x 寸手机上固定 220dp 控制台刚好，
        // 到了横屏（可用高度只有 360dp 左右）同样的数值就把画面压成一条缝了。
        val compact = maxHeight < 520.dp
        val minRatio = if (compact) 0.56f else 0.30f
        val maxRatio = if (compact) 0.76f else 0.62f
        val defaultRatio = if (compact) 0.58f else 0.36f
        val totalPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)

        val effective = (if (deckRatio > 0.01f) deckRatio else defaultRatio)
            .coerceIn(minRatio, maxRatio)
        // 拖动时直接改布局（视图尺寸变化只影响本地适应比例，不会向远端发改分辨率请求），
        // 松手才回调落盘，避免拖动过程中反复写 SharedPreferences。
        var liveRatio by remember(deckRatio, compact) { mutableStateOf(effective) }
        var dragging by remember { mutableStateOf(false) }

        // 输入法弹起时把控制台整块让出去：画面独占键盘以上的全部高度，
        // 键盘永远不会压住远端桌面（画面按比例缩小，内容仍然完整可见）。
        // 用 insets 高度判断而不是 WindowInsets.isImeVisible —— 后者是实验 API，
        // 升级 compose 时随时可能改签名。
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

        Column(Modifier.fillMaxSize().imePadding()) {
            // -------------------------------------------- 工具栏（自己占一行，不压画面）
            // 以前它浮在画面顶部，正好盖住远端窗口的标题栏和菜单 —— 远程桌面最上面
            // 往往就是标题栏/关闭按钮，所以工具栏必须占位，不能叠在画面上。
            if (toolbarVisible) {
                RemoteToolbar(
                    title = title,
                    inputMode = inputMode,
                    numpadVisible = numpadVisible,
                    deckVisible = deckVisible,
                    onToggleInputMode = onToggleInputMode,
                    onKeyboard = onKeyboard,
                    onToggleNumpad = onToggleNumpad,
                    onResetZoom = onResetZoom,
                    onToggleDeck = onToggleDeck,
                    onResetRatio = { onDeckRatioChange(0f) },
                    onShowHelp = { helpOpen = true },
                    onRightClick = onRightClick,
                    onCtrlAltDel = onCtrlAltDel,
                    onPasteClipboard = onPasteClipboard,
                    onToggleToolbar = onToggleToolbar,
                    onDisconnect = onDisconnect,
                )
            }

            // -------------------------------------------- 画面区（吃掉控制台之外的空间）
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        RemoteSurfaceView(ctx).also(onSurfaceReady)
                    },
                )

            // 连接状态遮罩只盖画面区，不盖控制台：断线重连时仍然可以翻看错误信息
            when (val s = state) {
                is RemoteUiState.Connecting -> Overlay {
                    CircularProgressIndicator(color = Color.White)
                    Text("正在连接…", color = Color.White)
                }

                is RemoteUiState.Reconnecting -> Overlay {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        "连接已断开",
                        color = Color(0xFFFFB86B),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "约 ${s.delaySeconds} 秒后自动重连（第 ${s.attempt}/${s.maxAttempts} 次）",
                        color = Color(0xFFDDDDDD),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            reason,
                            color = Color(0xFF9FB0C2),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onReconnect) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text(" 立即重连")
                        }
                        Button(onClick = onDisconnect) {
                            Text("取消")
                        }
                    }
                }

                is RemoteUiState.Failed -> Overlay {
                    Text(
                        "连接失败",
                        color = Color(0xFFFF6B6B),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        s.message,
                        color = Color(0xFFDDDDDD),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onReconnect) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text(" 重试")
                        }
                        Button(onClick = onDisconnect) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                            Text(" 返回")
                        }
                    }
                }

                is RemoteUiState.Closed -> Overlay {
                    Text("会话已结束", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onReconnect) { Text("重连") }
                        Button(onClick = onDisconnect) { Text("返回") }
                    }
                }

                is RemoteUiState.Connected -> Unit
            }
        }

            // ------------------------------------------------ 底部控制台
            // 系统键盘 / 小键盘 / 控制台三者互斥：同时出现会把画面挤没。
            when {
                // 输入法弹起时，下半屏是键盘的，控制台先让位 —— 画面因此能独占
                // 键盘以上的全部空间，不再被压在键盘底下
                imeVisible -> Unit

                numpadVisible -> NumpadPanel(onKey = onNumpadKey, onClose = onToggleNumpad)

                deckVisible -> Column(
                    Modifier
                        .fillMaxWidth()
                        // 它是 Column 的第二个子项，量到的 maxHeight 仍是整屏高度，
                        // 所以这里就是「占屏幕 ratio」，画面用 weight(1f) 拿剩下的部分。
                        .fillMaxHeight(liveRatio),
                ) {
                    DeckSplitter(
                        ratio = liveRatio,
                        dragging = dragging,
                        onDragStart = { dragging = true },
                        onDrag = { dyPx ->
                            liveRatio = (liveRatio + dyPx / totalPx).coerceIn(minRatio, maxRatio)
                        },
                        onDragEnd = {
                            dragging = false
                            onDeckRatioChange(liveRatio)
                        },
                        onReset = {
                            liveRatio = defaultRatio
                            onDeckRatioChange(defaultRatio)
                        },
                    )
                    RemoteControlDeck(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        modifiers = modifiers,
                        onMovePointer = onMovePointer,
                        onLeftClick = onLeftClick,
                        onRightClick = onRightClick,
                        onMiddleClick = onMiddleClick,
                        onScroll = onScroll,
                        onHoldLeft = onHoldLeft,
                        onReleaseLeft = onReleaseLeft,
                        onToggleModifier = onToggleModifier,
                        onKeyTap = onKeyTap,
                        onCollapse = onToggleDeck,
                        onShowToolbar = onToggleToolbar,
                        speedLabel = pointerSpeedLabel,
                        onCycleSpeed = onCycleSpeed,
                        onShowApps = onShowApps,
                    )
                }

                else -> CollapsedDeckBar(onShow = onToggleDeck, onShowToolbar = onToggleToolbar)
            }
        }
    }

    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            title = { Text("操作说明") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("下方触摸板", fontWeight = FontWeight.SemiBold)
                    Text(
                        "滑动=移光标；轻点=左键；双指点=右键；双指上下滑=滚轮；" +
                            "单指画圈=滚轮（顺时针向下、逆时针向上）；" +
                            "按住不动约 0.4 秒再拖动=框选/拖拽。右侧「拖拽」键可锁定左键。",
                        fontSize = 13.sp,
                    )
                    Text("下方按键", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Ctrl/Alt/Shift/Win 点一下保持按下（高亮），再点一下松开，" +
                            "这样能敲 Ctrl+C、Alt+Tab 这类组合键。",
                        fontSize = 13.sp,
                    )
                    Text("画面上的手势", fontWeight = FontWeight.SemiBold)
                    Text(
                        "指针模式：滑动移光标，轻点左键，双指轻点右键。" +
                            "触控模式：手指点到哪光标就到哪，长按右键。",
                        fontSize = 13.sp,
                    )
                    Text("通用", fontWeight = FontWeight.SemiBold)
                    Text(
                        "双指捏合=缩放；放大后双指拖动=平移画面。外接鼠标可直接使用。",
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { helpOpen = false }) {
                    Text("知道了")
                }
            },
        )
    }
}

/**
 * 顶部工具栏。**它占自己的一行，不叠在画面上** —— 远程桌面最上面通常就是窗口标题栏、
 * 关闭按钮和菜单，浮层会直接把这些盖住，所以宁可让出 44dp 高度。
 * 想全屏看图/看视频时可以从菜单里隐藏它，隐藏后控制台底栏仍有一个 ☰ 入口。
 */
@Composable
private fun RemoteToolbar(
    title: String,
    inputMode: RemoteInputMode,
    numpadVisible: Boolean,
    deckVisible: Boolean,
    onToggleInputMode: () -> Unit,
    onKeyboard: () -> Unit,
    onToggleNumpad: () -> Unit,
    onResetZoom: () -> Unit,
    onToggleDeck: () -> Unit,
    onResetRatio: () -> Unit,
    onShowHelp: () -> Unit,
    onRightClick: () -> Unit,
    onCtrlAltDel: () -> Unit,
    onPasteClipboard: () -> Unit,
    onToggleToolbar: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1118))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDisconnect, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 当前输入模式，点一下切换：指针=触控板，触控=直接点
        IconButton(onClick = onToggleInputMode, modifier = Modifier.size(40.dp)) {
            Text(
                if (inputMode == RemoteInputMode.POINTER) "指针" else "触控",
                color = Color.White,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onKeyboard, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Keyboard, contentDescription = "键盘", tint = Color.White)
        }
        IconButton(onClick = onToggleNumpad, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Dialpad,
                contentDescription = "小键盘",
                tint = if (numpadVisible) Color(0xFF22D3A6) else Color.White,
            )
        }
        IconButton(onClick = onResetZoom, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.ZoomOutMap, contentDescription = "适应屏幕", tint = Color.White)
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color.White)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (inputMode == RemoteInputMode.POINTER) "切换到触控模式"
                            else "切换到指针模式",
                        )
                    },
                    onClick = { menuOpen = false; onToggleInputMode() },
                )
                DropdownMenuItem(
                    text = { Text("手势说明") },
                    onClick = { menuOpen = false; onShowHelp() },
                )
                DropdownMenuItem(
                    text = { Text(if (deckVisible) "收起触摸板" else "显示触摸板") },
                    onClick = { menuOpen = false; onToggleDeck() },
                )
                DropdownMenuItem(
                    text = { Text("恢复默认比例") },
                    onClick = { menuOpen = false; onResetRatio() },
                )
                DropdownMenuItem(
                    text = { Text(if (numpadVisible) "隐藏小键盘" else "显示小键盘") },
                    onClick = { menuOpen = false; onToggleNumpad() },
                )
                DropdownMenuItem(
                    text = { Text("右键单击") },
                    onClick = { menuOpen = false; onRightClick() },
                )
                DropdownMenuItem(
                    text = { Text("Ctrl+Alt+Del") },
                    onClick = { menuOpen = false; onCtrlAltDel() },
                )
                DropdownMenuItem(
                    text = { Text("发送剪贴板到 Windows") },
                    onClick = { menuOpen = false; onPasteClipboard() },
                )
                DropdownMenuItem(
                    text = { Text("隐藏工具栏（画面全屏）") },
                    onClick = { menuOpen = false; onToggleToolbar() },
                )
                DropdownMenuItem(
                    text = { Text("断开连接") },
                    onClick = { menuOpen = false; onDisconnect() },
                )
            }
        }
    }
}

/**
 * 画面与控制台之间的分隔条。上下拖动即可调整两者比例，双击恢复默认。
 *
 * 为什么需要它：控制台多高才算合适完全取决于机型和横竖屏 —— 同一个人竖屏时想要大画面，
 * 横屏时又需要大触摸板。给死值总有一边不对，索性交给手指。
 */
@Composable
private fun DeckSplitter(
    ratio: Float,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
) {
    val view = LocalView.current
    // pointerInput(Unit) 里的 lambda 只创建一次，必须用 rememberUpdatedState 取最新回调，
    // 否则永远调用第一帧的闭包（拿到的 ratio 是拖动开始时的值，越拖越偏）。
    val startNow by rememberUpdatedState(onDragStart)
    val dragNow by rememberUpdatedState(onDrag)
    val endNow by rememberUpdatedState(onDragEnd)
    val resetNow by rememberUpdatedState(onReset)

    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Color(0xFF0B1118))
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { resetNow() })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        startNow()
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    },
                    onDragEnd = { endNow() },
                    onDragCancel = { endNow() },
                ) { change, dy ->
                    change.consume()
                    dragNow(dy)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .width(46.dp)
                    .height(3.dp)
                    .background(
                        if (dragging) Color(0xFF22D3A6) else Color(0xFF3A4A5C),
                        RoundedCornerShape(2.dp),
                    ),
            )
            if (dragging) {
                Text(
                    "${(ratio * 100).toInt()}%",
                    color = Color(0xFF22D3A6),
                    fontSize = 9.sp,
                )
            }
        }
    }
}

/** 控制台收起时贴在底部的细条，点一下重新展开 */
@Composable
private fun CollapsedDeckBar(onShow: () -> Unit, onShowToolbar: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1118))
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxSize().clickable(onClick = onShow),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color(0xFF9FB0C2),
                    modifier = Modifier.size(16.dp),
                )
                Text("显示触摸板与按键", color = Color(0xFF9FB0C2), fontSize = 11.sp)
            }
            // 工具栏也收起时，这里仍然有一个入口，不会被困在全屏画面里出不来
            Text(
                "☰",
                color = Color(0xFF9FB0C2),
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onShowToolbar)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
    }
}

private data class NumpadKeySpec(
    val label: String,
    val keyCode: Int,
    val weight: Float = 1f,
    val accent: Boolean = false,
)

/**
 * 贴在画面底部的数字小键盘。按键直接走远端键盘通道，不依赖系统输入法，
 * 适合输入 Windows 登录 PIN、数字密码和计算器类操作。
 */
@Composable
private fun NumpadPanel(onKey: (Int) -> Unit, onClose: () -> Unit) {
    // 横屏手机可用高度只有 360dp 左右，按键收紧一些，少挡画面。
    val compact = LocalConfiguration.current.screenHeightDp < 520
    val gap = if (compact) 4.dp else 6.dp
    val keyHeight = if (compact) 36.dp else 44.dp
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1118))
            .safeDrawingPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .background(Color(0xF20B1118), RoundedCornerShape(16.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "小键盘",
                    color = Color(0xFF9FB0C2),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "收起小键盘",
                        tint = Color(0xFF9FB0C2),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            NumpadRow(
                listOf(
                    NumpadKeySpec("Esc", KeyEvent.KEYCODE_ESCAPE),
                    NumpadKeySpec("/", KeyEvent.KEYCODE_NUMPAD_DIVIDE),
                    NumpadKeySpec("×", KeyEvent.KEYCODE_NUMPAD_MULTIPLY),
                    NumpadKeySpec("-", KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
                ),
                onKey,
                keyHeight,
                gap,
            )
            NumpadRow(
                listOf(
                    NumpadKeySpec("7", KeyEvent.KEYCODE_7),
                    NumpadKeySpec("8", KeyEvent.KEYCODE_8),
                    NumpadKeySpec("9", KeyEvent.KEYCODE_9),
                    NumpadKeySpec("+", KeyEvent.KEYCODE_NUMPAD_ADD),
                ),
                onKey,
                keyHeight,
                gap,
            )
            NumpadRow(
                listOf(
                    NumpadKeySpec("4", KeyEvent.KEYCODE_4),
                    NumpadKeySpec("5", KeyEvent.KEYCODE_5),
                    NumpadKeySpec("6", KeyEvent.KEYCODE_6),
                    NumpadKeySpec("+", KeyEvent.KEYCODE_NUMPAD_ADD),
                ),
                onKey,
                keyHeight,
                gap,
            )
            NumpadRow(
                listOf(
                    NumpadKeySpec("1", KeyEvent.KEYCODE_1),
                    NumpadKeySpec("2", KeyEvent.KEYCODE_2),
                    NumpadKeySpec("3", KeyEvent.KEYCODE_3),
                    NumpadKeySpec("Enter", KeyEvent.KEYCODE_ENTER, accent = true),
                ),
                onKey,
                keyHeight,
                gap,
            )
            NumpadRow(
                listOf(
                    NumpadKeySpec("0", KeyEvent.KEYCODE_0, weight = 2f),
                    NumpadKeySpec(".", KeyEvent.KEYCODE_PERIOD),
                    NumpadKeySpec("⌫", KeyEvent.KEYCODE_DEL),
                ),
                onKey,
                keyHeight,
                gap,
            )
        }
    }
}

@Composable
private fun NumpadRow(
    keys: List<NumpadKeySpec>,
    onKey: (Int) -> Unit,
    keyHeight: Dp,
    gap: Dp,
) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        keys.forEach { key ->
            Box(
                Modifier
                    .weight(key.weight)
                    .height(keyHeight)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (key.accent) Color(0xFF17654B) else Color(0xFF1A2632))
                    .clickable {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        onKey(key.keyCode)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    key.label,
                    color = Color(0xFFE6EDF3),
                    fontSize = if (key.label.length > 2) 12.sp else 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}
