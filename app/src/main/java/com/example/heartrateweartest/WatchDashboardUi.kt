package com.example.heartrateweartest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * One-column, scrollable, thumb-friendly AMOLED dashboard for a round Wear OS screen.
 * All three actions are real Android touch targets; no image or WebView overlays.
 */
class WatchDashboardUi(
    private val context: Context,
    onToggle: () -> Unit,
    onTestVibration: () -> Unit,
    onSettings: () -> Unit,
) {
    companion object {
        private val BG = Color.rgb(7, 12, 19)
        private val PANEL = Color.rgb(21, 30, 40)
        private val PANEL_BORDER = Color.rgb(42, 57, 70)
        private val MINT = Color.rgb(89, 240, 195)
        private val WHITE = Color.rgb(240, 248, 250)
        private val MUTED = Color.rgb(147, 167, 180)
        private val AMBER = Color.rgb(255, 199, 109)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()

    private fun surface(color: Int, radius: Int, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                       else Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    private fun frame(width: Int, height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, dp(height)).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private val bpmText = label("--", 44f, WHITE, true)
    private val motionText = label("等待运动数据", 15f, WHITE, true)
    private val motionDetail = label("运动评分 --", 11f, MUTED)
    private val connectionText = label("正在等待网关", 11f, MUTED, true)
    private val monitoringText = label("未开始监测", 11f, MUTED)
    private val statusText = label("点击开始，读取真实心率", 11f, MUTED)
    private val gauge = PulseRing(context, MINT)
    private val mainButton = label("开始监测", 16f, BG, true)
    private val stateDot = label("●", 10f, MUTED)

    val view: View

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Wider padding near the round screen's clipped top/bottom.
            setPadding(dp(17), dp(12), dp(17), dp(29))
        }

        val emblem = label("✦  P H A N T H Y", 11f, MINT, true)
        emblem.letterSpacing = .16f
        column.addView(emblem, frame(LinearLayout.LayoutParams.MATCH_PARENT, 16))

        val title = label("WEARABLE AGENT", 12f, WHITE, true).apply {
            letterSpacing = .09f
        }
        column.addView(title, frame(LinearLayout.LayoutParams.MATCH_PARENT, 17))

        val orb = FrameLayout(context)
        orb.addView(gauge, FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER))
        val inside = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        inside.addView(label("♥", 13f, MINT))
        inside.addView(bpmText)
        inside.addView(label("BPM", 9f, MUTED, true))
        orb.addView(inside, FrameLayout.LayoutParams(dp(98), dp(98), Gravity.CENTER))
        column.addView(orb, frame(LinearLayout.LayoutParams.MATCH_PARENT, 102))

        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = surface(PANEL, 20, PANEL_BORDER)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        strip.addView(motionText)
        strip.addView(motionDetail)


        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(10))
        }
        line.addView(stateDot)
        line.addView(connectionText)


        mainButton.apply {
            background = surface(MINT, 29)
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48)
            contentDescription = "开始监测"
            setOnClickListener { onToggle() }
        }
        column.addView(mainButton, frame(dp(148), 48))
        column.addView(monitoringText, frame(LinearLayout.LayoutParams.MATCH_PARENT, 27))
        column.addView(strip, frame(dp(166), 55))
        column.addView(line, frame(LinearLayout.LayoutParams.MATCH_PARENT, 37))

        val vibrationButton = label("◉  测试双次振动", 14f, WHITE, true).apply {
            background = surface(PANEL, 27, PANEL_BORDER)
            isClickable = true
            isFocusable = true
            contentDescription = "测试双次振动"
            setOnClickListener { onTestVibration() }
        }
        column.addView(vibrationButton, frame(dp(170), 50))

        val settingsButton = label("⚙  连接设置", 13f, MUTED, true).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "连接设置"
            setOnClickListener { onSettings() }
        }
        column.addView(settingsButton, frame(dp(170), 49))

        statusText.maxLines = 3
        statusText.setPadding(dp(8), dp(4), dp(8), 0)
        column.addView(statusText, frame(LinearLayout.LayoutParams.MATCH_PARENT, 52))

        view = ScrollView(context).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
        }
    }

    fun updateHeartRate(bpm: Double?) {
        bpmText.text = bpm?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
        gauge.setReading(bpm)
    }

    fun updateMotion(state: String, score: Double) {
        val text = when (state) {
            "still" -> "静止状态"
            "moving" -> "正在运动"
            else -> "等待运动数据"
        }
        motionText.text = text
        motionText.setTextColor(if (state == "moving") AMBER else WHITE)
        motionDetail.text = if (state == "unknown") "运动评分 --"
                            else String.format(Locale.US, "运动评分 %.2f", score)
    }

    fun updateMonitoring(active: Boolean) {
        mainButton.text = if (active) "停止监测" else "开始监测"
        mainButton.contentDescription = mainButton.text
        mainButton.background = surface(if (active) PANEL else MINT, 29,
                                         if (active) PANEL_BORDER else null)
        mainButton.setTextColor(if (active) WHITE else BG)
        monitoringText.text = if (active) "●  传感器监听中" else "○  监测已暂停"
        monitoringText.setTextColor(if (active) MINT else MUTED)
        gauge.setActive(active)
    }

    fun updateConnection(configured: Boolean, online: Boolean) {
        connectionText.text = when {
            !configured -> "未配置局域网"
            online -> "网关在线 · Wi-Fi"
            else -> "等待网关连接"
        }
        stateDot.setTextColor(if (online) MINT else if (configured) AMBER else MUTED)
    }

    fun showStatus(message: String) {
        statusText.text = message
    }

    private class PulseRing(context: Context, private val accent: Int) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 52, 62)
            style = Paint.Style.STROKE
            strokeWidth = 8f * resources.displayMetrics.density
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 8f * resources.displayMetrics.density
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(21, 46, 47)
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private var pulse = 0.12f
        private var active = false

        fun setReading(value: Double?) {
            pulse = if (value == null) 0.12f else
                ((value.toFloat() - 40f) / 120f).coerceIn(0.12f, 0.95f)
            invalidate()
        }

        fun setActive(value: Boolean) { active = value; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val inset = 12f * resources.displayMetrics.density
            val box = RectF(inset, inset, width - inset, height - inset)
            canvas.drawCircle(cx, cy, cx - inset - 13f * resources.displayMetrics.density, halo)
            canvas.drawArc(box, 0f, 360f, false, track)
            ring.alpha = if (active) 255 else 100
            canvas.drawArc(box, -90f, 360f * pulse, false, ring)
        }
    }
}
