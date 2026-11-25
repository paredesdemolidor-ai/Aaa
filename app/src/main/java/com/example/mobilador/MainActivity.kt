package com.example.mobilador

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var ovStatus: TextView
    private lateinit var shStatus: TextView
    private lateinit var ovBtn: TextView
    private lateinit var shBtn: TextView
    private lateinit var startBtn: TextView
    private lateinit var keyBtn: TextView
    private val chips = ArrayList<TextView>()

    private fun tv(t: String, sp: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = roundBg(Pal.CARD, 18)
        build()
    }

    private fun LinearLayout.addM(v: View, top: Int = 10) {
        v.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        addView(v)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("p", 0)
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(32))
        }
        var step = 0L
        fun add(v: View, top: Int = 14) {
            v.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
            root.addView(v)
            v.popIn(70L * step++)
        }

        // ---- cabeçalho ----
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat); setColorFilter(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.PRIMARY, Pal.ACCENT))
                .apply { cornerRadius = dp(18).toFloat() }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Mouse Livre", 26f, bold = true))
            addView(tv("Mouse sem bordas para jogar no celular", 13f, Color.parseColor("#9AA0C8")))
        }
        add(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        }, 0)

        // ---- permissões ----
        ovStatus = tv(""); shStatus = tv("")
        ovBtn = pill("Permitir", Pal.PRIMARY) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        shBtn = pill("Autorizar", Pal.PRIMARY) {
            try {
                if (!Shizuku.pingBinder()) toast("Abra o app Shizuku e inicie o serviço primeiro")
                else Shizuku.requestPermission(1)
            } catch (e: Throwable) { toast("Instale o app Shizuku primeiro") }
        }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }

        fun row(l: TextView, btn: TextView) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(l, LinearLayout.LayoutParams(0, -2, 1f)); addView(btn)
        }
        add(card {
            addView(tv("Permissões", 13f, Color.parseColor("#9AA0C8")))
            addM(row(ovStatus, ovBtn)); addM(row(shStatus, shBtn))
        })

        // ---- iniciar / parar ----
        startBtn = pill("Iniciar", Pal.PRIMARY) { toggleService() }.apply {
            textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        add(startBtn)

        // ---- ajustes ----
        val sensLabel = tv("", 14f, Color.parseColor("#9AA0C8"))
        fun sensText() { sensLabel.text = "Sensibilidade do mouse: ${"%.1f".format(prefs.getFloat("sens", 1.5f))}x" }
        sensText()
        val seek = SeekBar(this).apply {
            max = 40; progress = (prefs.getFloat("sens", 1.5f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                    prefs.edit().putFloat("sens", maxOf(p, 1) / 10f).apply(); sensText()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        keyBtn = pill("", Pal.CARD2) { pickKey() }
        val chipRow = LinearLayout(this)
        Tools.screenLabels.forEachIndexed { i, l ->
            val c = pill(l, Pal.CARD2) { selectScreen(i) }.apply {
                textSize = 12f; setPadding(dp(2), dp(10), dp(2), dp(10))
            }
            chips.add(c)
            chipRow.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        val statsSwitch = Switch(this).apply {
            text = "Mostrar FPS e ping na tela"; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("stats", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("stats", c).apply() }
        }
        add(card {
            addView(tv("Ajustes", 13f, Color.parseColor("#9AA0C8")))
            addM(sensLabel); addM(seek, 4)
            addM(tv("Tecla para soltar o mouse (ele volta a aparecer)", 14f, Color.parseColor("#9AA0C8")), 16)
            addM(keyBtn, 6)
            addM(tv("Tela maior", 14f, Color.parseColor("#9AA0C8")), 16)
            addM(chipRow, 6)
            addM(tv("Vale só enquanto o Roblox estiver aberto. É bloqueado se o tamanho ou o DPI do celular já foi alterado, e volta ao normal sozinho se algo der errado.", 12f, Color.parseColor("#9AA0C8")), 8)
            addM(statsSwitch, 16)
        })

        // ---- otimizar ----
        add(card {
            addView(tv("Desempenho", 13f, Color.parseColor("#9AA0C8")))
            addM(pill("⚡  Otimizar Roblox", Pal.ACCENT) {
                if (!shizukuOk()) toast("Falta autorizar o Shizuku")
                else { toast("Otimizando…"); Thread { val r = Tools.optimize(); runOnUiThread { toast(r) } }.start() }
            })
            addM(pill("Desfazer otimização") {
                if (!shizukuOk()) toast("Falta autorizar o Shizuku")
                else { Thread { Tools.undoOptimize() }.start(); toast("Modo normal restaurado") }
            }, 8)
            addM(pill("Restaurar tela ao normal agora") {
                if (!shizukuOk()) toast("Falta autorizar o Shizuku")
                else {
                    Thread { Tools.resetScreen(); prefs.edit().putBoolean("sizeActive", false).apply() }.start()
                    toast("Tela restaurada")
                }
            }, 8)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.BG); isFillViewport = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // proteção: sobrou tela alterada de uma execução que travou? restaura
        if (!MapperService.running && prefs.getBoolean("sizeActive", false) && shizukuOk())
            Thread { Tools.resetScreen(); prefs.edit().putBoolean("sizeActive", false).apply() }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun shizukuOk() = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    private fun keyName() = KeyEvent.keyCodeToString(
        prefs.getInt("relKey", KeyEvent.KEYCODE_ESCAPE)).removePrefix("KEYCODE_")

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val sh = shizukuOk()
        ovStatus.text = (if (ov) "✓  " else "✗  ") + "Sobreposição de tela"
        ovStatus.setTextColor(if (ov) Pal.OK else Pal.DANGER)
        ovBtn.visibility = if (ov) View.GONE else View.VISIBLE
        shStatus.text = (if (sh) "✓  " else "✗  ") + "Shizuku"
        shStatus.setTextColor(if (sh) Pal.OK else Pal.DANGER)
        shBtn.visibility = if (sh) View.GONE else View.VISIBLE

        val run = MapperService.running
        startBtn.text = if (run) "Parar" else "Iniciar"
        startBtn.background = roundBg(if (run) Pal.DANGER else Pal.PRIMARY, 16)

        val sel = prefs.getInt("screen", 0)
        chips.forEachIndexed { i, c -> c.background = roundBg(if (i == sel) Pal.PRIMARY else Pal.CARD2, 14) }
        keyBtn.text = "Tecla: ${keyName()}  ·  toque para mudar"
    }

    private fun selectScreen(i: Int) {
        if (!shizukuOk()) { toast("Falta autorizar o Shizuku"); return }
        if (i == 0) { prefs.edit().putInt("screen", 0).apply(); refresh(); return }
        Thread {
            val why = Tools.screenUnsafeReason()
            runOnUiThread {
                if (why != null) toast("Bloqueado por segurança: $why")
                else {
                    prefs.edit().putInt("screen", i).apply()
                    toast(if (MapperService.running) "Vai valer quando o Roblox abrir" else "Toque em Iniciar para ativar")
                }
                refresh()
            }
        }.start()
    }

    private fun pickKey() {
        AlertDialog.Builder(this)
            .setTitle("Tecla para soltar o mouse")
            .setMessage("Pressione agora a tecla do teclado que você quer usar.")
            .setOnKeyListener { d, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK) false
                else {
                    if (ev.action == KeyEvent.ACTION_UP) {
                        prefs.edit().putInt("relKey", code).apply(); refresh(); d.dismiss()
                    }
                    true
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleService() {
        if (MapperService.running) {
            stopService(Intent(this, MapperService::class.java))
            startBtn.postDelayed({ refresh() }, 400)
            return
        }
        when {
            !Settings.canDrawOverlays(this) -> toast("Falta a permissão de sobreposição")
            !shizukuOk() -> toast("Falta autorizar o Shizuku")
            else -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                ContextCompat.startForegroundService(this, Intent(this, MapperService::class.java))
                startBtn.postDelayed({ refresh() }, 500)
            }
        }
    }
}
