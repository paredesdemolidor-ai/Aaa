package com.example.mobilador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Visuais do cursor (skins). Setas têm o ponto ativo no canto; Mira e Ponto no centro. */
object Skins {
    val names = arrayOf("Clássico", "Neon", "Mira", "Ponto", "Ouro", "Flecha")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 34f); lineTo(9f, 26f); lineTo(16f, 42f)
        lineTo(23f, 39f); lineTo(16f, 24f); lineTo(28f, 24f); close()
    }
    private val thin = Path().apply {
        moveTo(0f, 0f); lineTo(8f, 40f); lineTo(14f, 25f); lineTo(30f, 19f); close()
    }

    fun centered(skin: Int) = skin == 2 || skin == 3

    fun draw(c: Canvas, skin: Int) {
        when (skin) {
            0 -> shape(c, arrow, Color.WHITE, Color.BLACK)
            1 -> {
                line.color = Color.parseColor("#6622D3EE"); line.strokeWidth = 9f; c.drawPath(arrow, line)
                shape(c, arrow, Color.parseColor("#22D3EE"), Color.parseColor("#06202A"))
            }
            2 -> {
                line.color = Color.BLACK; line.strokeWidth = 6f; cross(c)
                line.color = Color.WHITE; line.strokeWidth = 2.5f; cross(c)
            }
            3 -> {
                fill.color = Color.BLACK; c.drawCircle(0f, 0f, 8f, fill)
                fill.color = Color.WHITE; c.drawCircle(0f, 0f, 5.5f, fill)
                line.color = Color.BLACK; line.strokeWidth = 5f; c.drawCircle(0f, 0f, 14f, line)
                line.color = Color.WHITE; line.strokeWidth = 2.5f; c.drawCircle(0f, 0f, 14f, line)
            }
            4 -> shape(c, arrow, Color.parseColor("#FFC83D"), Color.parseColor("#5A3A00"))
            else -> shape(c, thin, Color.parseColor("#B79CFF"), Color.parseColor("#1E1040"))
        }
    }

    private fun cross(c: Canvas) {
        c.drawCircle(0f, 0f, 9f, line)
        c.drawLine(-20f, 0f, -12f, 0f, line); c.drawLine(12f, 0f, 20f, 0f, line)
        c.drawLine(0f, -20f, 0f, -12f, line); c.drawLine(0f, 12f, 0f, 20f, line)
    }

    private fun shape(c: Canvas, p: Path, f: Int, l: Int) {
        fill.color = f; c.drawPath(p, fill)
        line.color = l; line.strokeWidth = 3f; c.drawPath(p, line)
    }
}

/** Miniatura de uma skin para a galeria. */
class SkinView(c: Context, private val skin: Int) : View(c) {
    var selected = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bg.style = Paint.Style.FILL; bg.color = Pal.CARD2
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 24f, 24f, bg)
        if (selected) {
            bg.style = Paint.Style.STROKE; bg.strokeWidth = 5f; bg.color = Pal.PRIMARY
            canvas.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), 22f, 22f, bg)
        }
        val s = w / 70f
        canvas.save()
        if (Skins.centered(skin)) canvas.translate(w / 2f, h / 2f)
        else canvas.translate((w - 28f * s) / 2f, (h - 42f * s) / 2f)
        canvas.scale(s, s)
        Skins.draw(canvas, skin)
        canvas.restore()
    }
}
