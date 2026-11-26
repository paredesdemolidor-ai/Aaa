package com.example.mobilador

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Injeta toques multi-dedo no sistema (via Shizuku). Cada "slot" é um dedo. */
class TouchInjector {
    private val ids = ArrayList<Int>()
    private val xs = HashMap<Int, Float>()
    private val ys = HashMap<Int, Float>()
    private var downTime = 0L

    private val im: Any by lazy {
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
        Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }
    /** Usa o id da tela de toque de verdade, para o Roblox entender que são toques (e mostrar os botões). */
    private val devId: Int by lazy {
        InputDevice.getDeviceIds().firstOrNull { id ->
            val d = InputDevice.getDevice(id)
            d != null && (d.sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        } ?: 0
    }

    private val inject by lazy {
        im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    @Synchronized fun down(id: Int, x: Float, y: Float) {
        if (id in ids) return
        if (ids.isEmpty()) downTime = SystemClock.uptimeMillis()
        ids.add(id); xs[id] = x; ys[id] = y
        send(if (ids.size == 1) MotionEvent.ACTION_DOWN
             else MotionEvent.ACTION_POINTER_DOWN or (ids.indexOf(id) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    @Synchronized fun move(id: Int, x: Float, y: Float) {
        if (id !in ids) return
        xs[id] = x; ys[id] = y
        send(MotionEvent.ACTION_MOVE)
    }

    @Synchronized fun up(id: Int) {
        val idx = ids.indexOf(id)
        if (idx < 0) return
        send(if (ids.size == 1) MotionEvent.ACTION_UP
             else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        ids.removeAt(idx); xs.remove(id); ys.remove(id)
    }

    @Synchronized fun releaseAll() { ids.toList().forEach { up(it) } }

    private fun send(action: Int) {
        val n = ids.size
        val props = Array(n) { i -> MotionEvent.PointerProperties().apply {
            id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().apply {
            x = xs[ids[i]]!!; y = ys[ids[i]]!!; pressure = 1f; size = 1f } }
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, n, props, coords,
            0, 0, 1f, 1f, devId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { inject.invoke(im, ev, 0) } finally { ev.recycle() }
    }
}
