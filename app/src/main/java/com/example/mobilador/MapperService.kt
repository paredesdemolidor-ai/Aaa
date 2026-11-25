package com.example.mobilador

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class MapperService : Service() {

    // ===== Ajuste aqui as posições (fração da tela, modo paisagem) =====
    private val JOY_X = 0.14f; private val JOY_Y = 0.70f   // centro do joystick (WASD)
    private val CAM_X = 0.72f; private val CAM_Y = 0.45f   // ponto de ancoragem da câmera
    private val JUMP_X = 0.88f; private val JUMP_Y = 0.78f // botão de pulo (ESPAÇO)
    // ===================================================================

    private val JOY = 0; private val CAM = 1; private val TAP = 2; private val JUMP = 3

    private lateinit var wm: WindowManager
    private lateinit var view: View
    private lateinit var lp: WindowManager.LayoutParams
    private val inj = TouchInjector()
    private val handler = Handler(Looper.getMainLooper())
    private val keys = HashSet<Int>()
    private var active = true
    private var sens = 1.5f
    private var camX = 0f; private var camY = 0f
    private var camDown = false; private var joyDown = false

    private val idleRelease = Runnable { if (camDown) { inj.up(CAM); camDown = false } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "TOGGLE" -> if (::view.isInitialized) toggle()
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            else -> if (!::view.isInitialized) start()
        }
        return START_STICKY
    }

    private fun start() {
        sens = getSharedPreferences("p", 0).getFloat("sens", 1.5f)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notif()

        view = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnCapturedPointerListener { _, e -> onMouse(e); true }
            setOnKeyListener { _, code, e -> onKey(code, e) }
        }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        wm.addView(view, lp)
        view.viewTreeObserver.addOnWindowFocusChangeListener { f ->
            if (f && active) view.requestPointerCapture()
        }
        view.post { view.requestFocus() }
    }

    private fun toggle() {
        active = !active
        if (active) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            wm.updateViewLayout(view, lp)
            view.requestFocus(); view.requestPointerCapture()
        } else {
            releaseAll()
            view.releasePointerCapture()
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(view, lp)
        }
    }

    private fun size(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else { val d = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(d); d.widthPixels to d.heightPixels }

    // ---------- Mouse (movimento relativo: nunca bate na borda) ----------
    private fun onMouse(e: MotionEvent) {
        val (w, h) = size()
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> moveCam(e.x * sens, e.y * sens, w, h)
            MotionEvent.ACTION_DOWN -> inj.down(TAP, w / 2f, h / 2f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> inj.up(TAP)
        }
    }

    private fun moveCam(dx: Float, dy: Float, w: Int, h: Int) {
        val ax = w * CAM_X; val ay = h * CAM_Y
        val lim = min(w, h) * 0.18f
        if (!camDown) { camX = ax; camY = ay; inj.down(CAM, camX, camY); camDown = true }
        camX += dx; camY += dy
        if (abs(camX - ax) > lim || abs(camY - ay) > lim) {
            // chegou no limite: levanta o dedo e recoloca no centro (sem travar)
            inj.up(CAM); camX = ax; camY = ay; inj.down(CAM, camX, camY)
        } else inj.move(CAM, camX, camY)
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, 120)
    }

    // ---------- Teclado ----------
    private fun onKey(code: Int, e: KeyEvent): Boolean {
        val (w, h) = size()
        val down = e.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_ESCAPE -> { if (down && e.repeatCount == 0) toggle(); return true }
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D -> {
                if (down) keys.add(code) else keys.remove(code)
                updateJoystick(w, h); return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (down) inj.down(JUMP, w * JUMP_X, h * JUMP_Y) else inj.up(JUMP)
                return true
            }
        }
        return false
    }

    private fun updateJoystick(w: Int, h: Int) {
        var dx = 0f; var dy = 0f
        if (KeyEvent.KEYCODE_W in keys) dy -= 1f
        if (KeyEvent.KEYCODE_S in keys) dy += 1f
        if (KeyEvent.KEYCODE_A in keys) dx -= 1f
        if (KeyEvent.KEYCODE_D in keys) dx += 1f
        if (dx == 0f && dy == 0f) { if (joyDown) { inj.up(JOY); joyDown = false }; return }
        val cx = w * JOY_X; val cy = h * JOY_Y
        val r = min(w, h) * 0.10f; val n = hypot(dx, dy)
        if (!joyDown) { inj.down(JOY, cx, cy); joyDown = true }
        inj.move(JOY, cx + dx / n * r, cy + dy / n * r)
    }

    private fun releaseAll() {
        keys.clear(); camDown = false; joyDown = false
        handler.removeCallbacks(idleRelease)
        inj.releaseAll()
    }

    private fun notif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("m", "Mobilador", NotificationManager.IMPORTANCE_LOW))
        fun pi(a: String) = PendingIntent.getService(this, a.hashCode(),
            Intent(this, MapperService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "m")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Mobilador ativo")
            .setContentText("ESC ou botão abaixo para ligar/desligar")
            .addAction(Notification.Action.Builder(null, "Ligar/Desligar", pi("TOGGLE")).build())
            .addAction(Notification.Action.Builder(null, "Parar", pi("STOP")).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        releaseAll()
        if (::view.isInitialized) { view.releasePointerCapture(); wm.removeView(view) }
        super.onDestroy()
    }
}
