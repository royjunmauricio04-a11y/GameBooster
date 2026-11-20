package com.gamebooster.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.gamebooster.app.core.QualityLevel

/** Small draggable HUD drawn over other apps. Needs the user-granted "Display over other apps" permission. */
class OverlayController(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        if (view != null || !canDraw()) return
        val dp = context.resources.displayMetrics.density
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xCC0E1117.toInt()); cornerRadius = 10 * dp }
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * dp).toInt(); y = (96 * dp).toInt() }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (e.rawX - downX).toInt(); p.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(tv, p) }; true
                }
                else -> false
            }
        }
        if (runCatching { wm.addView(tv, p) }.isSuccess) { view = tv; params = p }
    }

    fun update(text: String, level: QualityLevel) {
        val tv = view ?: return
        tv.text = text
        tv.setTextColor(when (level) {
            QualityLevel.GOOD -> 0xFF00E5A8.toInt()
            QualityLevel.OK -> 0xFFFFC857.toInt()
            QualityLevel.BAD -> 0xFFFF5C5C.toInt()
            QualityLevel.UNKNOWN -> 0xFF9AA0A6.toInt()
        })
    }

    fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null; params = null
    }
}
