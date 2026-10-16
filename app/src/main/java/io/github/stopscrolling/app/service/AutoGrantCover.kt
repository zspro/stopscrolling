package io.github.stopscrolling.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 自动授权盖板：全屏不透明悬浮窗，盖住底下的系统设置页。
 *
 * 关键设计（两个窗口，缺一不可）：
 * 1. 主盖板 FLAG_NOT_TOUCHABLE：不接收任何触摸 → 手势注入 / 用户触摸
 *    都会穿透到底下的设置页（无障碍点击靠 ACTION_CLICK 或手势坐标，
 *    手势走输入管线，若盖板可触摸就会把点击全部吃掉）；
 * 2. 「停止」按钮放在另一个独立的小悬浮窗里（可触摸、范围之外穿透），
 *    保证用户始终有逃生出口。
 */
object AutoGrantCover {

    private var coverView: LinearLayout? = null
    private var stopView: LinearLayout? = null
    private var stepText: TextView? = null
    private var onCancel: (() -> Unit)? = null

    val shown: Boolean get() = coverView != null

    @SuppressLint("InflateParams")
    fun show(context: Context, stepLabel: String, cancelCallback: () -> Unit) {
        onCancel = cancelCallback
        if (coverView != null) {
            update(stepLabel)
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // ---- 主盖板：不透明、不可触摸、不可聚焦 ----
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            gravity = Gravity.CENTER
        }
        val logo = TextView(context).apply {
            setText("放下手机")
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(context).apply {
            setText("正在自动授权\n请不要操作手机")
            setTextColor(Color.parseColor("#9AA6B2"))
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        val step = TextView(context).apply {
            setTextColor(Color.parseColor("#7BC4A2"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setText(stepLabel)
        }
        stepText = step
        root.addView(logo)
        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 16) }
        )
        root.addView(
            step,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 28) }
        )

        val coverParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        if (Build.VERSION.SDK_INT >= 28) {
            coverParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        coverParams.gravity = Gravity.CENTER

        // ---- 停止按钮：独立小窗，只有它自己接收触摸 ----
        val stopPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC12181E"))
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
        }
        val stop = Button(context).apply {
            text = "停止自动授权"
            textSize = 14f
            setTextColor(Color.parseColor("#B9C4CF"))
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#222B33"))
            }
            setPadding(48, 20, 48, 20)
            setOnClickListener {
                val cb = onCancel
                hide()
                cb?.invoke()
            }
        }
        stopPanel.addView(stop)

        val stopParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        stopParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        stopParams.y = dp(context, 28)

        try {
            wm.addView(root, coverParams)
            wm.addView(stopPanel, stopParams)
            coverView = root
            stopView = stopPanel
        } catch (_: Exception) {
            removeQuietly(wm, root)
            removeQuietly(wm, stopPanel)
            coverView = null
            stopView = null
        }
    }

    fun update(text: String) {
        stepText?.text = text
    }

    fun hide() {
        val cover = coverView ?: return
        val stop = stopView
        coverView = null
        stopView = null
        stepText = null
        onCancel = null
        val wm = cover.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        removeQuietly(wm, cover)
        if (stop != null) removeQuietly(wm, stop)
    }

    private fun removeQuietly(wm: WindowManager, view: android.view.View) {
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
