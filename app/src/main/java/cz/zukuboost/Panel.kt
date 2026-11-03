package cz.zukuboost

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** Živá data pro herní panel. */
data class PanelData(
    val fps: Int?,
    val avg: Int?,
    val min: Int?,
    val drops: Int,
    val cpu: Float?,
    val bat: Float,
    val history: List<Int>,
    val measuring: Boolean,
)

/**
 * Herní panel ve hře – HUD styl: rotující neonový rámeček, velké živé FPS s grafem,
 * tepelné pruhy, kulatá akční tlačítka se září. Data se v otevřeném panelu obnovují každou sekundu.
 */
class GamePanelController(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onScreenshot()
        fun onRecord()
        fun onTouchLock()
        fun onToggleOverlay()
        fun onCleanRam()
        fun onToggleEdges()
        fun isRecording(): Boolean
        fun isOverlayOn(): Boolean
        fun isEdgesOn(): Boolean
        fun gameName(): String
        fun data(): PanelData
    }

    private companion object {
        val RED = 0xFFFF2D55.toInt()
        val CYAN = 0xFF00E5FF.toInt()
        val GREEN = 0xFF00E676.toInt()
        val YELLOW = 0xFFFFC400.toInt()
        val PURPLE = 0xFF9D4DFF.toInt()
        val DIM = 0xFF8A90A2.toInt()
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var handle: View? = null
    private var panel: View? = null
    private var borderAnim: ValueAnimator? = null
    private var updater: Runnable? = null

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    // ================= Úchyt =================

    private val handleParams = WindowManager.LayoutParams(
        dp(24),
        dp(84),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.END or Gravity.TOP
        y = (ctx.resources.displayMetrics.heightPixels * 0.3f).toInt()
        alpha = 0.8f
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (handle != null) return
        val pill = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(RED, PURPLE, CYAN)).apply {
                cornerRadius = dpf(4f)
            }
            elevation = dpf(6f)
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = RED
                outlineAmbientShadowColor = RED
            }
        }
        val root = FrameLayout(ctx).apply {
            clipChildren = false
            addView(pill, FrameLayout.LayoutParams(dp(5), dp(64), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(3) })
        }
        var downY = 0f
        var startY = 0
        var moved = false
        root.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startY = handleParams.y
                    moved = false
                    pill.animate().scaleX(1.7f).setDuration(120).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > dp(8)) moved = true
                    if (moved) {
                        val maxY = (ctx.resources.displayMetrics.heightPixels - dp(90)).coerceAtLeast(0)
                        handleParams.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(v, handleParams) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pill.animate().scaleX(1f).setDuration(150).start()
                    if (e.action == MotionEvent.ACTION_UP && !moved) togglePanel()
                }
            }
            true
        }
        runCatching { wm.addView(root, handleParams) }.onSuccess { handle = root }
    }

    fun hide() {
        removePanelNow()
        handle?.let { runCatching { wm.removeView(it) } }
        handle = null
    }

    fun setHandleVisible(visible: Boolean) {
        handle?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!visible) removePanelNow()
    }

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun stopLive() {
        borderAnim?.cancel()
        borderAnim = null
        updater?.let { handler.removeCallbacks(it) }
        updater = null
    }

    fun closePanel() {
        val p = panel ?: return
        panel = null
        stopLive()
        p.animate()
            .alpha(0f)
            .translationX(dpf(60f))
            .setDuration(170)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { runCatching { wm.removeView(p) } }
            .start()
    }

    private fun removePanelNow() {
        stopLive()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun refresh() {
        removePanelNow()
        openPanel(animate = false)
    }

    // ================= Vlastní kreslené prvky =================

    /** Rotující neonový rámeček (SweepGradient) + HUD rohové značky. */
    private inner class NeonBorder : View(ctx) {
        var angle = 0f
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.6f)
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(2.4f)
            color = CYAN
            strokeCap = Paint.Cap.ROUND
        }
        private val m = Matrix()
        private val rect = RectF()

        override fun onDraw(c: Canvas) {
            val inset = dpf(1f)
            rect.set(inset, inset, width - inset, height - inset)
            val shader = SweepGradient(width / 2f, height / 2f, intArrayOf(RED, PURPLE, CYAN, 0x3300E5FF, RED), null)
            m.setRotate(angle, width / 2f, height / 2f)
            shader.setLocalMatrix(m)
            stroke.shader = shader
            val r = dpf(26f)
            c.drawRoundRect(rect, r, r, stroke)
            // HUD značky v rozích
            val l = dpf(14f)
            val o = dpf(9f)
            c.drawLine(o, o + l, o, o + dpf(4f), corner)
            c.drawLine(o + dpf(4f), o, o + l, o, corner)
            c.drawLine(width - o, height - o - l, width - o, height - o - dpf(4f), corner)
            c.drawLine(width - o - dpf(4f), height - o, width - o - l, height - o, corner)
        }
    }

    /** Graf FPS za poslední desítky sekund. */
    private inner class Spark : View(ctx) {
        var data: List<Int> = emptyList()
            set(v) {
                field = v
                invalidate()
            }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(2f)
            color = CYAN
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val grid = Paint().apply {
            color = 0x1AFFFFFF
            strokeWidth = 1f
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            for (i in 1..2) c.drawLine(0f, h * i / 3f, w, h * i / 3f, grid)
            if (data.size < 2) return
            val top = max(data.maxOrNull() ?: 60, 30).toFloat()
            val step = w / (data.size - 1)
            val path = Path()
            var lx = 0f
            var ly = 0f
            data.forEachIndexed { i, v ->
                lx = i * step
                ly = h - (v / top) * (h - dpf(4f)) - dpf(2f)
                if (i == 0) path.moveTo(lx, ly) else path.lineTo(lx, ly)
            }
            val area = Path(path).apply {
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            fill.shader = LinearGradient(0f, 0f, 0f, h, 0x6600E5FF, 0x0000E5FF, Shader.TileMode.CLAMP)
            c.drawPath(area, fill)
            c.drawPath(path, line)
            c.drawCircle(lx, ly, dpf(3f), dot)
        }
    }

    /** Tepelný pruh: barva podle pozice (zelená → žlutá → červená). */
    private inner class HeatBar : View(ctx) {
        var fraction = 0f
            set(v) {
                field = v.coerceIn(0f, 1f)
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(c: Canvas) {
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(rect, r, r, track)
            bar.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(GREEN, YELLOW, RED), null, Shader.TileMode.CLAMP)
            rect.set(0f, 0f, max(width * fraction, height.toFloat()), height.toFloat())
            c.drawRoundRect(rect, r, r, bar)
        }
    }

    private fun label(text: String, color: Int, size: Float, bold: Boolean = false, spacing: Float = 0f) =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            letterSpacing = spacing
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

    // ================= Panel =================

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun openPanel(animate: Boolean = true) {
        if (panel != null) return
        val width = dp(286)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        // ---- hlavička ----
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(
            label("ZUKUBOOST", Color.WHITE, 15f, bold = true, spacing = 0.2f).apply {
                val w = paint.measureText(text.toString())
                paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.WHITE, RED, CYAN), null, Shader.TileMode.CLAMP)
            },
        )
        titles.addView(label(listener.gameName().uppercase(), DIM, 10f, spacing = 0.12f).apply { maxLines = 1 })
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val live = label("● LIVE", RED, 9f, bold = true, spacing = 0.12f).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dpf(20f)
                setColor(0x22FF2D55)
                setStroke(dp(1), 0x66FF2D55)
            }
        }
        header.addView(live)
        header.addView(
            label("✕", DIM, 14f).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x1FFFFFFF)
                }
                setOnClickListener { closePanel() }
            },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) },
        )
        content.addView(header)

        // ---- velké FPS + graf ----
        val fpsBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x2200E5FF, 0x0800E5FF)).apply {
                cornerRadius = dpf(18f)
                setStroke(dp(1), 0x3300E5FF)
            }
        }
        val fpsCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val fpsNum = label("--", CYAN, 34f, bold = true).apply { includeFontPadding = false }
        fpsCol.addView(fpsNum)
        fpsCol.addView(label("FPS", DIM, 9f, bold = true, spacing = 0.2f))
        fpsBlock.addView(fpsCol, LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT))
        val spark = Spark()
        fpsBlock.addView(spark, LinearLayout.LayoutParams(0, dp(46), 1f))
        content.addView(fpsBlock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val statLine = label("", DIM, 10f).apply { setPadding(dp(4), dp(6), 0, 0) }
        content.addView(statLine)

        // ---- teploty ----
        fun heatRow(name: String): Pair<TextView, HeatBar> {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, 0)
            }
            row.addView(label(name, DIM, 9.5f, bold = true, spacing = 0.12f), LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT))
            val bar = HeatBar()
            row.addView(bar, LinearLayout.LayoutParams(0, dp(7), 1f))
            val value = label("--", Color.WHITE, 11f, bold = true).apply { gravity = Gravity.END }
            row.addView(value, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
            content.addView(row)
            return value to bar
        }
        val (cpuText, cpuBar) = heatRow("CPU")
        val (batText, batBar) = heatRow("BATERIE")

        // ---- oddělovač ----
        content.addView(
            View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FF2D55, 0x99FF2D55.toInt(), 0x0000E5FF))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(14), 0, dp(12)) },
        )

        // ---- akce ----
        val rec = listener.isRecording()
        val actions = listOf(
            Action("📸", "Snímek", false, RED, true) { listener.onScreenshot() },
            Action(if (rec) "⏹" else "⏺", if (rec) "Stop" else "Nahrát", rec, RED, true) { listener.onRecord() },
            Action("🔒", "Zámek", false, PURPLE, true) { listener.onTouchLock() },
            Action("📊", "Overlay", listener.isOverlayOn(), CYAN, false) { listener.onToggleOverlay() },
            Action("🖐", "Okraje", listener.isEdgesOn(), CYAN, false) { listener.onToggleEdges() },
            Action("🧹", "Vyčistit", false, GREEN, true) { listener.onCleanRam() },
        )
        val actionViews = mutableListOf<View>()
        actions.chunked(3).forEach { chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { a ->
                val v = actionView(a)
                actionViews += v
                row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { bottomMargin = dp(8) })
            }
            content.addView(row)
        }

        // ---- kořen: sklo + obsah + rotující rámeček ----
        val border = NeonBorder()
        val root = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF5171B27.toInt(), 0xF5090B10.toInt())).apply {
                cornerRadius = dpf(26f)
            }
            elevation = dpf(14f)
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = RED
                outlineAmbientShadowColor = CYAN
            }
            addView(content, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(border, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel()
            false
        }

        // ---- živá data ----
        fun update() {
            val d = listener.data()
            fpsNum.text = d.fps?.toString() ?: "--"
            fpsNum.setTextColor(
                when {
                    d.fps == null -> DIM
                    d.avg != null && d.fps < d.avg * 0.75f -> RED // právě propad
                    else -> CYAN
                },
            )
            spark.data = d.history
            statLine.text = if (d.measuring) {
                "ø ${d.avg ?: "--"}   ·   min ${d.min ?: "--"}   ·   ⚠ propady ${d.drops}"
            } else {
                "Zapni 📊 Overlay pro živé FPS"
            }
            val cpu = d.cpu
            cpuText.text = cpu?.let { "${it.toInt()}°" } ?: "--"
            cpuBar.fraction = cpu?.let { (it - 30f) / 60f } ?: 0f
            batText.text = if (d.bat > 0f) "${d.bat.toInt()}°" else "--"
            batBar.fraction = (d.bat - 25f) / 25f
            live.alpha = if (live.alpha > 0.6f) 0.45f else 1f // blikající LIVE
        }
        update()
        val tick = object : Runnable {
            override fun run() {
                if (panel == null) return
                update()
                handler.postDelayed(this, 1_000)
            }
        }
        updater = tick

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(30)
        }
        if (animate) {
            root.alpha = 0f
            root.translationX = dpf(70f)
            actionViews.forEach {
                it.alpha = 0f
                it.translationY = dpf(10f)
            }
        }
        runCatching { wm.addView(root, params) }.onSuccess {
            panel = root
            handler.postDelayed(tick, 1_000)
            borderAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 4_000
                // 2,5 otáčky a dost – nekonečné překreslování by bralo GPU hře, když necháš panel otevřený
                repeatCount = 2
                interpolator = LinearInterpolator()
                addUpdateListener {
                    border.angle = it.animatedValue as Float
                    border.invalidate()
                }
                start()
            }
            if (animate) {
                root.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(OvershootInterpolator(0.7f)).start()
                actionViews.forEachIndexed { i, v ->
                    v.animate().alpha(1f).translationY(0f).setStartDelay(120L + i * 40L).setDuration(220).start()
                }
            }
        }
    }

    private class Action(
        val icon: String,
        val label: String,
        val active: Boolean,
        val accent: Int,
        val closeAfter: Boolean,
        val run: () -> Unit,
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun actionView(a: Action): View {
        val circle = TextView(ctx).apply {
            text = a.icon
            textSize = 19f
            gravity = Gravity.CENTER
            background = if (a.active) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a.accent, (a.accent and 0x00FFFFFF) or 0x55000000)).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), Color.WHITE)
                }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor((a.accent and 0x00FFFFFF) or 0x24000000)
                    setStroke(dp(1), (a.accent and 0x00FFFFFF) or 0x77000000)
                }
            }
            elevation = if (a.active) dpf(10f) else 0f
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = a.accent
                outlineAmbientShadowColor = a.accent
            }
        }
        val text = label(a.label, if (a.active) a.accent else 0xFFCFD3DC.toInt(), 10f, bold = a.active).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            setPadding(0, dp(4), 0, dp(2))
            addView(circle, LinearLayout.LayoutParams(dp(50), dp(50)))
            addView(text)
        }
        box.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> circle.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                MotionEvent.ACTION_UP -> {
                    circle.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
                    if (inside) {
                        if (a.closeAfter) closePanel()
                        a.run()
                        if (!a.closeAfter) refresh()
                    }
                }
                MotionEvent.ACTION_CANCEL -> circle.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            true
        }
        return box
    }
}
