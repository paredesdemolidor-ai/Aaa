package com.example.mobilador

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("L")

        // Proteção: se o app travar com a tela alterada, devolve a tela ao normal antes de fechar.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                if (getSharedPreferences("p", 0).getBoolean("sizeActive", false)) Tools.resetScreen()
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
}
