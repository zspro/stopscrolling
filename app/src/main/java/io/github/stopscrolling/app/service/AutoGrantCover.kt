package io.github.stopscrolling.app.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 自动授权盖板：全屏不透明窗口，盖住底下的系统设置页。
 *
 * 关键设计（三个要点，缺一不可）：
 * 1. 挂在无障碍服务上，用 TYPE_ACCESSIBILITY_OVERLAY —— 该窗口类型
 *    不需要「悬浮窗权限」，所以盖板从链条第一步就能工作，
 *    不再依赖最易碎的悬浮窗授权步骤先成功；
 * 2. 主盖板 FLAG_NOT_TOUCHABLE：不接收任何触摸 → 手势注入 / 用户触摸
 *    都穿透到底下的设置页（若盖板可触摸，会把点击全部吃掉）；
 * 3. 「停止」按钮放在另一个独立的小窗口里（可触摸），
 *    保证用户始终有逃生出口。
 *
 * 注意：TYPE_ACCESSIBILITY_OVERLAY 必须由无障碍服务实例的
 * WindowManager 添加，不能用 ApplicationContext。
 */
object AutoGrantCover {

    private var coverView: LinearLayout? = null
    private var stopView: LinearLayout? = null
    private var stepText: TextView? = null
    private var statusText: TextView? = null
    private var onCancel: (() -> Unit)? = null

    val shown: Boolean get() = coverView != null

    @SuppressLint("InflateParams")
    fun show(service: AccessibilityService, stepLabel: String, cancelCallback: () -> Unit) {
        onCancel = cancelCallback
        if (coverView != null) {
            update(stepLabel)
            return
        }
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // ---- 主盖板：不透明、不可触摸、不可聚焦 ----
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            gravity = Gravity.CENTER
        }
        val logo = TextView(service).apply {
            text = "放下手机"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(service).apply {
            text = "正在自动授权\n请不要操作手机"
            setTextColor(Color.parseColor("#9AA6B2"))
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        val step = TextView(service).apply {
            setTextColor(Color.parseColor("#7BC4A2"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = stepLabel
        }
        stepText = step
        statusText = TextView(service).apply {
            setTextColor(Color.parseColor("#5F6B77"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        root.addView(logo)
        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(service, 16) }
        )
        root.addView(
            step,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(service, 28) }
        )
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(service, 14) }
        )

        val coverParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        coverParams.gravity = Gravity.CENTER

        // ---- 停止按钮：独立小窗，只有它自己接收触摸 ----
        val stopPanel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC12181E"))
            setPadding(dp(service, 8), dp(service, 4), dp(service, 8), dp(service, 4))
        }
        val stop = Button(service).apply {
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
        val copy = Button(service).apply {
            text = "复制诊断"
            textSize = 12f
            setTextColor(Color.parseColor("#6E7B87"))
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#1A222A"))
            }
            setPadding(36, 12, 36, 12)
            setOnClickListener {
                try {
                    val cm = service.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("diag", AutoGrantController.dumpTrace())
                    )
                    android.widget.Toast.makeText(service, "诊断已复制，发给开发者即可", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
            }
        }
        stopPanel.addView(
            copy,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(service, 6) }
        )

        val stopParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        stopParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        stopParams.y = dp(service, 28)

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
            stepText = null
            statusText = null
        }
    }

    fun update(text: String) {
        stepText?.text = text
    }

    /** 实时诊断状态行（引擎每轮 tick 刷新） */
    fun updateStatus(line: String) {
        statusText?.text = line
    }

    fun hide() {
        val cover = coverView ?: return
        val stop = stopView
        coverView = null
        stopView = null
        stepText = null
        statusText = null
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
