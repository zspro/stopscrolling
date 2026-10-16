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
 * 原理：窗口带 FLAG_NOT_FOCUSABLE，不抢输入焦点 → rootInActiveWindow
 * 仍然是底下的设置页，无障碍照常读取并点击（无障碍点击不经过触摸层）；
 * 用户看到的只有盖板上的进度提示。
 *
 * 盖板右下角有「停止自动授权」按钮，防止自动化卡死把用户困住；
 * 自动化失败也会自动移除盖板并回到引导页。
 */
object AutoGrantCover {

    private var container: LinearLayout? = null
    private var stepText: TextView? = null
    private var onCancel: (() -> Unit)? = null

    val shown: Boolean get() = container != null

    @SuppressLint("InflateParams")
    fun show(context: Context, stepLabel: String, cancelCallback: () -> Unit) {
        onCancel = cancelCallback
        if (container != null) {
            update(stepLabel)
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

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

        val stop = Button(context).apply {
            text = "停止自动授权"
            textSize = 14f
            setTextColor(Color.parseColor("#B9C4CF"))
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#222B33"))
            }
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                val cb = onCancel
                hide()
                cb?.invoke()
            }
        }

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
        root.addView(
            stop,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 64) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        params.gravity = Gravity.CENTER

        try {
            wm.addView(root, params)
            container = root
        } catch (_: Exception) {
            container = null
        }
    }

    fun update(text: String) {
        stepText?.text = text
    }

    fun hide() {
        val view = container ?: return
        container = null
        stepText = null
        onCancel = null
        try {
            (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
