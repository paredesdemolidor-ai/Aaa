package com.example.mobilador

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

const val ROBLOX = "com.roblox.client"

object Pal {
    val BG = Color.parseColor("#0F1226")
    val CARD = Color.parseColor("#1B1F3B")
    val CARD2 = Color.parseColor("#2A2F55")
    val PRIMARY = Color.parseColor("#6C5CE7")
    val ACCENT = Color.parseColor("#00B8D9")
    val DANGER = Color.parseColor("#E5484D")
    val OK = Color.parseColor("#2ECC71")
}

// ---------- helpers de interface ----------
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radiusDp).toFloat()
}

/** Animação de "apertar": encolhe ao tocar e volta com um quique. */
@SuppressLint("ClickableViewAccessibility")
fun View.bounce() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(2f)).start()
        }
        false
    }
}

/** Animação de entrada: sobe e aparece. */
fun View.popIn(delay: Long) {
    alpha = 0f; translationY = 40f
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(380).start()
}

fun Context.pill(text: String, color: Int = Pal.CARD2, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
    setPadding(dp(16), dp(12), dp(16), dp(12))
    background = roundBg(color, 14)
    setOnClickListener { onClick() }
    bounce()
}

// ---------- comandos via Shizuku ----------
object Sh {
    private val newProc by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    fun run(cmd: String): String = try {
        val p = newProc.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) { "" }
}

object Tools {
    val screenLabels = arrayOf("Normal", "Larga", "Mais larga", "Máxima")
    private val screenFactors = floatArrayOf(1f, 0.9f, 0.8f, 0.7f)

    private var lastTarget = ""

    /** Motivo para NÃO mexer na tela (null = seguro). Só vale quando nós ainda não alteramos nada. */
    fun screenUnsafeReason(): String? {
        val s = Sh.run("wm size"); val d = Sh.run("wm density")
        if (!s.contains("Physical size")) return "não consegui ler o tamanho da tela"
        if (!d.contains("Physical density")) return "não consegui ler o DPI do celular"
        if (s.contains("Override size")) return "o tamanho da tela do celular já foi modificado"
        if (d.contains("Override density")) return "o DPI original do celular foi modificado"
        return null
    }

    /** Deixa a tela mais larga (lado curto menor) e confere o resultado. Se algo estranho, desfaz. */
    fun applyScreen(level: Int): Boolean {
        val m = Regex("Physical size: (\\d+)x(\\d+)").find(Sh.run("wm size")) ?: return false
        val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
        val short = minOf(w, h)
        val ns = (short * screenFactors[level]).toInt() / 2 * 2
        if (ns < 480 || ns < short * 0.65f) return false
        val target = if (w <= h) "${ns}x$h" else "${w}x$ns"
        Sh.run("wm size $target")
        val after = Sh.run("wm size"); val d = Sh.run("wm density")
        val ok = after.contains("Override size: $target") && !d.contains("Override density")
        if (ok) lastTarget = target else resetScreen()
        return ok
    }

    fun resetScreen() { Sh.run("wm size reset") }

    /** Alguém (outro app/usuário) mexeu no tamanho ou no DPI enquanto o nosso estava ativo? */
    fun screenTamperReason(): String? {
        val s = Sh.run("wm size"); val d = Sh.run("wm density")
        if (d.contains("Override density")) return "o DPI do celular foi alterado"
        if (!s.contains("Override size: $lastTarget")) return "o tamanho da tela foi alterado por outro app"
        return null
    }

    /** true = Roblox aberto na frente, false = outro app, null = não deu para saber. */
    fun robloxOnTop(): Boolean? {
        val o = Sh.run("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'").trim()
        if (o.isEmpty()) return null
        return o.contains(ROBLOX)
    }

    fun optimize(): String {
        Sh.run("cmd game mode performance $ROBLOX")
        Sh.run("cmd package compile -m speed -f $ROBLOX")
        return "Roblox otimizado. Feche e abra o jogo para notar a diferença."
    }

    fun undoOptimize() { Sh.run("cmd game mode standard $ROBLOX") }

    private var layer: String? = null

    /** FPS aproximado do Roblox (quadros apresentados no último segundo). */
    fun fps(): Int? {
        if (layer == null) {
            val ls = Sh.run("dumpsys SurfaceFlinger --list").lines().map { it.trim() }.filter { it.contains(ROBLOX) }
            layer = ls.firstOrNull { it.contains("SurfaceView") } ?: ls.firstOrNull()
        }
        val l = layer ?: return null
        val rows = Sh.run("dumpsys SurfaceFlinger --latency \"$l\"").lines().drop(1)
        val now = System.nanoTime()
        val ts = rows.mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() }
            .filter { it > 0 && it < Long.MAX_VALUE / 2 }
        if (ts.isEmpty()) { layer = null; return null }
        return ts.count { now - it in 0..1_000_000_000L }
    }

    private var addr: InetAddress? = null

    /** Ping (ms) até os servidores do Roblox. */
    fun ping(): Int? = try {
        val a = addr ?: InetAddress.getByName("www.roblox.com").also { addr = it }
        val t = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(a, 443), 2000) }
        ((System.nanoTime() - t) / 1_000_000).toInt()
    } catch (e: Exception) { addr = null; null }
}
