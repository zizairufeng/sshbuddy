package com.czk.sshai.rdp

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Android KeyEvent → Windows 虚拟键码（VK）。
 *
 * FreeRDP 的 JNI 入口 `freerdp_send_key_event` 内部调用的是
 * `GetVirtualScanCodeFromVirtualKeyCode()`，它期待 **Windows VK**，而不是
 * Android 的 `KEYCODE_*`。两者数值完全对不上：Android 的 KEYCODE_ENTER 是 66，
 * 恰好等于 Windows 的 'B'；KEYCODE_DEL 是 67，等于 'C'。直接透传会把回车
 * 变成字母、字母变成别的键，所以这里必须显式转换。
 *
 * 映射表与官方 aFreeRDP 的 KeyboardMapper 保持一致；[EXT] 对应它的
 * VK_EXT_KEY，native 侧据此设置 RDP 的扩展键标志（方向键、Home/End 等）。
 */
internal object RdpKeyMapper {

    /** 与 aFreeRDP 的 VK_EXT_KEY 相同；native 会把它转换成 KBD_FLAGS_EXTENDED */
    private const val EXT = 0x100

    /** 返回 0 表示这个 Android keycode 没有对应的 Windows 键，调用方应忽略。 */
    fun windowsVk(androidKeyCode: Int): Int = when (androidKeyCode) {
        KeyEvent.KEYCODE_0 -> 0x30
        KeyEvent.KEYCODE_1 -> 0x31
        KeyEvent.KEYCODE_2 -> 0x32
        KeyEvent.KEYCODE_3 -> 0x33
        KeyEvent.KEYCODE_4 -> 0x34
        KeyEvent.KEYCODE_5 -> 0x35
        KeyEvent.KEYCODE_6 -> 0x36
        KeyEvent.KEYCODE_7 -> 0x37
        KeyEvent.KEYCODE_8 -> 0x38
        KeyEvent.KEYCODE_9 -> 0x39

        KeyEvent.KEYCODE_A -> 0x41
        KeyEvent.KEYCODE_B -> 0x42
        KeyEvent.KEYCODE_C -> 0x43
        KeyEvent.KEYCODE_D -> 0x44
        KeyEvent.KEYCODE_E -> 0x45
        KeyEvent.KEYCODE_F -> 0x46
        KeyEvent.KEYCODE_G -> 0x47
        KeyEvent.KEYCODE_H -> 0x48
        KeyEvent.KEYCODE_I -> 0x49
        KeyEvent.KEYCODE_J -> 0x4A
        KeyEvent.KEYCODE_K -> 0x4B
        KeyEvent.KEYCODE_L -> 0x4C
        KeyEvent.KEYCODE_M -> 0x4D
        KeyEvent.KEYCODE_N -> 0x4E
        KeyEvent.KEYCODE_O -> 0x4F
        KeyEvent.KEYCODE_P -> 0x50
        KeyEvent.KEYCODE_Q -> 0x51
        KeyEvent.KEYCODE_R -> 0x52
        KeyEvent.KEYCODE_S -> 0x53
        KeyEvent.KEYCODE_T -> 0x54
        KeyEvent.KEYCODE_U -> 0x55
        KeyEvent.KEYCODE_V -> 0x56
        KeyEvent.KEYCODE_W -> 0x57
        KeyEvent.KEYCODE_X -> 0x58
        KeyEvent.KEYCODE_Y -> 0x59
        KeyEvent.KEYCODE_Z -> 0x5A

        KeyEvent.KEYCODE_DEL -> 0x08 // VK_BACK
        KeyEvent.KEYCODE_TAB -> 0x09
        KeyEvent.KEYCODE_ENTER -> 0x0D
        KeyEvent.KEYCODE_SPACE -> 0x20
        KeyEvent.KEYCODE_ESCAPE -> 0x1B
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x14

        // 修饰键：和 aFreeRDP 一样统一用左侧键，避免左右状态不同步
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA0 // VK_LSHIFT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA2 // VK_LCONTROL
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 0xA4 // VK_LMENU
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> 0x5B or EXT

        KeyEvent.KEYCODE_DPAD_LEFT -> 0x25 or EXT
        KeyEvent.KEYCODE_DPAD_UP -> 0x26 or EXT
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27 or EXT
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x28 or EXT
        KeyEvent.KEYCODE_MOVE_HOME -> 0x24 or EXT
        KeyEvent.KEYCODE_MOVE_END -> 0x23 or EXT
        KeyEvent.KEYCODE_PAGE_UP -> 0x21 or EXT
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x22 or EXT
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x2E or EXT
        KeyEvent.KEYCODE_INSERT -> 0x2D or EXT

        KeyEvent.KEYCODE_MINUS -> 0xBD
        KeyEvent.KEYCODE_EQUALS -> 0xBB
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD
        KeyEvent.KEYCODE_BACKSLASH -> 0xDC
        KeyEvent.KEYCODE_SEMICOLON -> 0xBA
        KeyEvent.KEYCODE_APOSTROPHE -> 0xDE
        KeyEvent.KEYCODE_GRAVE -> 0xC0
        KeyEvent.KEYCODE_COMMA -> 0xBC
        KeyEvent.KEYCODE_PERIOD -> 0xBE
        KeyEvent.KEYCODE_SLASH -> 0xBF

        KeyEvent.KEYCODE_F1 -> 0x70
        KeyEvent.KEYCODE_F2 -> 0x71
        KeyEvent.KEYCODE_F3 -> 0x72
        KeyEvent.KEYCODE_F4 -> 0x73
        KeyEvent.KEYCODE_F5 -> 0x74
        KeyEvent.KEYCODE_F6 -> 0x75
        KeyEvent.KEYCODE_F7 -> 0x76
        KeyEvent.KEYCODE_F8 -> 0x77
        KeyEvent.KEYCODE_F9 -> 0x78
        KeyEvent.KEYCODE_F10 -> 0x79
        KeyEvent.KEYCODE_F11 -> 0x7A
        KeyEvent.KEYCODE_F12 -> 0x7B

        KeyEvent.KEYCODE_NUM_LOCK -> 0x90
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0x91
        KeyEvent.KEYCODE_SYSRQ -> 0x2C or EXT
        KeyEvent.KEYCODE_BREAK -> 0x13

        KeyEvent.KEYCODE_NUMPAD_0 -> 0x60
        KeyEvent.KEYCODE_NUMPAD_1 -> 0x61
        KeyEvent.KEYCODE_NUMPAD_2 -> 0x62
        KeyEvent.KEYCODE_NUMPAD_3 -> 0x63
        KeyEvent.KEYCODE_NUMPAD_4 -> 0x64
        KeyEvent.KEYCODE_NUMPAD_5 -> 0x65
        KeyEvent.KEYCODE_NUMPAD_6 -> 0x66
        KeyEvent.KEYCODE_NUMPAD_7 -> 0x67
        KeyEvent.KEYCODE_NUMPAD_8 -> 0x68
        KeyEvent.KEYCODE_NUMPAD_9 -> 0x69
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x6F or EXT
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x6A
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x6B
        KeyEvent.KEYCODE_NUMPAD_ADD -> 0x6C
        KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_NUMPAD_COMMA -> 0x6E
        KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x0D or EXT

        KeyEvent.KEYCODE_MENU -> 0x5D or EXT // VK_APPS

        else -> 0
    }

    /**
     * 服务端不支持 Unicode 输入时，把字符拆成「虚拟键 + Shift」事件。
     * 用的是 Android 自带的虚拟键盘布局，字母大小写、数字和常见符号都能覆盖。
     * 返回 null 表示这个字符没法用键码敲出来（例如中文）。
     */
    fun eventsForChar(codePoint: Int): Array<KeyEvent>? {
        if (codePoint < 0 || codePoint > 0xFFFF) return null
        return runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(charArrayOf(codePoint.toChar()))
        }.getOrNull()
    }
}
