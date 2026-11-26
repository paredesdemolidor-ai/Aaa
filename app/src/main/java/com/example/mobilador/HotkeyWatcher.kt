package com.example.mobilador

/**
 * Lê as teclas direto do aparelho (getevent via Shizuku). Assim a tecla de travar/soltar
 * funciona mesmo quando o mouse está solto e o app não recebe o teclado.
 */
class HotkeyWatcher(private val onPress: (Int) -> Unit) {
    @Volatile private var proc: Process? = null
    @Volatile private var alive = false

    fun start() {
        if (alive) return
        alive = true
        Thread {
            try {
                val p = Sh.start("getevent")
                proc = p
                p.inputStream.bufferedReader().forEachLine { l ->
                    if (alive) {
                        val parts = l.substringAfter(": ", "").trim().split(Regex("\\s+"))
                        if (parts.size == 3) {
                            val type = parts[0].toIntOrNull(16)
                            val code = parts[1].toIntOrNull(16)
                            val value = parts[2].toLongOrNull(16)
                            if (type == 1 && value == 1L && code != null) onPress(code)
                        }
                    }
                }
            } catch (e: Throwable) { }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        alive = false
        try { proc?.destroy() } catch (e: Throwable) { }
    }
}
