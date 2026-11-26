package com.example.mobilador

import android.animation.ValueAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.view.WindowManager.LayoutParams as WLP
import android.view.animation.OvershootInterpolator
import android.widget.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Captura o mouse (movimento relativo) e desenha o cursor virtual com a skin escolhida. */
class CaptureView(c: Context) : View(c) {
    var showCursor = false
    var curX = 0f
    var curY = 0f
    var skin = 0
    var onCapture: ((Boolean) -> Unit)? = null

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        onCapture?.invoke(hasCapture)
    }

    override fun onDraw(canvas: Canvas) {
        if (!showCursor) return
        val loc = IntArray(2); getLocationOnScreen(loc)
        val s = resources.displayMetrics.density * 0.7f
        canvas.save()
        canvas.translate(curX - loc[0], curY - loc[1]); canvas.scale(s, s)
        Skins.draw(canvas, skin)
        canvas.restore()
    }
}

class MapperService : Service() {

    companion object { @Volatile var running = false }

    private val TAP = 0   // dedo do cursor virtual
    private val CAM = 1   // dedo da câmera (elementos do mapeamento usam 2..30)

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var worker: Handler
    private lateinit var workerThread: HandlerThread
    private val ui = Handler(Looper.getMainLooper())
    private val inj = TouchInjector()

    private var capView: CaptureView? = null
    private val capLp = WLP(
        WLP.MATCH_PARENT, WLP.MATCH_PARENT, WLP.TYPE_APPLICATION_OVERLAY,
        WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT)

    private var bubble: View? = null
    private lateinit var bubbleLp: WLP
    private var menu: View? = null
    private var menuClosedAt = 0L
    private var trash: TextView? = null
    private var stats: TextView? = null
    private var editor: MapperEditor? = null
    private var watcher: HotkeyWatcher? = null

    // ---- mapeamento ----
    private var elements: MutableList<El> = ArrayList()
    private val joyKeys = HashMap<Int, BooleanArray>()
    private val joyDown = HashMap<Int, Boolean>()
    private val toggled = HashSet<Int>()

    // ---- estado do mouse ----
    private var locked = false
    private var cursorMode = false
    private var capHas = false
    private var lastToggle = 0L
    private var prevButtons = 0
    private var sens = 1.5f
    private var cx = 0f
    private var cy = 0f
    private var leftDown = false
    private var camX = 0f
    private var camY = 0f
    private var camDown = false
    private val idleCam = Runnable { endCam() }

    // ---- proteção da tela ----
    @Volatile private var applied = false
    @Volatile private var appliedLevel = 0
    @Volatile private var stopping = false
    private var detectFails = 0
    private var tick = 0

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            "sens" -> sens = p.getFloat("sens", 1.5f)
            "stats" -> applyStats()
            "skin" -> capView?.let { it.skin = p.getInt("skin", 0); it.invalidate() }
        }
    }

    private val statsLoop = object : Runnable {
        override fun run() {
            if (stats == null) return
            val fps = Tools.fps()
            val ping = Tools.ping()
            ui.post { stats?.text = "FPS ${fps ?: "--"}  ·  Ping ${ping?.let { "$it ms" } ?: "--"}" }
            worker.postDelayed(this, 1000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (capView == null) start()
        when (intent?.action) {
            "MOUSE" -> setMouse(!locked)
            "BUBBLE" -> if (bubble != null) hideBubble() else showBubble()
            "EDIT" -> openEditor()
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    // ================= início =================
    private fun start() {
        running = true
        prefs = getSharedPreferences("p", 0)
        sens = prefs.getFloat("sens", 1.5f)
        elements = Store.load(prefs)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        workerThread = HandlerThread("w").also { it.start() }
        worker = Handler(workerThread.looper)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("m", "Orbyx", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        if (Build.VERSION.SDK_INT >= 28) capLp.layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val v = CaptureView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            skin = prefs.getInt("skin", 0)
            setOnCapturedPointerListener { _, e -> onMouse(e); true }
            setOnKeyListener { _, code, e ->
                val down = e.action == KeyEvent.ACTION_DOWN
                when (code) {
                    prefs.getInt("lockKey", KeyEvent.KEYCODE_ESCAPE) -> {
                        if (down && e.repeatCount == 0) toggleLock(); true
                    }
                    prefs.getInt("curKey", KeyEvent.KEYCODE_ALT_LEFT) -> {
                        if (e.repeatCount == 0) setCursorMode(down); true
                    }
                    else -> { if (!down || e.repeatCount == 0) mapKey(code, down); true }
                }
            }
            onCapture = { has ->
                capHas = has
                showCursor = has && locked && cursorMode
                invalidate()
            }
        }
        capView = v
        wm.addView(v, capLp)
        v.viewTreeObserver.addOnWindowFocusChangeListener { f -> if (f && locked) v.requestPointerCapture() }

        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        setMouse(true)
        showBubble()
        applyStats()

        // a tecla de travar/soltar vale mesmo com o mouse solto (lida direto do aparelho)
        watcher = HotkeyWatcher { sc ->
            if (sc == prefs.getInt("lockScan", 1)) ui.post { toggleLock() }
        }.also { it.start() }

        // sobra de uma execução anterior que travou? devolve a tela ao normal antes de tudo
        worker.post {
            if (prefs.getBoolean("sizeActive", false)) {
                Tools.resetScreen(); prefs.edit().putBoolean("sizeActive", false).apply()
            }
        }
        worker.post(guardLoop)
    }

    private fun size(): Pair<Int, Int> {
        val d = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(d)
        return d.widthPixels to d.heightPixels
    }

    private fun rm(v: View?) { if (v == null) return; try { wm.removeView(v) } catch (e: Exception) { } }

    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }

    // ================= travar / soltar =================
    private fun toggleLock() {
        val now = SystemClock.uptimeMillis()
        if (now - lastToggle < 350 || editor != null) return
        lastToggle = now
        setMouse(!locked)
    }

    private fun setMouse(on: Boolean) {
        val v = capView ?: return
        locked = on
        releaseAll()
        if (on) {
            val (w, h) = size()
            if (cx == 0f && cy == 0f) { cx = w / 2f; cy = h / 2f }
            capLp.flags = capLp.flags and WLP.FLAG_NOT_FOCUSABLE.inv()
            wm.updateViewLayout(v, capLp)
            v.showCursor = false; v.invalidate()
            v.requestFocus(); v.requestPointerCapture()
        } else {
            cursorMode = false
            v.releasePointerCapture()
            v.showCursor = false; v.invalidate()
            capLp.flags = capLp.flags or WLP.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(v, capLp)
        }
        updateNotif()
    }

    /** Segurando a tecla de cursor: o mouse vira cursor (clica em botões do jogo). Solta: volta a girar a câmera. */
    private fun setCursorMode(on: Boolean) {
        if (cursorMode == on || !locked) return
        cursorMode = on
        endCam()
        if (!on && leftDown) { inj.up(TAP); leftDown = false }
        capView?.let { it.curX = cx; it.curY = cy; it.showCursor = on && capHas; it.invalidate() }
    }

    private fun releaseAll() {
        ui.removeCallbacks(idleCam)
        inj.releaseAll()
        camDown = false; leftDown = false; prevButtons = 0
        joyKeys.clear(); joyDown.clear(); toggled.clear()
    }

    // ================= mouse =================
    private fun onMouse(e: MotionEvent) {
        val (w, h) = size()
        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) {
                    val c = if (v > 0) B_WHEEL_UP else B_WHEEL_DOWN
                    mapKey(c, true); ui.postDelayed({ mapKey(c, false) }, 60)
                }
                return
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val dx = e.x * sens; val dy = e.y * sens
                if (cursorMode) {
                    cx = (cx + dx).coerceIn(0f, w - 1f)
                    cy = (cy + dy).coerceIn(0f, h - 1f)
                    if (leftDown) inj.move(TAP, cx, cy)
                    capView?.let { it.curX = cx; it.curY = cy; it.invalidate() }
                } else moveCam(dx, dy, w, h)
            }
        }
        val bs = e.buttonState
        if (bs != prevButtons) {
            for (bit in intArrayOf(1, 2, 4, 8, 16)) {
                val now = (bs and bit) != 0
                val was = (prevButtons and bit) != 0
                if (now == was) continue
                if (cursorMode && bit == 1) {
                    leftDown = now
                    if (now) inj.down(TAP, cx, cy) else inj.up(TAP)
                } else mapKey(10000 + bit, now)
            }
            prevButtons = bs
        }
    }

    /** Mouse gira a câmera. O "dedo" volta ao ponto de origem quando chega no limite (nunca trava na borda). */
    private fun moveCam(dx: Float, dy: Float, w: Int, h: Int) {
        val look = elements.firstOrNull { it.type == T_LOOK }
        val ax = w * (look?.x ?: 0.70f); val ay = h * (look?.y ?: 0.40f)
        val lim = min(w, h) * 0.18f
        if (!camDown) { camX = ax; camY = ay; inj.down(CAM, camX, camY); camDown = true }
        camX += dx; camY += dy
        if (abs(camX - ax) > lim || abs(camY - ay) > lim) {
            inj.up(CAM); camX = ax; camY = ay; inj.down(CAM, camX, camY)
        } else inj.move(CAM, camX, camY)
        ui.removeCallbacks(idleCam)
        ui.postDelayed(idleCam, 140)
    }

    private fun endCam() { if (camDown) { inj.up(CAM); camDown = false } }

    // ================= teclas e botões → bolinhas =================
    private fun mapKey(code: Int, down: Boolean) {
        val (w, h) = size()
        for (i in elements.indices) {
            if (i > 28) break
            val el = elements[i]
            val slot = 2 + i
            if (el.type == T_TAP) {
                if (el.bind != code) continue
                if (el.mode == 1) {
                    if (down) {
                        if (toggled.remove(i)) inj.up(slot)
                        else { toggled.add(i); inj.down(slot, el.x * w, el.y * h) }
                    }
                } else if (down) inj.down(slot, el.x * w, el.y * h) else inj.up(slot)
            } else if (el.type == T_JOY) {
                val k = el.binds.indexOf(code)
                if (k < 0) continue
                val st = joyKeys.getOrPut(i) { BooleanArray(4) }
                st[k] = down
                updateJoy(i, el, st, w, h)
            }
        }
    }

    private fun updateJoy(i: Int, el: El, st: BooleanArray, w: Int, h: Int) {
        val slot = 2 + i
        var dx = 0f; var dy = 0f
        if (st[0]) dy -= 1f
        if (st[2]) dy += 1f
        if (st[1]) dx -= 1f
        if (st[3]) dx += 1f
        val bx = el.x * w; val by = el.y * h
        if (dx == 0f && dy == 0f) { if (joyDown.remove(i) == true) inj.up(slot); return }
        val r = min(w, h) * el.r
        val n = hypot(dx, dy)
        if (joyDown[i] != true) { inj.down(slot, bx, by); joyDown[i] = true }
        inj.move(slot, bx + dx / n * r, by + dy / n * r)
    }

    private fun openEditor() {
        if (editor != null) return
        closeMenu()
        setMouse(false)
        val copy = elements.map { it.copy() }.toMutableList()
        editor = MapperEditor(this, wm, copy) { saved ->
            editor = null
            if (saved) { elements = copy; Store.save(prefs, copy) }
            toast(if (saved) "Mapeamento salvo" else "Edição cancelada")
        }.also { it.show() }
    }

    // ================= bolinha =================
    private fun showBubble() {
        if (bubble != null) return
        val s = dp(56)
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat)
            setColorFilter(Color.WHITE)
            val p = dp(14); setPadding(p, p, p, p)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.PRIMARY, Pal.ACCENT))
                .apply { shape = GradientDrawable.OVAL }
            scaleX = 0f; scaleY = 0f
        }
        bubbleLp = WLP(s, s, WLP.TYPE_APPLICATION_OVERLAY,
            WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bx", dp(6)); y = prefs.getInt("by", dp(160))
        }
        wm.addView(v, bubbleLp)
        bubble = v
        v.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator()).start()

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = bubbleLp.x; oy = bubbleLp.y; moved = false
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val ddx = e.rawX - sx; val ddy = e.rawY - sy
                    if (!moved && hypot(ddx, ddy) > dp(8)) { moved = true; closeMenu(); showTrash() }
                    if (moved) {
                        bubbleLp.x = ox + ddx.toInt(); bubbleLp.y = oy + ddy.toInt()
                        wm.updateViewLayout(v, bubbleLp)
                        val near = nearTrash()
                        trash?.animate()?.scaleX(if (near) 1.3f else 1f)?.scaleY(if (near) 1.3f else 1f)?.setDuration(100)?.start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
                    if (moved) {
                        if (nearTrash()) hideBubble() else snap(v)
                        hideTrash()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) toggleMenu()
                }
            }
            true
        }
        updateNotif()
    }

    private fun hideBubble() {
        closeMenu()
        val b = bubble ?: return
        bubble = null
        b.animate().scaleX(0f).scaleY(0f).setDuration(160).withEndAction { rm(b) }.start()
        updateNotif()
    }

    private fun nearTrash(): Boolean {
        val (w, h) = size()
        val bx = bubbleLp.x + bubbleLp.width / 2f
        val by = bubbleLp.y + bubbleLp.height / 2f
        return hypot(bx - w / 2f, by - (h - dp(80))) < dp(90)
    }

    private fun snap(v: View) {
        val (w, _) = size()
        val s = bubbleLp.width
        val target = if (bubbleLp.x + s / 2 < w / 2) dp(4) else w - s - dp(4)
        ValueAnimator.ofInt(bubbleLp.x, target).apply {
            duration = 220; interpolator = OvershootInterpolator()
            addUpdateListener {
                bubbleLp.x = it.animatedValue as Int
                try { wm.updateViewLayout(v, bubbleLp) } catch (e: Exception) { }
            }
        }.start()
        prefs.edit().putInt("bx", target).putInt("by", bubbleLp.y).apply()
    }

    private fun showTrash() {
        if (trash != null) return
        val s = dp(64)
        val t = TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.DANGER) }
            alpha = 0f; scaleX = 0.6f; scaleY = 0.6f
        }
        val lp = WLP(s, s, WLP.TYPE_APPLICATION_OVERLAY,
            WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(48)
        }
        wm.addView(t, lp)
        trash = t
        t.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    private fun hideTrash() {
        val t = trash ?: return
        trash = null
        t.animate().alpha(0f).setDuration(150).withEndAction { rm(t) }.start()
    }

    // ================= menu da bolinha =================
    private fun toggleMenu() {
        if (menu != null) closeMenu()
        else if (SystemClock.uptimeMillis() - menuClosedAt > 300) openMenu()
    }

    private fun closeMenu() {
        val m = menu ?: return
        menu = null
        menuClosedAt = SystemClock.uptimeMillis()
        m.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(120).withEndAction { rm(m) }.start()
    }

    private fun openMenu() {
        if (bubble == null) return
        val (w, h) = size()
        val cw = dp(240)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(4))
            background = roundBg(Color.parseColor("#F2101220"), 22)
            alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
        }
        fun item(text: String, color: Int = Pal.CARD2, act: () -> Unit) {
            card.addView(pill(text, color) { closeMenu(); act() }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            })
        }
        item(if (locked) "🖱  Mouse preso · soltar" else "🖱  Mouse solto · prender",
            if (locked) Pal.PRIMARY else Pal.CARD2) { setMouse(!locked) }
        item("🎯  Editar mapeamento") { openEditor() }
        val si = prefs.getInt("screen", 0)
        item("🖥  Tela: ${Tools.screenLabels[si]}") { cycleScreen() }
        val st = prefs.getBoolean("stats", false)
        item("📊  FPS e ping: ${if (st) "ligado" else "desligado"}") {
            prefs.edit().putBoolean("stats", !st).apply()
        }
        item("⚡  Otimizar Roblox") {
            toast("Otimizando…")
            Thread { toast(Tools.optimize()) }.start()
        }
        item("⚙  Abrir o app") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item("⛔  Parar tudo", Pal.DANGER) { stopSelf() }

        card.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val ch = card.measuredHeight
        val bs = bubbleLp.width
        val mx = if (bubbleLp.x + bs / 2 < w / 2) bubbleLp.x + bs + dp(8) else bubbleLp.x - cw - dp(8)
        val my = bubbleLp.y.coerceIn(dp(8), maxOf(dp(8), h - ch - dp(8)))
        val lp = WLP(cw, -2, WLP.TYPE_APPLICATION_OVERLAY,
            WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_WATCH_OUTSIDE_TOUCH or WLP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = mx; y = my }
        card.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) closeMenu(); false }
        wm.addView(card, lp)
        menu = card
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    // ================= FPS e ping =================
    private fun applyStats() {
        val on = prefs.getBoolean("stats", false)
        if (on && stats == null) {
            val t = TextView(this).apply {
                textSize = 12f; setTextColor(Color.WHITE); text = "FPS --  ·  Ping --"
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundBg(Color.parseColor("#AA000000"), 12)
            }
            val lp = WLP(-2, -2, WLP.TYPE_APPLICATION_OVERLAY,
                WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(6) }
            wm.addView(t, lp)
            stats = t
            worker.post(statsLoop)
        } else if (!on && stats != null) {
            rm(stats); stats = null
            worker.removeCallbacks(statsLoop)
        }
    }

    // ================= proteção: tela maior só no Roblox =================
    private val guardLoop = object : Runnable {
        override fun run() {
            if (stopping || capView == null) return
            try { guardStep() } catch (e: Throwable) { screenFail("erro inesperado") }
            worker.postDelayed(this, 1000)
        }
    }

    private fun guardStep() {
        val level = prefs.getInt("screen", 0)
        if (level == 0) { if (applied) restoreScreen(); return }

        val top = Tools.robloxOnTop()
        if (top == null) {
            if (++detectFails >= 3) screenFail("não consegui saber qual app está aberto")
            return
        }
        detectFails = 0

        if (!top) { if (applied) restoreScreen(); return }

        if (!applied) {
            val why = Tools.screenUnsafeReason()
            if (why != null) { screenFail(why); return }
            if (stopping) return
            if (!Tools.applyScreen(level)) { screenFail("o aparelho não aceitou o novo tamanho"); return }
            applied = true; appliedLevel = level
            prefs.edit().putBoolean("sizeActive", true).apply()
        } else if (level != appliedLevel) {
            if (!Tools.applyScreen(level)) { screenFail("o aparelho não aceitou o novo tamanho"); return }
            appliedLevel = level
        } else if (++tick % 5 == 0) {
            val why = Tools.screenTamperReason()
            if (why != null) screenFail(why)
        }
    }

    private fun restoreScreen() {
        Tools.resetScreen()
        applied = false
        prefs.edit().putBoolean("sizeActive", false).apply()
    }

    /** Qualquer problema: devolve a tela ao normal e desliga a função. */
    private fun screenFail(why: String) {
        restoreScreen()
        prefs.edit().putInt("screen", 0).apply()
        toast("Tela maior desativada por segurança: $why")
    }

    private fun cycleScreen() {
        val next = (prefs.getInt("screen", 0) + 1) % Tools.screenLabels.size
        if (next == 0 || applied) {
            prefs.edit().putInt("screen", next).apply()
            toast("Tela: ${Tools.screenLabels[next]}")
            return
        }
        Thread {
            val why = Tools.screenUnsafeReason()
            if (why != null) toast("Bloqueado por segurança: $why")
            else {
                prefs.edit().putInt("screen", next).apply()
                toast("Tela: ${Tools.screenLabels[next]} (vale quando o Roblox estiver aberto)")
            }
        }.start()
    }

    // ================= notificação =================
    private fun buildNotif(): Notification {
        fun pi(a: String) = PendingIntent.getService(this, a.hashCode(),
            Intent(this, MapperService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        fun act(label: String, a: String) =
            Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), label, pi(a)).build()
        val bubbleOn = bubble != null
        return Notification.Builder(this, "m")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Orbyx")
            .setContentText(
                if (!bubbleOn) "Bolinha removida · reative ou pare o app"
                else if (locked) "Mouse preso" else "Mouse solto")
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .addAction(act(if (bubbleOn) "Esconder bolinha" else "Mostrar bolinha", "BUBBLE"))
            .addAction(act(if (locked) "Soltar mouse" else "Prender mouse", "MOUSE"))
            .addAction(act("Parar", "STOP"))
            .build()
    }

    private fun updateNotif() {
        getSystemService(NotificationManager::class.java).notify(1, buildNotif())
    }

    // ================= fim =================
    override fun onDestroy() {
        running = false
        stopping = true
        if (capView != null) {
            watcher?.stop()
            editor?.dismiss(); editor = null
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            releaseAll()
            capView?.releasePointerCapture()
            worker.removeCallbacksAndMessages(null)
            workerThread.quitSafely()
            try { workerThread.join(2500) } catch (e: Exception) { }
            rm(capView); rm(bubble); rm(menu); rm(trash); rm(stats)
            capView = null; bubble = null; menu = null; trash = null; stats = null
            if (applied || prefs.getBoolean("sizeActive", false)) {
                Tools.resetScreen()
                applied = false
                prefs.edit().putBoolean("sizeActive", false).apply()
            }
        }
        super.onDestroy()
    }
}
