package com.czk.sshai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.czk.sshai.remote.RemoteButtons
import com.czk.sshai.remote.RemoteSession
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 两种输入模式，语义对齐向日葵：
 * - POINTER（指针/触控板）：屏幕当触控板，手指滑动移光标，轻点左键；
 * - TOUCH（触控）：手指点到哪里，远端光标就去哪里，拖动即拖拽。
 */
enum class RemoteInputMode { POINTER, TOUCH }

/**
 * **唯一的**远端画面渲染 + 手势实现（v1.8.0 起只服务 RDP）。
 *
 * 以前这里有两份（RdpSurfaceView / VncSurfaceView），结果两份能力不一致、手感不同。
 * 现在只有这一份，协议差异全部藏在 [RemoteSession] 后面。VNC 已在 v1.8.0 移除。
 *
 * 用 SurfaceView + lockCanvas 而不是硬件加速的 View.onDraw：RDP 的 native 侧直接写
 * Bitmap 的像素内存，不会更新 Bitmap 的 generationId，硬件加速的纹理缓存拿不到新内容。
 * 软件 Canvas 每次从像素内存采样，确保画面是最新的。
 *
 * 触摸并不是 Android 的鼠标指针，所有点击/拖动都要自己翻译成远端的指针事件；
 * 本地只负责画光标（仅 [RemoteSession.needsLocalCursor] 为 true 时，即 RDP）。
 */
@SuppressLint("ViewConstructor")
class RemoteSurfaceView(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

    // 渲染线程会读，必须可见；连接页赋值后立刻 start()。
    @Volatile var session: RemoteSession? = null

    /** 切换输入模式时顺手把没有结束的拖拽松开，避免远端一直按着左键。 */
    var inputMode: RemoteInputMode = RemoteInputMode.POINTER
        set(value) {
            if (field == value) return
            endLeftDrag()
            resetGesture()
            field = value
            if (value == RemoteInputMode.POINTER && !mouseKnown) centerMouse()
            requestDraw()
        }

    /** 长按阈值（进入拖动 / 右键） */
    private val longPressMs = 420L
    /** 判定为"移动"而不是"点击"的位移 */
    private val tapSlopPx = 18f
    /** 双指轻点（右键）的最长持续时间 */
    private val twoFingerTapMs = 320L

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val bgPaint = Paint().apply { color = Color.BLACK }

    @Volatile private var running = false
    private var renderThread: Thread? = null

    private val lock = Object()
    private var needDraw = false

    // 视图变换：scale + 平移
    @Volatile private var scale = 1f
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    private var fitScale = 1f

    // 鼠标状态（远端坐标）。渲染线程会读，必须可见。
    @Volatile private var mouseX = 0
    @Volatile private var mouseY = 0

    // 远端光标位图（仅 RDP 由服务端下发）
    @Volatile private var cursorBitmap: Bitmap? = null
    @Volatile private var cursorHotX = 0
    @Volatile private var cursorHotY = 0
    // 服务端把光标切回"系统默认"时不会给位图，需要自己画一个箭头；
    // OnPointerSetNull 则是明确隐藏（例如全屏视频），两者不能混为一谈。
    @Volatile private var showDefaultCursor = true
    @Volatile private var cursorVisible = true
    @Volatile private var mouseKnown = false

    private val defaultCursorPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 22f)
        lineTo(5.5f, 17f)
        lineTo(9.5f, 26f)
        lineTo(13f, 24.5f)
        lineTo(9.5f, 16f)
        lineTo(17f, 16f)
        close()
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = Color.BLACK
    }

    // 单指手势
    private var pressX = 0f
    private var pressY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var longPressFired = false
    private var leftButtonHeld = false
    // 触控板模式下累计不足 1 个远端像素的位移，避免高缩放时丢步
    private var accX = 0f
    private var accY = 0f

    // 双指手势
    private var pinching = false
    private var twoFingerMode = 0 // 0=未定，1=缩放，2=滚轮/平移
    private var twoFingerTapCandidate = false
    private var twoFingerStartTime = 0L
    private var twoFingerStartX = 0f
    private var twoFingerStartY = 0f
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f
    private var zoomRemoteX = 0f
    private var zoomRemoteY = 0f
    private var panLastX = 0f
    private var panLastY = 0f
    private var lastWheelY = 0f
    // 双指松开一根后，剩下那根不再触发点击/移动，直到全部抬起
    private var ignoreSingle = false

    /** 物理鼠标当前按住的键（统一位掩码） */
    private var mouseButtonDown = 0

    private val handler = Handler(Looper.getMainLooper())

    private val longPress = Runnable {
        if (moved || pinching || ignoreSingle) return@Runnable
        val s = session ?: return@Runnable
        longPressFired = true
        if (inputMode == RemoteInputMode.TOUCH) {
            // 触控模式：长按 = 右键
            mouseKnown = true
            s.pointerButton(mouseX, mouseY, RemoteButtons.RIGHT, true)
            s.pointerButton(mouseX, mouseY, RemoteButtons.RIGHT, false)
        } else {
            // 指针模式：长按后移动 = 按住左键拖拽（向日葵/触控板的习惯）
            beginLeftDragAt(mouseX, mouseY)
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --------------------------------------------------------- Surface 生命周期

    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        renderThread = Thread({ renderLoop() }, "remote-render").also { it.start() }
        requestDraw()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        recomputeFit()
        requestDraw()
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        renderThread?.join(1500)
        renderThread = null
    }

    // ------------------------------------------------------------- 渲染

    private fun requestDraw() {
        synchronized(lock) { needDraw = true; (lock as Object).notifyAll() }
    }

    private fun renderLoop() {
        while (running) {
            // 等到超时也只是为了周期性检查 running；没有新帧就不要重绘。
            // 以前这里会无条件画一遍，静止时白白跑 4~5 次全屏缩放绘制。
            // 注意：判断结果要带出 synchronized 块，不能在里面 continue
            // （inline lambda 里的 break/continue 在 Kotlin 里是实验特性）。
            val shouldDraw = synchronized(lock) {
                if (!needDraw) {
                    try {
                        lock.wait(250)
                    } catch (_: InterruptedException) {
                    }
                }
                if (!needDraw) {
                    false
                } else {
                    needDraw = false
                    true
                }
            }
            if (!shouldDraw) continue
            val bmp = session?.frameBitmap() ?: continue
            if (bmp.isRecycled) continue
            val h = holder
            if (!h.surface.isValid) continue
            var canvas: Canvas? = null
            try {
                canvas = h.lockCanvas() ?: continue
                drawFrame(canvas, bmp)
            } catch (_: Throwable) {
            } finally {
                if (canvas != null) {
                    try {
                        h.unlockCanvasAndPost(canvas)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun drawFrame(canvas: Canvas, bmp: Bitmap) {
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint)
        val sw = bmp.width
        val sh = bmp.height
        if (sw <= 0 || sh <= 0) return
        val s = scale
        val dw = sw * s
        val dh = sh * s
        // 水平居中 + 用户平移；垂直方向**贴顶**：
        // 竖屏时 16:9 的远端桌面上下都会留黑边，居中会让人觉得画面浮在屏幕中间，
        // 贴顶后桌面紧贴最上方，多余的黑边都落在下方被控制台盖住。
        val left = (canvas.width - dw) / 2f + panX
        val top = panY
        canvas.drawBitmap(
            bmp, Rect(0, 0, sw, sh), Rect(
                left.roundToInt(), top.roundToInt(),
                (left + dw).roundToInt(), (top + dh).roundToInt(),
            ), paint,
        )

        // 远端光标叠加。RDP 走 setRemoteCursor 回调下发形状，
        // 最终落到「用一张位图 + 热点在本地画」。
        if (session?.needsLocalCursor != true || !cursorVisible) return
        val shape = session?.remoteCursor()
        val cur = shape?.bitmap ?: cursorBitmap
        val hotX = shape?.hotX ?: cursorHotX
        val hotY = shape?.hotY ?: cursorHotY
        if (cur != null && !cur.isRecycled) {
            val cx = left + (mouseX - hotX) * s
            val cy = top + (mouseY - hotY) * s
            canvas.drawBitmap(
                cur, null, Rect(
                    cx.roundToInt(), cy.roundToInt(),
                    (cx + cur.width * s).roundToInt(),
                    (cy + cur.height * s).roundToInt(),
                ), paint,
            )
        } else if (showDefaultCursor && mouseKnown) {
            canvas.save()
            canvas.translate(left + mouseX * s, top + mouseY * s)
            canvas.scale(s, s)
            canvas.drawPath(defaultCursorPath, cursorFill)
            canvas.drawPath(defaultCursorPath, cursorStroke)
            canvas.restore()
        }
    }

    fun setRemoteCursor(pixels: IntArray, width: Int, height: Int, hotX: Int, hotY: Int) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        cursorBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        cursorHotX = hotX.coerceIn(0, width - 1)
        cursorHotY = hotY.coerceIn(0, height - 1)
        showDefaultCursor = false
        cursorVisible = true
        requestDraw()
    }

    fun setDefaultCursor() {
        cursorBitmap = null
        showDefaultCursor = true
        cursorVisible = true
        requestDraw()
    }

    fun hideRemoteCursor() {
        cursorBitmap = null
        showDefaultCursor = false
        cursorVisible = false
        requestDraw()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.actionLabel = null
        outAttrs.inputType = android.text.InputType.TYPE_NULL
        // 和官方 aFreeRDP 一致：输入法不要弹出全屏编辑框，也不要被聊天式界面接管。
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        // false = 不是 full editor，输入法只负责产生文本/按键事件，状态由远端维护。
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null) return true
                val s = session ?: return true
                var i = 0
                while (i < text.length) {
                    val cp = Character.codePointAt(text, i)
                    i += Character.charCount(cp)
                    // 由各协议实现判断服务端是否支持 Unicode：不支持时绝不能发
                    // Unicode 事件（RDP 的 native 会把发送失败当断线处理）。
                    s.typeChar(cp)
                }
                return true
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                repeat(leftLength.coerceIn(0, 256)) { sendKeyTap(KeyEvent.KEYCODE_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                // 部分输入法（尤其英文键盘）不发 commitText，而是直接发按键事件。
                if (event.action == KeyEvent.ACTION_DOWN) sendKeyTap(event.keyCode)
                return true
            }
        }
    }

    private fun sendKeyTap(keyCode: Int) {
        val s = session ?: return
        if (s.keyDown(keyCode)) s.keyUp(keyCode)
    }

    fun onFrame() = requestDraw()

    fun onResize() {
        recomputeFit()
        requestDraw()
    }

    private fun recomputeFit() {
        val s = session ?: return
        val bw = s.frameWidth
        val bh = s.frameHeight
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return
        fitScale = minOf(vw / bw, vh / bh)
        scale = fitScale.coerceIn(0.05f, 8f)
        panX = 0f
        panY = 0f
        if (mouseKnown) {
            // 分辨率变化后旧坐标可能已越界
            mouseX = mouseX.coerceIn(0, bw - 1)
            mouseY = mouseY.coerceIn(0, bh - 1)
        } else {
            // 指针模式默认把光标放在桌面中心，避免第一次点按落在左上角。
            mouseX = bw / 2
            mouseY = bh / 2
            mouseKnown = true
        }
    }

    fun zoomTo(s: Float) {
        scale = s.coerceIn(fitScale * 0.5f, fitScale * 6f)
        clampPan()
        requestDraw()
    }

    fun resetZoom() {
        recomputeFit()
        requestDraw()
    }

    // ------------------------------------------------------------- 坐标换算

    private fun frameSize(): Pair<Int, Int> {
        val s = session ?: return 0 to 0
        return s.frameWidth to s.frameHeight
    }

    private fun toRemoteFloat(vx: Float, vy: Float): Pair<Float, Float> {
        val (bw, _) = frameSize()
        if (bw <= 0) return 0f to 0f
        val s = scale
        if (s <= 0f) return 0f to 0f
        val left = (width - bw * s) / 2f + panX
        // 与 drawFrame 保持一致：垂直贴顶
        val top = panY
        return (vx - left) / s to (vy - top) / s
    }

    private fun toRemote(vx: Float, vy: Float): Pair<Int, Int> {
        val (bw, bh) = frameSize()
        if (bw <= 0 || bh <= 0) return 0 to 0
        val (fx, fy) = toRemoteFloat(vx, vy)
        return fx.roundToInt().coerceIn(0, bw - 1) to
            fy.roundToInt().coerceIn(0, bh - 1)
    }

    /** 放大/缩小时把远端点 [remoteX]/[remoteY] 固定在屏幕点 [focusVx]/[focusVy] 下。 */
    private fun applyScale(
        newScale: Float,
        focusVx: Float,
        focusVy: Float,
        remoteX: Float,
        remoteY: Float,
    ) {
        val (bw, _) = frameSize()
        if (bw <= 0) return
        val ns = newScale.coerceIn(fitScale * 0.5f, fitScale * 6f)
        scale = ns
        val left = focusVx - remoteX * ns
        val top = focusVy - remoteY * ns
        panX = left - (width - bw * ns) / 2f
        // 垂直基准是「贴顶」（top = 0），所以 panY 直接等于算出来的 top
        panY = top
        clampPan()
        requestDraw()
    }

    private fun clampPan() {
        val (bw, bh) = frameSize()
        if (bw <= 0 || bh <= 0) return
        val overX = max(0f, bw * scale - width)
        val overY = max(0f, bh * scale - height)
        panX = panX.coerceIn(-overX / 2f, overX / 2f)
        // 垂直贴顶：放大后只能往上拖去看下半部分，不允许拖出上方的黑边
        panY = panY.coerceIn(-overY, 0f)
    }

    private fun centerMouse() {
        val (bw, bh) = frameSize()
        if (bw <= 0 || bh <= 0) return
        mouseX = bw / 2
        mouseY = bh / 2
        mouseKnown = true
    }

    // ------------------------------------------------------------- 鼠标事件

    private fun moveMouseTo(rx: Int, ry: Int) {
        val s = session ?: return
        mouseX = rx
        mouseY = ry
        mouseKnown = true
        // 按住的键由 Session 自己记着，这里只报位置；本地重绘是为了让光标动起来
        // （桌面画面可能完全静止，不重绘的话本地光标会看起来卡住）。
        s.pointerMove(rx, ry)
        requestDraw()
    }

    private fun leftClick(rx: Int, ry: Int) {
        val s = session ?: return
        endLeftDrag()
        mouseX = rx
        mouseY = ry
        mouseKnown = true
        s.pointerMove(rx, ry)
        s.pointerButton(rx, ry, RemoteButtons.LEFT, true)
        s.pointerButton(rx, ry, RemoteButtons.LEFT, false)
        requestDraw()
    }

    fun sendRightClick() {
        val s = session ?: return
        endLeftDrag()
        mouseKnown = true
        s.pointerMove(mouseX, mouseY)
        s.pointerButton(mouseX, mouseY, RemoteButtons.RIGHT, true)
        s.pointerButton(mouseX, mouseY, RemoteButtons.RIGHT, false)
        requestDraw()
    }

    private fun sendTwoFingerRightClick() {
        val s = session ?: return
        endLeftDrag()
        mouseKnown = true
        s.pointerMove(mouseX, mouseY)
        s.pointerButton(mouseX, mouseY, RemoteButtons.RIGHT, true)
        s.pointerButton(mouseX, mouseY, RemoteButtons.RIGHT, false)
        requestDraw()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** 在指定远端坐标按下左键，之后的移动会带着"左键按住"状态。 */
    private fun beginLeftDragAt(rx: Int, ry: Int) {
        val s = session ?: return
        if (leftButtonHeld) return
        mouseX = rx
        mouseY = ry
        mouseKnown = true
        s.pointerButton(rx, ry, RemoteButtons.LEFT, true)
        leftButtonHeld = true
        requestDraw()
    }

    private fun endLeftDrag() {
        if (!leftButtonHeld) return
        leftButtonHeld = false
        session?.pointerButton(mouseX, mouseY, RemoteButtons.LEFT, false)
        requestDraw()
    }

    // ------------------------------------------- 底部独立触摸板（UI 面板调用）
    //
    // 画面被收进屏幕上半部分后，下半部分是独立的触摸板控件，它的坐标和画面
    // 完全没有关系（相对移动，不映射到桌面位置），所以不能复用 onTouchEvent
    // 里那套「屏幕坐标 → 远端坐标」的换算，只能单独走相对位移。

    /**
     * 触摸板灵敏度：手指在面板上移动 1dp 对应远端多少像素。
     *
     * 面板宽约 300dp，2.4 的倍率下横扫一次约覆盖 720 远端像素（1920 桌面的一半
     * 以上），既不用反复抬手，也不至于点不准小按钮。
     */
    @Volatile
    var touchpadGain = 2.4f

    /**
     * 指针速度档位（0.6 / 1.0 / 1.6），乘在 [touchpadGain] 上。
     * 4K 远端桌面和小分辨率屏的最佳灵敏度差很多，写死一个值总有人嫌飘或嫌慢。
     */
    var pointerSpeed = 1f

    // 累计不足一个远端像素的位移，否则高倍率下慢速移动会完全丢步
    private var padAccX = 0f
    private var padAccY = 0f

    /** 相对移动光标；dx/dy 是触摸板上的像素位移，方向与手指一致。 */
    fun movePointerBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val (bw, bh) = frameSize()
        if (bw <= 0 || bh <= 0) return
        val density = resources.displayMetrics.density.let { if (it > 0f) it else 1f }
        // 把「本地像素」先折算成 dp，再乘灵敏度（含速度档位）得到远端像素
        val perPx = touchpadGain * pointerSpeed / density
        padAccX += dx * perPx
        padAccY += dy * perPx
        val stepX = padAccX.roundToInt()
        val stepY = padAccY.roundToInt()
        if (stepX == 0 && stepY == 0) return
        padAccX -= stepX
        padAccY -= stepY
        val nx = (mouseX + stepX).coerceIn(0, bw - 1)
        val ny = (mouseY + stepY).coerceIn(0, bh - 1)
        // 顶到边界时不要再发事件，避免每帧都往网络里塞一条无效移动
        if (nx == mouseX && ny == mouseY) return
        moveMouseTo(nx, ny)
    }

    /** 触摸板左键单击（位置是当前光标位置，不是手指落点） */
    fun clickLeft() {
        if (!mouseKnown) centerMouse()
        leftClick(mouseX, mouseY)
    }

    /** 触摸板右键单击 */
    fun clickRight() {
        if (!mouseKnown) centerMouse()
        sendRightClick()
    }

    /** 中键单击：Windows 里常用于「在新标签页打开链接」、中键粘贴 */
    fun clickMiddle() {
        val s = session ?: return
        endLeftDrag()
        if (!mouseKnown) centerMouse()
        s.pointerMove(mouseX, mouseY)
        s.pointerButton(mouseX, mouseY, RemoteButtons.MIDDLE, true)
        s.pointerButton(mouseX, mouseY, RemoteButtons.MIDDLE, false)
        requestDraw()
    }

    /** 按住左键不放（配合触摸板移动做拖拽、框选） */
    fun holdLeftButton() {
        if (!mouseKnown) centerMouse()
        beginLeftDragAt(mouseX, mouseY)
    }

    /** 松开左键 */
    fun releaseLeftButton() = endLeftDrag()

    /** 滚轮；down = true 表示向下滚（和双指下滑一致） */
    fun scrollBy(ticks: Int, down: Boolean) {
        val s = session ?: return
        if (!mouseKnown) centerMouse()
        s.pointerScroll(mouseX, mouseY, ticks, down)
    }

    // ------------------------------------------------------------- 触摸手势

    private fun isMouseEvent(e: MotionEvent): Boolean =
        e.isFromSource(InputDevice.SOURCE_MOUSE) ||
            (e.pointerCount > 0 && e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

    private fun centroidX(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getX(i)
        return if (e.pointerCount > 0) sum / e.pointerCount else 0f
    }

    private fun centroidY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return if (e.pointerCount > 0) sum / e.pointerCount else 0f
    }

    private fun fingerDistance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun twoFingerMoved(e: MotionEvent): Boolean {
        if (e.pointerCount < 2) return false
        val dx = centroidX(e) - twoFingerStartX
        val dy = centroidY(e) - twoFingerStartY
        return hypot(dx, dy) > tapSlopPx ||
            abs(fingerDistance(e) - pinchStartDist) > 24f
    }

    private fun resetGesture() {
        handler.removeCallbacks(longPress)
        pinching = false
        twoFingerMode = 0
        twoFingerTapCandidate = false
        ignoreSingle = false
        moved = false
        longPressFired = false
        accX = 0f
        accY = 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 外接鼠标/触控板：绝对坐标 + 真实按键，不走手机手势
        if (isMouseEvent(event)) return handleMouseEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                endLeftDrag()
                pressX = event.x
                pressY = event.y
                downX = event.x
                downY = event.y
                downTime = SystemClock.uptimeMillis()
                moved = false
                longPressFired = false
                pinching = false
                twoFingerMode = 0
                twoFingerTapCandidate = false
                ignoreSingle = false
                accX = 0f
                accY = 0f
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, longPressMs)
                if (inputMode == RemoteInputMode.TOUCH) {
                    val (rx, ry) = toRemote(event.x, event.y)
                    moveMouseTo(rx, ry)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                // 单指拖拽中又落下第二根手指：先松开左键，避免"粘键"
                endLeftDrag()
                if (!pinching) {
                    pinching = true
                    twoFingerMode = 0
                    twoFingerTapCandidate = true
                    twoFingerStartTime = SystemClock.uptimeMillis()
                    twoFingerStartX = centroidX(event)
                    twoFingerStartY = centroidY(event)
                    pinchStartDist = fingerDistance(event)
                    pinchStartScale = scale
                    panLastX = twoFingerStartX
                    panLastY = twoFingerStartY
                    lastWheelY = twoFingerStartY
                    val (rx, ry) = toRemoteFloat(twoFingerStartX, twoFingerStartY)
                    zoomRemoteX = rx
                    zoomRemoteY = ry
                } else {
                    // 第三根手指：不再当作轻点
                    twoFingerTapCandidate = false
                }
                moved = true
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    pinching -> handleTwoFingerMove(event)
                    ignoreSingle -> Unit
                    else -> handleSingleMove(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pinching) {
                    if (twoFingerTapCandidate &&
                        SystemClock.uptimeMillis() - twoFingerStartTime <= twoFingerTapMs &&
                        !twoFingerMoved(event)
                    ) {
                        if (inputMode == RemoteInputMode.TOUCH) {
                            val (rx, ry) = toRemote(centroidX(event), centroidY(event))
                            mouseX = rx
                            mouseY = ry
                            mouseKnown = true
                        }
                        sendTwoFingerRightClick()
                    }
                    handler.removeCallbacks(longPress)
                    pinching = false
                    twoFingerMode = 0
                    twoFingerTapCandidate = false
                    // 剩下的那根手指只负责抬起，不再触发点击或移动
                    ignoreSingle = true
                    moved = true
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (pinching) {
                    if (twoFingerTapCandidate &&
                        SystemClock.uptimeMillis() - twoFingerStartTime <= twoFingerTapMs &&
                        !twoFingerMoved(event)
                    ) {
                        if (inputMode == RemoteInputMode.TOUCH) {
                            val (rx, ry) = toRemote(centroidX(event), centroidY(event))
                            mouseX = rx
                            mouseY = ry
                            mouseKnown = true
                        }
                        sendTwoFingerRightClick()
                    }
                    resetGesture()
                } else if (ignoreSingle) {
                    resetGesture()
                } else if (leftButtonHeld) {
                    // 用抬起点收尾，避免最后一次 MOVE 没到、拖动终点差几像素
                    if (inputMode == RemoteInputMode.TOUCH) {
                        val (rx, ry) = toRemote(event.x, event.y)
                        mouseX = rx
                        mouseY = ry
                        mouseKnown = true
                    }
                    endLeftDrag()
                    resetGesture()
                } else {
                    val dt = SystemClock.uptimeMillis() - downTime
                    if (!moved && !longPressFired && dt < longPressMs) {
                        if (inputMode == RemoteInputMode.POINTER) {
                            leftClick(mouseX, mouseY)
                        } else {
                            val (rx, ry) = toRemote(event.x, event.y)
                            leftClick(rx, ry)
                        }
                    }
                    resetGesture()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                endLeftDrag()
                resetGesture()
            }
        }
        return true
    }

    private fun handleSingleMove(event: MotionEvent) {
        // 用"离按下点的总位移"判断点击/拖动；不能只看相邻两次事件的增量，
        // 否则慢慢滑很远也会被当成一次点击。
        if (hypot(event.x - pressX, event.y - pressY) > tapSlopPx) {
            moved = true
            handler.removeCallbacks(longPress)
        }

        if (inputMode == RemoteInputMode.POINTER) {
            // 相对移动：按缩放比例换算成远端像素，并保留不足一像素的余量
            accX += (event.x - downX) / scale
            accY += (event.y - downY) / scale
            downX = event.x
            downY = event.y
            val stepX = accX.roundToInt()
            val stepY = accY.roundToInt()
            if (stepX != 0 || stepY != 0) {
                val (bw, bh) = frameSize()
                val nx = (mouseX + stepX).coerceIn(0, (bw - 1).coerceAtLeast(0))
                val ny = (mouseY + stepY).coerceIn(0, (bh - 1).coerceAtLeast(0))
                if (nx == mouseX) accX = 0f else accX -= stepX
                if (ny == mouseY) accY = 0f else accY -= stepY
                moveMouseTo(nx, ny)
            }
        } else {
            val (rx, ry) = toRemote(event.x, event.y)
            // 触控模式的长按已经发过右键，手指继续移动不能再按左键拖拽，
            // 否则远端会收到「右键 + 左键拖动」的混合事件。指针模式的长按
            // 本身就是进入拖拽，leftButtonHeld 已经为 true，不走这里。
            if (moved && !leftButtonHeld && !longPressFired) beginLeftDragAt(rx, ry)
            moveMouseTo(rx, ry)
        }
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val cx = centroidX(event)
        val cy = centroidY(event)
        val dist = fingerDistance(event)

        if (twoFingerTapCandidate && twoFingerMoved(event)) {
            twoFingerTapCandidate = false
        }

        if (twoFingerMode == 0) {
            val distDelta = abs(dist - pinchStartDist)
            val moveDelta = max(abs(cx - twoFingerStartX), abs(cy - twoFingerStartY))
            if (distDelta > 24f || moveDelta > 24f) {
                twoFingerMode = if (distDelta >= moveDelta) 1 else 2
                panLastX = cx
                panLastY = cy
                lastWheelY = cy
                if (twoFingerMode == 1) {
                    // 以开始缩放时的焦点为锚，避免画面突然跳走
                    val (rx, ry) = toRemoteFloat(cx, cy)
                    zoomRemoteX = rx
                    zoomRemoteY = ry
                }
            }
        }

        when (twoFingerMode) {
            1 -> {
                if (pinchStartDist > 10f) {
                    applyScale(
                        pinchStartScale * (dist / pinchStartDist),
                        cx, cy, zoomRemoteX, zoomRemoteY,
                    )
                }
            }

            2 -> {
                if (scale > fitScale * 1.02f) {
                    // 已放大：双指拖动 = 平移画面（否则放大后看不到边缘）
                    panX += cx - panLastX
                    panY += cy - panLastY
                    panLastX = cx
                    panLastY = cy
                    clampPan()
                    requestDraw()
                } else {
                    // 适应屏幕：双指上下滑 = 滚轮
                    val dy = cy - lastWheelY
                    if (abs(dy) > 40f) {
                        scrollBy(abs(dy / 40f).toInt().coerceIn(1, 3), down = dy > 0)
                        lastWheelY = cy
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- 物理鼠标

    private fun sendMouseDown(rx: Int, ry: Int, button: Int) {
        val s = session ?: return
        if (mouseButtonDown and button != 0) return
        mouseButtonDown = mouseButtonDown or button
        s.pointerButton(rx, ry, button, true)
    }

    private fun releaseMouseButtons(rx: Int, ry: Int) {
        val s = session ?: return
        val held = mouseButtonDown
        if (held == 0) return
        mouseButtonDown = 0
        if (held and RemoteButtons.LEFT != 0) s.pointerButton(rx, ry, RemoteButtons.LEFT, false)
        if (held and RemoteButtons.RIGHT != 0) s.pointerButton(rx, ry, RemoteButtons.RIGHT, false)
        if (held and RemoteButtons.MIDDLE != 0) s.pointerButton(rx, ry, RemoteButtons.MIDDLE, false)
        requestDraw()
    }

    private fun buttonOf(event: MotionEvent): Int {
        val b = event.buttonState
        return when {
            b and MotionEvent.BUTTON_SECONDARY != 0 -> RemoteButtons.RIGHT
            b and MotionEvent.BUTTON_TERTIARY != 0 -> RemoteButtons.MIDDLE
            else -> RemoteButtons.LEFT
        }
    }

    private fun handleMouseEvent(event: MotionEvent): Boolean {
        val (rx, ry) = toRemote(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 上一次 UP 丢失时先补一个抬起，避免远端一直按着某个键
                if (mouseButtonDown != 0) releaseMouseButtons(mouseX, mouseY)
                moveMouseTo(rx, ry)
                sendMouseDown(rx, ry, buttonOf(event))
            }

            MotionEvent.ACTION_MOVE -> moveMouseTo(rx, ry)

            MotionEvent.ACTION_UP -> {
                mouseX = rx
                mouseY = ry
                mouseKnown = true
                releaseMouseButtons(rx, ry)
            }

            MotionEvent.ACTION_BUTTON_PRESS -> {
                mouseX = rx
                mouseY = ry
                mouseKnown = true
                sendMouseDown(rx, ry, buttonOf(event))
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                mouseX = rx
                mouseY = ry
                mouseKnown = true
                releaseMouseButtons(rx, ry)
            }

            MotionEvent.ACTION_CANCEL -> releaseMouseButtons(rx, ry)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        endLeftDrag()
        if (mouseButtonDown != 0) releaseMouseButtons(mouseX, mouseY)
        cursorBitmap = null
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    /** 物理鼠标/触控板：移动、滚轮与按键 */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isMouseEvent(event)) return super.onGenericMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                val (rx, ry) = toRemote(event.x, event.y)
                moveMouseTo(rx, ry)
                return true
            }

            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) {
                    scrollBy(abs(v).toInt().coerceAtLeast(1), down = v < 0)
                    return true
                }
            }

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            -> {
                handleMouseEvent(event)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
