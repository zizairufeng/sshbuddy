package com.czk.sshai

/**
 * 手机键盘上没有、但用 Linux 迟早要的那些键。
 *
 * 参考 JuiceSSH / Termius 的做法：一条可横向滚动的按键条 + Ctrl / Alt 粘滞键。
 * 说明：Ctrl、Alt 是「粘住」的 —— 点一下变亮，再点一个字母/符号就组成组合键，然后自动复位。
 * 这样只需要一行按钮就能覆盖 Ctrl+C / Ctrl+D / Ctrl+Z / Ctrl+L / Alt+. 等常用组合。
 */
object KeyPad {

    /** 一次按键动作：要发出去的原始字节（不是命令，不加回车） */
    data class Tap(val label: String, val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Tap && other.label == label
        override fun hashCode() = label.hashCode()
    }

    const val ESC = "\u001B"
    const val TAB = "\t"
    const val ENTER = "\r"

    // 方向键：CSI 序列
    val UP = Tap("↑", "$ESC[A".toByteArray())
    val DOWN = Tap("↓", "$ESC[B".toByteArray())
    val RIGHT = Tap("→", "$ESC[C".toByteArray())
    val LEFT = Tap("←", "$ESC[D".toByteArray())
    val HOME = Tap("Home", "$ESC[H".toByteArray())
    val END = Tap("End", "$ESC[F".toByteArray())
    val PAGE_UP = Tap("PgUp", "$ESC[5~".toByteArray())
    val PAGE_DOWN = Tap("PgDn", "$ESC[6~".toByteArray())
    val DELETE = Tap("Del", "$ESC[3~".toByteArray())
    val INSERT = Tap("Ins", "$ESC[2~".toByteArray())
    val F1 = Tap("F1", "${ESC}OP".toByteArray())
    val F2 = Tap("F2", "${ESC}OQ".toByteArray())

    /** 按键条上直接点的普通键（不需要组合） */
    val plain: List<Tap> = listOf(
        Tap("Esc", "$ESC".toByteArray()),
        Tap("Tab", TAB.toByteArray()),
        Tap("←", LEFT.bytes), Tap("↑", UP.bytes), Tap("↓", DOWN.bytes), Tap("→", RIGHT.bytes),
        Tap("Home", HOME.bytes), Tap("End", END.bytes),
        Tap("PgUp", PAGE_UP.bytes), Tap("PgDn", PAGE_DOWN.bytes),
        Tap("Del", DELETE.bytes),
        Tap("-", "-".toByteArray()), Tap("/", "/".toByteArray()),
        Tap("|", "|".toByteArray()), Tap("~", "~".toByteArray()),
        Tap("*", "*".toByteArray()), Tap("$", "$".toByteArray()),
    )

    /** 组合键可用的基础字符（Ctrl 用大写字母，Alt 用符号） */
    val comboChars = listOf("a", "b", "c", "d", "e", "f", "k", "l", "r", "u", "w", "x", "z")

    /**
     * 把 Ctrl 粘到某个字符上，得到控制字节。
     * 规则（ASCII）：Ctrl+A..Ctrl+Z = 0x01..0x1A；Ctrl+[ = 0x1B；Ctrl+\ = 0x1C；Ctrl+] = 0x1D
     */
    fun ctrl(ch: Char): Byte? {
        val c = ch.uppercaseChar()
        val v: Int = when {
            c in 'A'..'Z' -> c.code - 'A'.code + 1
            c == '[' -> 0x1B
            c == '\\' -> 0x1C
            c == ']' -> 0x1D
            c == ' ' -> 0x00
            else -> return null
        }
        return v.toByte()
    }

    /** Alt = 前缀 ESC（终端里的 Meta 键约定） */
    fun alt(text: String): ByteArray = ("$ESC$text").toByteArray(Charsets.UTF_8)

    /** 有 Ctrl 时按组合键处理 */
    fun withCtrl(ch: Char): ByteArray? = ctrl(ch)?.let { byteArrayOf(it) }
}
