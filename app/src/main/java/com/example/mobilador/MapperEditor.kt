package com.example.mobilador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min

/** Editor de bolinhas por cima do jogo: arraste, toque para escolher a tecla, ajuste o tamanho. */
class MapperEditor(
    private val ctx: Context,
    private val wm: WindowManager,
    private val els: MutableList<El>,
    private val onDone: (Boolean) -> Unit
) {
    private val root = FrameLayout(ctx)
    private val panel = LinearLayout(ctx)
    private val board = Board()
    private var sel = -1
    private var waitSlot = -1
    private val lp = WLP(-1, -1, WLP.TYPE_APPLICATION_OVERLAY,
        WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)

    private fun dp(v: Int) = ctx.dp(v)

    private inner class Board : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = ctx.dp(13).toFloat()
        }
        private var dragging = false

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#99000000"))
            val m = min(width, height).toFloat()
            for (i in els.indices) {
                val e = els[i]
                val cx = e.x * width; val cy = e.y * height; val r = e.r * m
                val col = when (e.type) { T_JOY -> "#7722D3EE"; T_LOOK -> "#77FFB020"; else -> "#777C5CFF" }
                p.style = Paint.Style.FILL; p.color = Color.parseColor(col); c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = if (i == sel) 6f else 3f
                p.color = if (i == sel) Color.WHITE else Color.parseColor("#CCFFFFFF")
                c.drawCircle(cx, cy, r, p)
                val off = t.textSize / 3
                when (e.type) {
                    T_TAP -> c.drawText(bindName(e.bind), cx, cy + off, t)
                    T_LOOK -> c.drawText("Câmera", cx, cy + off, t)
                    else -> {
                        c.drawText(bindName(e.binds[0]), cx, cy - r + t.textSize * 1.1f, t)
                        c.drawText(bindName(e.binds[2]), cx, cy + r - t.textSize * 0.4f, t)
                        c.drawText(bindName(e.binds[1]), cx - r * 0.62f, cy + off, t)
                        c.drawText(bindName(e.binds[3]), cx + r * 0.62f, cy + off, t)
                    }
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val m = min(width, height).toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (waitSlot >= 0 && (e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                        val bit = listOf(1, 2, 4, 8, 16).firstOrNull { (e.buttonState and it) != 0 }
                        if (bit != null) { assign(10000 + bit); return true }
                    }
                    sel = els.indexOfLast { hypot(e.x - it.x * width, e.y - it.y * height) <= it.r * m + dp(8) }
                    waitSlot = -1
                    dragging = sel >= 0
                    refreshPanel(); invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging && sel >= 0) {
                        els[sel].x = (e.x / width).coerceIn(0.02f, 0.98f)
                        els[sel].y = (e.y / height).coerceIn(0.02f, 0.98f)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    private fun assign(code: Int) {
        val e = els.getOrNull(sel) ?: return
        if (waitSlot < 0) return
        if (e.type == T_JOY) e.binds[waitSlot] = code else e.bind = code
        waitSlot = -1
        refreshPanel(); board.invalidate()
    }

    private fun refreshPanel() {
        panel.removeAllViews()
        val e = els.getOrNull(sel)
        if (e == null) { panel.visibility = View.GONE; return }
        panel.visibility = View.VISIBLE

        fun row(vararg v: View) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (x in v) r.addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(6); topMargin = dp(6) })
            panel.addView(r)
        }
        fun waitBtn(slot: Int, name: String): View {
            val cur = if (e.type == T_JOY) e.binds[slot] else e.bind
            val waiting = waitSlot == slot
            return ctx.pill(if (waiting) "Aperte tecla / botão…" else "$name: ${bindName(cur)}",
                if (waiting) Pal.PRIMARY else Pal.CARD2) {
                waitSlot = if (waitSlot == slot) -1 else slot
                root.requestFocus(); refreshPanel()
            }.apply { textSize = 13f }
        }

        when (e.type) {
            T_JOY -> { row(waitBtn(0, "↑"), waitBtn(2, "↓")); row(waitBtn(1, "←"), waitBtn(3, "→")) }
            T_TAP -> row(waitBtn(0, "Tecla"),
                ctx.pill(if (e.mode == 0) "Modo: segurar" else "Modo: alternar") {
                    e.mode = 1 - e.mode; refreshPanel()
                }.apply { textSize = 13f })
            else -> row(TextView(ctx).apply {
                text = "Arraste para escolher onde a câmera gira"; setTextColor(Color.WHITE); textSize = 13f })
        }

        val tools = ArrayList<View>()
        tools.add(ctx.pill("−") { e.r = (e.r - 0.01f).coerceAtLeast(0.03f); board.invalidate() })
        tools.add(ctx.pill("+") { e.r = (e.r + 0.01f).coerceAtMost(0.2f); board.invalidate() })
        if (e.type != T_LOOK) tools.add(ctx.pill("Excluir", Pal.DANGER) {
            els.remove(e); sel = -1; waitSlot = -1; refreshPanel(); board.invalidate()
        })
        row(*tools.toTypedArray())
    }

    private fun add(type: Int) {
        if (els.size >= 28) return
        els.add(if (type == T_JOY) El(T_JOY, 0.30f, 0.50f, 0.10f) else El(T_TAP, 0.50f, 0.50f, 0.06f))
        sel = els.size - 1; waitSlot = -1
        refreshPanel(); board.invalidate()
    }

    fun show() {
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        root.addView(board, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(4), dp(8)) }
        fun top(label: String, col: Int = Pal.CARD2, a: () -> Unit) {
            bar.addView(ctx.pill(label, col, a).apply { textSize = 13f },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        top("+ Toque") { add(T_TAP) }
        top("+ Joystick") { add(T_JOY) }
        top("Salvar", Pal.OK) { finish(true) }
        top("Cancelar", Pal.DANGER) { finish(false) }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(8), dp(6), dp(10))
        panel.background = ctx.roundBg(Color.parseColor("#EE11131F"), 18)
        panel.visibility = View.GONE
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(12)) })

        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && waitSlot >= 0 && code != KeyEvent.KEYCODE_BACK) {
                assign(code); true
            } else false
        }
        root.setOnGenericMotionListener { _, ev ->
            if (waitSlot >= 0 && ev.actionMasked == MotionEvent.ACTION_SCROLL) {
                assign(if (ev.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) B_WHEEL_UP else B_WHEEL_DOWN); true
            } else false
        }
        wm.addView(root, lp)
        root.post { root.requestFocus() }
    }

    fun dismiss() { try { wm.removeView(root) } catch (e: Exception) { } }

    private fun finish(save: Boolean) { dismiss(); onDone(save) }
}
