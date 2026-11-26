package com.example.mobilador

import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

const val T_TAP = 0      // bolinha de toque
const val T_JOY = 1      // joystick com 4 teclas
const val T_LOOK = 2     // ponto onde a câmera gira com o mouse
const val B_WHEEL_UP = 20001
const val B_WHEEL_DOWN = 20002

/** Um elemento do mapeamento. x, y e r são frações da tela. */
class El(
    var type: Int, var x: Float, var y: Float, var r: Float = 0.06f,
    var bind: Int = 0, var binds: IntArray = IntArray(4), var mode: Int = 0
) {
    fun copy() = El(type, x, y, r, bind, binds.copyOf(), mode)
}

/** Nome legível de uma tecla ou botão do mouse. 10000+bit = botão do mouse, 2000x = roda. */
fun bindName(c: Int): String = when {
    c == 0 -> "—"
    c == B_WHEEL_UP -> "Roda ↑"
    c == B_WHEEL_DOWN -> "Roda ↓"
    c >= 10000 -> when (c - 10000) {
        1 -> "Mouse esq."; 2 -> "Mouse dir."; 4 -> "Mouse meio"
        8 -> "Mouse lado 1"; 16 -> "Mouse lado 2"; else -> "Mouse"
    }
    else -> KeyEvent.keyCodeToString(c).removePrefix("KEYCODE_")
}

object Store {
    /** Perfil inicial (serve para o Roblox): câmera, joystick WASD, pulo no ESPAÇO e clique esquerdo. */
    fun defaults(): MutableList<El> = mutableListOf(
        El(T_LOOK, 0.70f, 0.40f, 0.05f),
        El(T_JOY, 0.14f, 0.70f, 0.10f, binds = intArrayOf(
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D)),
        El(T_TAP, 0.88f, 0.78f, 0.06f, bind = KeyEvent.KEYCODE_SPACE),
        El(T_TAP, 0.50f, 0.50f, 0.04f, bind = 10001)
    )

    fun load(p: SharedPreferences): MutableList<El> {
        val raw = p.getString("profile", null) ?: return defaults()
        return try {
            val a = JSONArray(raw)
            val out = ArrayList<El>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val b = o.getJSONArray("binds")
                out.add(El(o.getInt("t"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.getDouble("r").toFloat(), o.getInt("b"), IntArray(4) { b.getInt(it) }, o.getInt("m")))
            }
            if (out.none { it.type == T_LOOK }) out.add(0, El(T_LOOK, 0.70f, 0.40f, 0.05f))
            out
        } catch (e: Exception) { defaults() }
    }

    fun save(p: SharedPreferences, l: List<El>) {
        val a = JSONArray()
        for (e in l) {
            a.put(JSONObject().put("t", e.type).put("x", e.x.toDouble()).put("y", e.y.toDouble())
                .put("r", e.r.toDouble()).put("b", e.bind).put("binds", JSONArray(e.binds.toList())).put("m", e.mode))
        }
        p.edit().putString("profile", a.toString()).apply()
    }
}
