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
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var ovStatus: TextView
    private lateinit var shStatus: TextView
    private lateinit var ovBtn: TextView
    private lateinit var shBtn: TextView
    private lateinit var startBtn: TextView
    private lateinit var lockKeyBtn: TextView
    private lateinit var curKeyBtn: TextView
    private lateinit var gamesBox: LinearLayout
    private val chips = ArrayList<TextView>()
    private val skinViews = ArrayList<SkinView>()
    private val gray = Color.parseColor("#8E94B8")

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

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText; setHintTextColor(gray); setTextColor(Color.WHITE); textSize = 14f
        setSingleLine(); background = roundBg(Pal.CARD2, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
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

        // ---- cabeçalho (logo gira e "pula" na entrada) ----
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat); setColorFilter(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.PRIMARY, Pal.ACCENT))
                .apply { cornerRadius = dp(20).toFloat() }
            scaleX = 0f; scaleY = 0f; rotation = -140f
        }
        logo.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(150).setDuration(750)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Orbyx", 28f, bold = true))
            addView(tv("Teclado, mouse e joystick para qualquer jogo", 13f, gray))
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
            addView(tv("Permissões", 13f, gray))
            addM(row(ovStatus, ovBtn)); addM(row(shStatus, shBtn))
        })

        // ---- iniciar / parar ----
        startBtn = pill("Iniciar", Pal.PRIMARY) { toggleService() }.apply {
            textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        add(startBtn)

        // ---- jogar ----
        val gameLink = input("Link ou ID do jogo do Roblox")
        val gameName = input("Apelido (opcional)")
        gamesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(card {
            addView(tv("Jogar", 13f, gray))
            addM(pill("▶  Entrar no Roblox", Pal.PRIMARY) { openRoblox() })
            addM(gameLink, 14); addM(gameName, 8)
            addM(pill("Salvar jogo") {
                val id = Regex("(\\d{5,})").find(gameLink.text.toString())?.value
                if (id == null) toast("Cole o link do jogo ou o número (ID) dele")
                else {
                    val name = gameName.text.toString().trim().ifEmpty { "Jogo $id" }
                    val l = games(); l.add(name to id); saveGames(l)
                    gameLink.setText(""); gameName.setText(""); renderGames()
                }
            }, 8)
            addM(gamesBox, 8)
        })
        renderGames()

        // ---- mapeamento ----
        val sensLabel = tv("", 14f, gray)
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
        lockKeyBtn = pill("", Pal.CARD2) { pickKey("lockKey", "lockScan") }
        curKeyBtn = pill("", Pal.CARD2) { pickKey("curKey", null) }
        add(card {
            addView(tv("Mapeamento", 13f, gray))
            addM(pill("🎯  Editar bolinhas (teclas e joystick)", Pal.ACCENT) { editMapping() })
            addM(tv("Com o mouse preso, mexer o mouse gira a câmera e as teclas viram toques nas bolinhas. " +
                "Segure a tecla de cursor para clicar em botões do jogo.", 12f, gray), 8)
            addM(sensLabel, 14); addM(seek, 4)
            addM(tv("Tecla para travar / soltar o mouse", 14f, gray), 14); addM(lockKeyBtn, 6)
            addM(tv("Tecla de cursor (segurar)", 14f, gray), 12); addM(curKeyBtn, 6)
        })

        // ---- mouses (skins) ----
        val skinRow = LinearLayout(this)
        Skins.names.forEachIndexed { i, name ->
            val sv = SkinView(this, i).apply {
                selected = prefs.getInt("skin", 0) == i
                setOnClickListener {
                    prefs.edit().putInt("skin", i).apply()
                    skinViews.forEach { it.selected = false; it.invalidate() }
                    selected = true; invalidate()
                }
                bounce()
            }
            skinViews.add(sv)
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(sv, LinearLayout.LayoutParams(dp(72), dp(72)))
                addView(tv(name, 11f, gray).apply { gravity = Gravity.CENTER })
            }
            skinRow.addView(item, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        add(card {
            addView(tv("Mouses", 13f, gray))
            addM(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false; addView(skinRow) }, 10)
        })

        // ---- tela maior + FPS ----
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
            addView(tv("Tela maior", 13f, gray))
            addM(chipRow, 10)
            addM(tv("Vale só enquanto o Roblox estiver aberto. É bloqueado se o tamanho ou o DPI do celular já foi alterado, " +
                "e volta ao normal sozinho se algo der errado.", 12f, gray), 8)
            addM(statsSwitch, 16)
        })

        // ---- desempenho ----
        add(card {
            addView(tv("Desempenho", 13f, gray))
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

    private fun keyName(k: String, d: Int) =
        KeyEvent.keyCodeToString(prefs.getInt(k, d)).removePrefix("KEYCODE_")

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
        lockKeyBtn.text = "Travar / soltar: ${keyName("lockKey", KeyEvent.KEYCODE_ESCAPE)}  ·  toque para mudar"
        curKeyBtn.text = "Cursor: ${keyName("curKey", KeyEvent.KEYCODE_ALT_LEFT)}  ·  toque para mudar"
    }

    // ---------- jogos do Roblox ----------
    private fun games(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val a = JSONArray(prefs.getString("games", "[]"))
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                out.add(o.getString("n") to o.getString("i"))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveGames(l: List<Pair<String, String>>) {
        val a = JSONArray()
        for (g in l) a.put(JSONObject().put("n", g.first).put("i", g.second))
        prefs.edit().putString("games", a.toString()).apply()
    }

    private fun renderGames() {
        gamesBox.removeAllViews()
        val l = games()
        if (l.isEmpty()) {
            gamesBox.addView(tv("Nenhum jogo salvo ainda.", 12f, gray)); return
        }
        gamesBox.addView(tv("Toque para entrar · segure para apagar", 12f, gray))
        for (g in l) {
            val p = pill("▶  ${g.first}", Pal.CARD2) { openGame(g.second) }
            p.setOnLongClickListener {
                val n = games(); n.removeAll { it.second == g.second && it.first == g.first }
                saveGames(n); renderGames(); true
            }
            gamesBox.addView(p, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun openRoblox() {
        val i = packageManager.getLaunchIntentForPackage(ROBLOX)
        if (i == null) toast("Roblox não encontrado neste celular") else startActivity(i)
    }

    private fun openGame(id: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("roblox://placeId=$id")).setPackage(ROBLOX))
        } catch (e: Exception) { toast("Não consegui abrir o jogo. O Roblox está instalado?") }
    }

    // ---------- ações ----------
    private fun editMapping() {
        if (!MapperService.running) { toast("Toque em Iniciar primeiro"); return }
        startService(Intent(this, MapperService::class.java).setAction("EDIT"))
        moveTaskToBack(true)
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

    /** Guarda a tecla escolhida (e o código bruto dela, usado para ouvir a tecla com o mouse solto). */
    private fun pickKey(keyPref: String, scanPref: String?) {
        AlertDialog.Builder(this)
            .setTitle("Escolher tecla")
            .setMessage("Pressione agora a tecla do teclado que você quer usar.")
            .setOnKeyListener { d, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK) false
                else {
                    if (ev.action == KeyEvent.ACTION_UP) {
                        val e = prefs.edit().putInt(keyPref, code)
                        if (scanPref != null) e.putInt(scanPref, ev.scanCode)
                        e.apply(); refresh(); d.dismiss()
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
