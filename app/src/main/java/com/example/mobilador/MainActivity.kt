package com.example.mobilador

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("p", 0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { root.addView(it) }

        root.addView(TextView(this).apply {
            text = "Mobilador Roblox\nMouse sem borda + teclado (WASD, ESPAÇO, ESC)"; textSize = 18f })

        btn("1. Permitir sobreposição de tela") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. Permitir Shizuku") {
            if (!Shizuku.pingBinder()) toast("Abra o app Shizuku e inicie o serviço primeiro")
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) toast("Shizuku já autorizado")
            else Shizuku.requestPermission(1)
        }

        root.addView(TextView(this).apply { text = "Sensibilidade do mouse" })
        root.addView(SeekBar(this).apply {
            max = 40; progress = (prefs.getFloat("sens", 1.5f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                    prefs.edit().putFloat("sens", maxOf(p, 1) / 10f).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })

        btn("3. Iniciar") {
            when {
                !Settings.canDrawOverlays(this) -> toast("Falta a permissão de sobreposição")
                !Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    toast("Falta autorizar o Shizuku")
                else -> {
                    if (Build.VERSION.SDK_INT >= 33)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                    ContextCompat.startForegroundService(this, Intent(this, MapperService::class.java))
                    toast("Ativo. Abra o Roblox e use o mouse.")
                }
            }
        }
        btn("Parar") { stopService(Intent(this, MapperService::class.java)) }
        setContentView(root)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
