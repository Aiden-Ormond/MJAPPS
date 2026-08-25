package com.mikun.mjapps.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot

/**
 * 常驻悬浮窗：显示 assets/mj.png，可拖动并自动吸附屏幕左右边缘。
 *
 * 触发方式由 [mode] 决定（App 内可选择）：
 * - [MjWindowMode.DIRECT] 直接触发：轻点即随机播放。
 * - [MjWindowMode.CODE]    暗号触发：轻点弹出本地按钮键盘，输入正确暗号后播放
 *                         （不依赖系统输入法，适配 MIUI 等限制 overlay 键盘的 ROM）。
 *
 * 旋转方向随吸附边自动变化：左侧 -90°，右侧 +90°。
 * 需要 SYSTEM_ALERT_WINDOW 权限（调用方负责检查/申请）。
 */
class MjImageFloatingWindow(
    context: Context,
    private val onTriggerPlay: () -> Unit,
) {

    /** 点击触发方式（App 内设置） */
    var mode: MjWindowMode = MjWindowMode.DIRECT

    private val appContext = context.applicationContext
    private val density = appContext.resources.displayMetrics.density
    private val screenW = appContext.resources.displayMetrics.widthPixels
    private val screenH = appContext.resources.displayMetrics.heightPixels

    private val iconSize = (72 * density).toInt()
    private val panelW = (260 * density).toInt()
    private val panelH = (340 * density).toInt()
    private val edgeGap = 0 // 常驻图标紧贴屏幕边缘，无间隙
    private val panelMargin = (8 * density).toInt() // 暗号键盘保留少量内边距
    private val tapSlop = (8 * density).toInt()

    private val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var iconFrame: FrameLayout? = null
    private var iconView: ImageView? = null
    private var keypad: LinearLayout? = null
    private var keypadDisplay: TextView? = null

    private var curX = (screenW - iconSize - edgeGap).coerceAtLeast(edgeGap)
    private var curY = ((screenH * 0.35f).toInt()).coerceIn(edgeGap, (screenH - iconSize - edgeGap).coerceAtLeast(edgeGap))
    private var savedX = curX
    private var savedY = curY

    private var added = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private val typed = StringBuilder()
    private val acceptedCodes = listOf("MJ", "蜘蛛侠")
    private val codeChars: List<String> by lazy {
        val set = LinkedHashSet<String>()
        acceptedCodes.forEach { code -> code.forEach { ch -> set.add(ch.toString()) } }
        // 混入干扰字符，避免按钮直接暴露答案
        val decoys = listOf("米", "网", "神", "精")
        val all = (set + decoys).toMutableList()
        all.shuffle()
        all
    }

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible

    private val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    // 常驻图标无需焦点（用本地按钮键盘，不弹系统输入法）
    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    fun show() {
        if (added) dismissView()
        val r = buildRoot()
        root = r
        val isLeft = (curX + iconSize / 2) < (screenW / 2)
        iconView?.rotation = if (isLeft) -90f else 90f
        val params = makeParams(iconSize, iconSize, curX, curY, baseFlags)
        wm.addView(r, params)
        added = true
        _visible.value = true
    }

    fun hide() {
        dismissView()
        _visible.value = false
    }

    fun release() {
        dismissView()
    }

    private fun dismissView() {
        try {
            root?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        root = null
        iconFrame = null
        iconView = null
        keypad = null
        keypadDisplay = null
        added = false
    }

    private fun makeParams(w: Int, h: Int, x: Int, y: Int, flags: Int) =
        WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun buildRoot(): FrameLayout {
        val ctx = appContext
        val frame = FrameLayout(ctx)

        // 图标容器：无边框（仅显示 mj.png 本身）
        val icon = FrameLayout(ctx)
        val iv = ImageView(ctx).apply {
            val bmp = loadBitmap()
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(
                (6 * density).toInt(),
                (6 * density).toInt(),
                (6 * density).toInt(),
                (6 * density).toInt(),
            )
        }
        icon.addView(
            iv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        iconFrame = icon
        iconView = iv

        // 暗号键盘（默认隐藏）
        keypad = buildKeypad(ctx)

        frame.addView(
            icon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        frame.addView(
            keypad,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        icon.setOnTouchListener { _, ev -> onIconTouch(ev) }
        return frame
    }

    private fun buildKeypad(ctx: Context): LinearLayout {
        val lp = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
            )
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 18 * density
                setColor(0xF2FFFFFF.toInt())
                setStroke((1.5 * density).toInt(), 0xFFBBBBBB.toInt())
            }
            background = bg
            visibility = View.GONE
        }
        val title = TextView(ctx).apply {
            text = "请输入暗号"
            textSize = 16f
            setTextColor(0xFF222222.toInt())
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val display = TextView(ctx).apply {
            textSize = 20f
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (10 * density).toInt(),
            )
            minHeight = (44 * density).toInt()
            gravity = android.view.Gravity.CENTER
        }
        keypadDisplay = display

        // 字符按钮网格（每行 4 个）
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val perRow = 4
        codeChars.chunked(perRow).forEach { rowChars ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = rowChars.size.toFloat()
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            rowChars.forEach { ch ->
                val b = Button(ctx).apply {
                    text = ch
                    textSize = 20f
                    setOnClickListener { appendChar(ch) }
                    val lpB = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f)
                    lpB.setMargins(
                        (4 * density).toInt(),
                        0,
                        (4 * density).toInt(),
                        0,
                    )
                    layoutParams = lpB
                }
                row.addView(b)
            }
            grid.addView(row)
        }

        // 操作行：删除 / 确认
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val del = Button(ctx).apply {
            text = "删除"
            setOnClickListener { removeLast() }
            val lpD = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lpD.setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            layoutParams = lpD
        }
        val ok = Button(ctx).apply {
            text = "确认"
            setOnClickListener { onKeypadConfirm() }
            val lpO = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lpO.setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            layoutParams = lpO
        }
        row.addView(del)
        row.addView(ok)

        lp.addView(title)
        lp.addView(display)
        lp.addView(grid)
        lp.addView(row)
        return lp
    }

    private fun appendChar(ch: String) {
        if (typed.length >= 8) return
        typed.append(ch)
        keypadDisplay?.text = typed.toString()
    }

    private fun removeLast() {
        if (typed.isNotEmpty()) typed.deleteAt(typed.length - 1)
        keypadDisplay?.text = typed.toString()
    }

    private fun onIconTouch(ev: MotionEvent): Boolean {
        return when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = curX
                startY = curY
                moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!moved && hypot(dx, dy) > tapSlop) moved = true
                if (moved) {
                    curX = (startX + dx).toInt()
                        .coerceIn(edgeGap, (screenW - iconSize - edgeGap).coerceAtLeast(edgeGap))
                    curY = (startY + dy).toInt()
                        .coerceIn(edgeGap, (screenH - iconSize - edgeGap).coerceAtLeast(edgeGap))
                    val p = root?.layoutParams as? WindowManager.LayoutParams
                    if (p != null) {
                        p.x = curX
                        p.y = curY
                        wm.updateViewLayout(root, p)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) {
                    snapToEdge()
                } else {
                    onTap()
                }
                true
            }
            else -> false
        }
    }

    private fun onTap() {
        if (mode == MjWindowMode.DIRECT) {
            onTriggerPlay()
        } else {
            openKeypad()
        }
    }

    private fun snapToEdge() {
        val left = (curX + iconSize / 2) < (screenW / 2)
        val target = if (left) {
            edgeGap
        } else {
            (screenW - iconSize - edgeGap).coerceAtLeast(edgeGap)
        }
        // 旋转方向随吸附边变化：左 -90°，右 +90°
        iconView?.rotation = if (left) -90f else 90f
        animateX(target)
    }

    private fun animateX(target: Int) {
        val start = curX
        if (start == target) return
        val anim = android.animation.ValueAnimator.ofInt(start, target)
        anim.duration = 180
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener {
            curX = it.animatedValue as Int
            val p = root?.layoutParams as? WindowManager.LayoutParams
            if (p != null) {
                p.x = curX
                wm.updateViewLayout(root, p)
            }
        }
        anim.start()
    }

    private fun openKeypad() {
        savedX = curX
        savedY = curY
        val px = savedX.coerceIn(panelMargin, (screenW - panelW - panelMargin).coerceAtLeast(panelMargin))
        val py = savedY.coerceIn(panelMargin, (screenH - panelH - panelMargin).coerceAtLeast(panelMargin))
        val p = root?.layoutParams as? WindowManager.LayoutParams ?: return
        p.width = panelW
        p.height = panelH
        p.x = px
        p.y = py
        wm.updateViewLayout(root, p)
        iconFrame?.visibility = View.GONE
        keypad?.visibility = View.VISIBLE
        typed.clear()
        keypadDisplay?.text = ""
    }

    private fun closeKeypad() {
        val p = root?.layoutParams as? WindowManager.LayoutParams ?: return
        p.width = iconSize
        p.height = iconSize
        p.x = savedX
        p.y = savedY
        wm.updateViewLayout(root, p)
        curX = savedX
        curY = savedY
        keypad?.visibility = View.GONE
        iconFrame?.visibility = View.VISIBLE
        // 恢复图标旋转方向（按当前吸附边）
        val left = (curX + iconSize / 2) < (screenW / 2)
        iconView?.rotation = if (left) -90f else 90f
    }

    private fun onKeypadConfirm() {
        val txt = typed.toString().trim()
        if (acceptedCodes.any { it.equals(txt, ignoreCase = true) }) {
            closeKeypad()
            onTriggerPlay()
        } else {
            Toast.makeText(appContext, "暗号错误，请重试", Toast.LENGTH_SHORT).show()
            typed.clear()
            keypadDisplay?.text = ""
        }
    }

    private fun loadBitmap(): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.assets.open("mj.png").use { BitmapFactory.decodeStream(it, null, opts) }
            val tw = opts.outWidth.coerceAtLeast(1)
            val th = opts.outHeight.coerceAtLeast(1)
            val maxDim = 256
            var sample = 1
            while (tw / sample > maxDim || th / sample > maxDim) sample *= 2
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            appContext.assets.open("mj.png").use { BitmapFactory.decodeStream(it, null, opts2) }
        } catch (_: Exception) {
            null
        }
    }
}
