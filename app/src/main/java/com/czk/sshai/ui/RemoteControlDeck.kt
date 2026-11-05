package com.czk.sshai.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 远端会话底部的控制台（RDP）：一块触摸板 + 常用按键。
 *
 * 为什么单独做一块触摸板，而不是复用画面上的手势：
 * 画面收在上半部分后，手指直接点画面就得眯着眼对准小按钮；触摸板是**相对移动**
 * （滑一下就移光标，不受画面缩放影响），配合下面的 Esc/Tab/Ctrl/方向键，
 * 处理 Windows 的右键菜单、任务管理器、表格框选这类操作才顺手。
 */

private val PanelBg = Color(0xFF0B1118)
private val KeyBg = Color(0xFF1A2632)
private val KeyPressed = Color(0xFF2E4459)
private val KeyActive = Color(0xFF17654B)
private val KeyAccent = Color(0xFF1F4A66)
private val PadTop = Color(0xFF17222E)
private val PadBottom = Color(0xFF0F1922)

/** 画圈手势用的常量：手算比 import 更清楚，避免 Float/Double 来回转换 */
private const val PI_F = 3.1415927f
private const val TWO_PI_F = 6.2831855f
private val PadBorder = Color(0xFF243343)
private val TextMain = Color(0xFFE6EDF3)
private val TextDim = Color(0xFF9FB0C2)

private data class DeckKey(
    val label: String,
    val keyCode: Int,
    /** 修饰键：点一下保持按下，再点一下松开（和键盘的 CapsLock 手感类似） */
    val modifier: Boolean = false,
    val accent: Boolean = false,
    val weight: Float = 1f,
)

/**
 * 数字键常驻一行。远程桌面里输入 IP、端口、PIN、密码、Excel 数值都要数字，
 * 每次都去开系统键盘太麻烦；`-` 和 `.` 是 IP / 路径 / 小数最常用的两个符号。
 */
private val RowDigits = listOf(
    DeckKey("1", KeyEvent.KEYCODE_1),
    DeckKey("2", KeyEvent.KEYCODE_2),
    DeckKey("3", KeyEvent.KEYCODE_3),
    DeckKey("4", KeyEvent.KEYCODE_4),
    DeckKey("5", KeyEvent.KEYCODE_5),
    DeckKey("6", KeyEvent.KEYCODE_6),
    DeckKey("7", KeyEvent.KEYCODE_7),
    DeckKey("8", KeyEvent.KEYCODE_8),
    DeckKey("9", KeyEvent.KEYCODE_9),
    DeckKey("0", KeyEvent.KEYCODE_0),
    DeckKey("-", KeyEvent.KEYCODE_MINUS),
    DeckKey(".", KeyEvent.KEYCODE_PERIOD),
)

/** 第一行：修饰键 + 最常用的编辑键 */
private val RowModifiers = listOf(
    DeckKey("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, modifier = true),
    DeckKey("Alt", KeyEvent.KEYCODE_ALT_LEFT, modifier = true),
    DeckKey("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, modifier = true),
    DeckKey("Win", KeyEvent.KEYCODE_META_LEFT, modifier = true),
    DeckKey("Esc", KeyEvent.KEYCODE_ESCAPE),
    DeckKey("Tab", KeyEvent.KEYCODE_TAB),
    DeckKey("⌫", KeyEvent.KEYCODE_DEL),
    DeckKey("Enter", KeyEvent.KEYCODE_ENTER, accent = true, weight = 1.4f),
)

/** 第二行：导航键（远端 Windows 里翻页、改单元格位置都靠它） */
private val RowNavigation = listOf(
    DeckKey("←", KeyEvent.KEYCODE_DPAD_LEFT),
    DeckKey("↑", KeyEvent.KEYCODE_DPAD_UP),
    DeckKey("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    DeckKey("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    DeckKey("Home", KeyEvent.KEYCODE_MOVE_HOME),
    DeckKey("End", KeyEvent.KEYCODE_MOVE_END),
    DeckKey("PgUp", KeyEvent.KEYCODE_PAGE_UP),
    DeckKey("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
)

/** 第二行切到 F 键后的样子 */
private val RowF1toF8 = listOf(
    DeckKey("F1", KeyEvent.KEYCODE_F1),
    DeckKey("F2", KeyEvent.KEYCODE_F2),
    DeckKey("F3", KeyEvent.KEYCODE_F3),
    DeckKey("F4", KeyEvent.KEYCODE_F4),
    DeckKey("F5", KeyEvent.KEYCODE_F5),
    DeckKey("F6", KeyEvent.KEYCODE_F6),
    DeckKey("F7", KeyEvent.KEYCODE_F7),
    DeckKey("F8", KeyEvent.KEYCODE_F8),
)

/** 切到 F 键时多出来的一行：F9–F12 + Insert/Delete（这两个键在远端桌面里也常用） */
private val RowF9toF12 = listOf(
    DeckKey("F9", KeyEvent.KEYCODE_F9),
    DeckKey("F10", KeyEvent.KEYCODE_F10),
    DeckKey("F11", KeyEvent.KEYCODE_F11),
    DeckKey("F12", KeyEvent.KEYCODE_F12),
    DeckKey("Ins", KeyEvent.KEYCODE_INSERT),
    DeckKey("Del", KeyEvent.KEYCODE_FORWARD_DEL),
)

@Composable
fun RemoteControlDeck(
    /** 当前按住不放的修饰键（Android keycode 集合） */
    modifiers: Set<Int>,
    onMovePointer: (Float, Float) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onMiddleClick: () -> Unit,
    onScroll: (Int, Boolean) -> Unit,
    onHoldLeft: () -> Unit,
    onReleaseLeft: () -> Unit,
    onToggleModifier: (Int) -> Unit,
    onKeyTap: (Int) -> Unit,
    onCollapse: () -> Unit,
    /** 工具栏被隐藏时，控制台底栏要留一个入口把它叫回来 */
    onShowToolbar: () -> Unit,
    /** 指针速度档位文案（慢 / 标准 / 快） */
    speedLabel: String,
    onCycleSpeed: () -> Unit,
    /**
     * 打开远端系统的应用启动器（GNOME 的活动视图 / Windows 的开始菜单）。
     *
     * 为什么需要单独的按钮：GNOME 唤出应用列表要求 Super 键**按下再抬起**，
     * 而控制台上的 Win 是**粘滞修饰键**（只按下，等组合下一个键），
     * 单点它唤不出列表 —— 用户会觉得"打不开别的应用"。
     */
    onShowApps: () -> Unit,
    /** 由外层控制台容器决定高度（按屏幕比例），这里只管把内容铺满 */
    modifier: Modifier = Modifier,
) {
    // 横屏手机可用高度只有 360dp 左右，按键收紧一些；高度不再写死，
    // 由 RdpScreen 按比例分配，触摸板用 weight 吃掉按键之外的剩余空间。
    val compact = LocalConfiguration.current.screenHeightDp < 520
    val keyHeight = if (compact) 30.dp else 38.dp
    val gap = if (compact) 4.dp else 6.dp

    var dragLocked by remember { mutableStateOf(false) }
    var fKeyRow by remember { mutableStateOf(false) }

    Column(
        modifier
            .background(PanelBg)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        // 触摸板是弹性项：拖分隔条时「变高的是触摸板」，按键高度保持稳定，手感不跳。
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            TouchpadPad(
                locked = dragLocked,
                onMove = onMovePointer,
                onLeftClick = onLeftClick,
                onRightClick = onRightClick,
                onScroll = onScroll,
                onHold = onHoldLeft,
                onRelease = onReleaseLeft,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            // 右侧竖排鼠标键：和笔记本触摸板下面的左右键一个思路，
            // 手指在左、按键在右，单手也能操作。
            Column(
                Modifier.width(if (compact) 46.dp else 54.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                DeckButton(
                    "左键", active = false, modifier = Modifier.weight(1f).fillMaxWidth(),
                    fontSize = 11,
                ) { onLeftClick() }
                DeckButton(
                    "右键", active = false, modifier = Modifier.weight(1f).fillMaxWidth(),
                    fontSize = 11,
                ) { onRightClick() }
                DeckButton(
                    "中键", active = false, modifier = Modifier.weight(1f).fillMaxWidth(),
                    fontSize = 11,
                ) { onMiddleClick() }
                DeckButton(
                    "拖拽", active = dragLocked, modifier = Modifier.weight(1f).fillMaxWidth(),
                    fontSize = 11,
                ) {
                    dragLocked = !dragLocked
                    if (dragLocked) onHoldLeft() else onReleaseLeft()
                }
            }
        }

        KeyRow(
            keys = RowDigits,
            keyHeight = keyHeight,
            gap = gap,
            modifiers = modifiers,
            onKeyTap = onKeyTap,
            onToggleModifier = onToggleModifier,
        )
        KeyRow(
            keys = RowModifiers,
            keyHeight = keyHeight,
            gap = gap,
            modifiers = modifiers,
            onKeyTap = onKeyTap,
            onToggleModifier = onToggleModifier,
        )
        KeyRow(
            keys = if (fKeyRow) RowF1toF8 else RowNavigation,
            keyHeight = keyHeight,
            gap = gap,
            modifiers = modifiers,
            onKeyTap = onKeyTap,
            onToggleModifier = onToggleModifier,
        )
        if (fKeyRow) {
            KeyRow(
                keys = RowF9toF12,
                keyHeight = keyHeight,
                gap = gap,
                modifiers = modifiers,
                onKeyTap = onKeyTap,
                onToggleModifier = onToggleModifier,
            )
        }

        // 细条：标题 + F 键切换 + 收起。占高很小，尽量把空间留给画面。
        Row(
            Modifier.fillMaxWidth().height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (dragLocked) "拖拽已锁定（左键按住）"
                else "触摸板 · 拖上边缘调高度",
                color = if (dragLocked) Color(0xFF22D3A6) else TextDim,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DeckChip(text = "应用", onClick = onShowApps)
            DeckChip(
                text = "指针·$speedLabel",
                onClick = onCycleSpeed,
            )
            DeckChip(
                text = if (fKeyRow) "方向" else "F键",
                onClick = { fKeyRow = !fKeyRow },
            )
            DeckChip(text = "☰", onClick = onShowToolbar)
            DeckChip(text = "收起 ⌄", onClick = onCollapse)
        }
    }
}

/**
 * 触摸板本体。
 *
 * 手势（和笔记本触摸板保持一致）：
 * - 单指滑动 = 移动光标
 * - 单指点一下 = 左键
 * - 双指点一下 = 右键
 * - 双指上下滑 = 滚轮
 * - 单指画圈 = 滚轮（顺时针向下 / 逆时针向上）
 * - 单指按住不动约 0.4 秒后拖动 = 按住左键拖拽（框选文字/拖文件）
 */
@Composable
private fun TouchpadPad(
    locked: Boolean,
    onMove: (Float, Float) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Int, Boolean) -> Unit,
    onHold: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 用 rememberUpdatedState 让长驻的 pointerInput 始终拿到最新的回调/状态，
    // 否则 dragLocked 变化时重建手势循环，会把进行中的手势掐断。
    val lockedNow by rememberUpdatedState(locked)
    val moveNow by rememberUpdatedState(onMove)
    val leftNow by rememberUpdatedState(onLeftClick)
    val rightNow by rememberUpdatedState(onRightClick)
    val scrollNow by rememberUpdatedState(onScroll)
    val holdNow by rememberUpdatedState(onHold)
    val releaseNow by rememberUpdatedState(onRelease)
    val view = LocalView.current
    // 是否正处于「画圈滚轮」状态。只用来给用户一个视觉反馈（画圈不像点击有明确的落点），
    // 不参与任何判定，所以不必做得非常精确。
    var rotating by remember { mutableStateOf(false) }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(PadTop, PadBottom)))
            .border(1.dp, PadBorder, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                val slop = 10.dp.toPx()
                val scrollStep = 26.dp.toPx()
                val longPressMs = 380L
                // ---------------- 画圈滚轮
                // 判据是「绕重心的极角累计转了多少」，不是位移也不是速度：
                // 直线滑动时重心始终落在手指后方，极角几乎不变；
                // 画圈时重心会收敛到圆心，极角单调累积。两者在数学上就不会混淆。
                val rotTrigger = 2.6f              // 累计转过约 150° 才认为在画圈
                val rotStep = 0.62f                // 每转约 35° 滚一格（转一圈约 10 格）
                val rotMinRadius = 8.dp.toPx()      // 半径太小时角度全是噪声，直接跳过
                val rotMinPath = 48.dp.toPx()       // 路径太短时重心还没收敛，不能用
                val cenAlpha = 0.12f               // 重心的指数滑动平均系数

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var totalMove = 0f
                    var dragMode = false
                    var twoFinger = false
                    // 两只手指的抖动比一根手指大得多，用「有没有真的滚过」判断双指点按，
                    // 比累计位移可靠：只有超过一格滚动阈值才算滚动。
                    var scrolled = false
                    var scrollAcc = 0f
                    var lastCy = down.position.y

                    // 画圈滚轮的状态
                    var cenX = down.position.x
                    var cenY = down.position.y
                    var hasAngle = false
                    var lastAngle = 0f
                    var rotAccum = 0f    // 累计转角，用来判断「是不是在画圈」
                    var rotPending = 0f  // 还没发出去的转角，攒够一格发一次
                    var rotMode = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                var sumY = 0f
                                pressed.forEach { sumY += it.position.y }
                                val cy = sumY / pressed.size
                                if (!twoFinger) {
                                    // 第二根手指落下：放弃可能已经开始的长按拖拽
                                    twoFinger = true
                                    scrollAcc = 0f
                                    // 也放弃画圈：手指数量变了就不是同一个手势了
                                    rotMode = false
                                    rotAccum = 0f
                                    rotPending = 0f
                                    hasAngle = false
                                    if (dragMode) {
                                        dragMode = false
                                        releaseNow()
                                    }
                                } else {
                                    val dy = cy - lastCy
                                    scrollAcc += dy
                                    if (abs(scrollAcc) >= scrollStep) {
                                        val ticks = (abs(scrollAcc) / scrollStep).toInt().coerceIn(1, 3)
                                        scrollNow(ticks, scrollAcc > 0f)
                                        scrollAcc = 0f
                                        scrolled = true
                                    }
                                }
                                lastCy = cy
                                pressed.forEach { it.consume() }
                            } else {
                                val ch = pressed.first()
                                val dx = ch.position.x - lastX
                                val dy = ch.position.y - lastY
                                lastX = ch.position.x
                                lastY = ch.position.y
                                if (!twoFinger) {
                                    totalMove += hypot(dx, dy)
                                    // 先「按住不动」再移动才算拖拽：这个判定放在移动事件里，
                                    // 所以抬手时的快速滑动永远不会被误判成拖拽。
                                    if (!dragMode && !rotMode && !lockedNow && totalMove < slop &&
                                        ch.uptimeMillis - down.uptimeMillis > longPressMs
                                    ) {
                                        dragMode = true
                                        holdNow()
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }

                                    // ---------- 单指画圈 = 滚轮 ----------
                                    if (!dragMode) {
                                        // 重心做指数滑动平均：画出小圈时它会逐渐收敛到圆心
                                        cenX += (ch.position.x - cenX) * cenAlpha
                                        cenY += (ch.position.y - cenY) * cenAlpha
                                        val rx = ch.position.x - cenX
                                        val ry = ch.position.y - cenY
                                        if (hypot(rx, ry) > rotMinRadius) {
                                            // 屏幕坐标 y 向下，所以 atan2 增大 = 视觉上顺时针
                                            val ang = atan2(ry, rx)
                                            if (hasAngle) {
                                                var d = ang - lastAngle
                                                // 跨过 ±π 时会出现约 2π 的假跳变，归一化回真实增量
                                                while (d > PI_F) d -= TWO_PI_F
                                                while (d < -PI_F) d += TWO_PI_F
                                                // 单帧转 90° 以上不可能是手指动作：那是「来回擦」时
                                                // 半径穿过重心、极角瞬间翻面的假跳变。丢掉它，
                                                // 否则先往右再往左滑就会被误判成画了半圈。
                                                if (abs(d) < 1.6f) {
                                                    rotAccum += d
                                                    rotPending += d
                                                }
                                            }
                                            lastAngle = ang
                                            hasAngle = true
                                        }
                                        if (!rotMode && totalMove > rotMinPath &&
                                            abs(rotAccum) > rotTrigger
                                        ) {
                                            // 转过足够角度才算画圈；这一刻之后的转动都换成滚轮，
                                            // 之前已经转掉的角度也补发，滚动量始终和转动角度成正比
                                            rotMode = true
                                            rotating = true
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                    }

                                    if (rotMode) {
                                        // 顺时针（转角为正）= 向下滚，逆时针 = 向上滚
                                        val cw = rotPending > 0f
                                        var n = (abs(rotPending) / rotStep).toInt()
                                        while (n > 0) {
                                            val chunk = minOf(n, 2)
                                            scrollNow(chunk, cw)
                                            n -= chunk
                                            rotPending -= if (cw) chunk * rotStep else -chunk * rotStep
                                        }
                                    } else if (dx != 0f || dy != 0f) {
                                        moveNow(dx, dy)
                                    }
                                }
                                ch.consume()
                            }
                        }
                    } finally {
                        // 手势被系统打断时也要复位，否则提示会一直停在「滚轮中」
                        rotating = false
                    }

                    if (dragMode) {
                        releaseNow()
                    } else if (twoFinger) {
                        // 双指几乎没动 = 双指轻点 → 右键
                        if (!scrolled) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            rightNow()
                        }
                    } else if (!rotMode && totalMove < slop) {
                        // 拖拽锁定时不再响应单击，否则会把锁定的左键松开
                        if (!lockedNow) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            leftNow()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // 不用 ↻ / ↺ 这类符号：部分 ROM 的中文字体会把它们渲染成方框
            if (rotating) "滚轮中 · 顺↓ 逆↑" else "滑动移动光标 · 画圈滚动",
            color = if (rotating) Color(0x5522D3A6) else Color(0x2EFFFFFF),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun KeyRow(
    keys: List<DeckKey>,
    keyHeight: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp,
    modifiers: Set<Int>,
    onKeyTap: (Int) -> Unit,
    onToggleModifier: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        keys.forEach { key ->
            DeckButton(
                label = key.label,
                active = key.modifier && modifiers.contains(key.keyCode),
                accent = key.accent,
                modifier = Modifier.weight(key.weight).height(keyHeight),
                fontSize = if (key.label.length > 2) 11 else 13,
            ) {
                if (key.modifier) onToggleModifier(key.keyCode) else onKeyTap(key.keyCode)
            }
        }
    }
}

@Composable
private fun DeckButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    fontSize: Int = 13,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    // 按下时给一个明显的底色反馈：远程桌面本身有延迟，没有本地反馈会让人以为没按上
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        active -> KeyActive
        pressed -> KeyPressed
        accent -> KeyAccent
        else -> KeyBg
    }
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) Color.White else TextMain,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DeckChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = TextDim,
        fontSize = 11.sp,
        modifier = Modifier
            .padding(start = 10.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
