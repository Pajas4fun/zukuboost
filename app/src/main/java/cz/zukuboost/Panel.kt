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
        val RED = 0xFFFF3D6E.toInt()
        val CYAN = 0xFF38E8FF.toInt()
        val GREEN = 0xFFC6FF3D.toInt()
        val YELLOW = 0xFFFFC94D.toInt()
        val PURPLE = 0xFF8B6CFF.toInt()
        val DIM = 0xFF8C90A8.toInt()
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

    // Stejné technické písmo jako v appce
    private val displayBold: Typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.chakra_bold) }
        .getOrNull() ?: Typeface.DEFAULT_BOLD
    private val displaySemi: Typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.chakra_semibold) }
        .getOrNull() ?: Typeface.DEFAULT_BOLD

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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(RED, RED, PURPLE)).apply {
                cornerRadius = dpf(1.5f)
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

    val isOpen: Boolean get() = panel != null

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

    /** Zkosený tvar (vlevo nahoře a vpravo dole) – stejný jako v appce. */
    private fun chamferPath(w: Float, h: Float, cut: Float) = Path().apply {
        moveTo(cut, 0f)
        lineTo(w, 0f)
        lineTo(w, h - cut)
        lineTo(w - cut, h)
        lineTo(0f, h)
        lineTo(0f, cut)
        close()
    }

    /** Pozadí se zkosenými rohy, volitelně obrys, pruh vlevo a pruh dole. */
    private inner class ChamferDrawable(
        private val fillTop: Int,
        private val fillBottom: Int,
        private val cut: Float,
        private val strokeColor: Int = 0,
        private val leftBar: Int = 0,
        private val bottomBar: Int = 0,
    ) : android.graphics.drawable.Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1f)
        }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val path = chamferPath(w, h, cut)
            c.save()
            c.translate(bounds.left.toFloat(), bounds.top.toFloat())
            fill.shader = LinearGradient(0f, 0f, 0f, h, fillTop, fillBottom, Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            if (strokeColor != 0) {
                stroke.color = strokeColor
                c.drawPath(path, stroke)
            }
            if (leftBar != 0 || bottomBar != 0) {
                c.clipPath(path)
                if (leftBar != 0) {
                    bar.color = leftBar
                    c.drawRect(0f, 0f, dpf(3f), h, bar)
                }
                if (bottomBar != 0) {
                    bar.color = bottomBar
                    c.drawRect(0f, h - dpf(2f), w, h, bar)
                }
            }
            c.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** HUD rohové značky (statické – nepřekreslují se, nezatěžují hru). */
    private inner class HudCorners : View(ctx) {
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(2f)
            color = CYAN
        }

        override fun onDraw(c: Canvas) {
            val l = dpf(14f)
            val o = dpf(8f)
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawLine(o + dpf(10f), o, o + dpf(10f) + l, o, corner)
            c.drawLine(w - o, o, w - o - l, o, corner)
            c.drawLine(w - o, o, w - o, o + l, corner)
            c.drawLine(o, h - o, o + l, h - o, corner)
            c.drawLine(o, h - o, o, h - o - l, corner)
            c.drawLine(w - o - dpf(10f) - l, h - o, w - o - dpf(10f), h - o, corner)
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

    /** Segmentová lišta jako HP ve hře; barva podle teploty (zelená → žlutá → červená). */
    private inner class HeatBar : View(ctx) {
        var fraction = 0f
            set(v) {
                field = v.coerceIn(0f, 1f)
                invalidate()
            }
        private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
        private val on = Paint(Paint.ANTI_ALIAS_FLAG)
        private val seg = Path()

        override fun onDraw(c: Canvas) {
            val n = 12
            val gap = dpf(2f)
            val w = (width - gap * (n - 1)) / n
            val h = height.toFloat()
            val k = h * 0.4f
            val lit = (fraction * n).toInt().coerceAtLeast(if (fraction > 0f) 1 else 0)
            on.color = when {
                fraction > 0.75f -> RED
                fraction > 0.5f -> YELLOW
                else -> GREEN
            }
            for (i in 0 until n) {
                val x = i * (w + gap)
                seg.reset()
                seg.moveTo(x + k, 0f)
                seg.lineTo(x + w, 0f)
                seg.lineTo(x + w - k, h)
                seg.lineTo(x, h)
                seg.close()
                c.drawPath(seg, if (i < lit) on else off)
            }
        }
    }

    private fun label(text: String, color: Int, size: Float, bold: Boolean = false, spacing: Float = 0f) =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            letterSpacing = spacing
            typeface = if (bold) displaySemi else Typeface.DEFAULT
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
            label("ZukuBoost", Color.WHITE, 17f, bold = true).apply { typeface = displayBold },
        )
        titles.addView(label(listener.gameName(), DIM, 11f).apply { maxLines = 1 })
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val live = label("● Live", RED, 10f, bold = true).apply {
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
            background = ChamferDrawable(0x2238E8FF, 0x0838E8FF, dpf(12f), strokeColor = 0x3338E8FF)
        }
        val fpsCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val fpsNum = label("--", CYAN, 38f, bold = true).apply {
            includeFontPadding = false
            typeface = displayBold
        }
        fpsCol.addView(fpsNum)
        fpsCol.addView(label("FPS", DIM, 11f, bold = true))
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
            row.addView(label(name, DIM, 11f), LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
            val bar = HeatBar()
            row.addView(bar, LinearLayout.LayoutParams(0, dp(8), 1f))
            val value = label("--", Color.WHITE, 13f, bold = true).apply {
                gravity = Gravity.END
                typeface = displayBold
            }
            row.addView(value, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
            content.addView(row)
            return value to bar
        }
        val (cpuText, cpuBar) = heatRow("CPU")
        val (batText, batBar) = heatRow("Battery")

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
            Action("📸", "Screenshot", false, RED, true) { listener.onScreenshot() },
            Action(if (rec) "⏹" else "⏺", if (rec) "Stop" else "Record", rec, RED, true) { listener.onRecord() },
            Action("🔒", "Lock", false, PURPLE, true) { listener.onTouchLock() },
            Action("📊", "Overlay", listener.isOverlayOn(), CYAN, false) { listener.onToggleOverlay() },
            Action("🖐", "Edges", listener.isEdgesOn(), CYAN, false) { listener.onToggleEdges() },
            Action("🧹", "Clean", false, GREEN, true) { listener.onCleanRam() },
        )
        val actionViews = mutableListOf<View>()
        actions.chunked(3).forEach { chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { a ->
                val v = actionView(a)
                actionViews += v
                row.addView(
                    v,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), 0, dp(3), dp(6))
                    },
                )
            }
            content.addView(row)
        }

        // ---- kořen: zkosený panel, plazmový pruh vlevo, HUD rohy ----
        val border = HudCorners()
        val cut = dpf(18f)
        val root = FrameLayout(ctx).apply {
            background = ChamferDrawable(0xF7131520.toInt(), 0xF7090A10.toInt(), cut, strokeColor = 0x14FFFFFF, leftBar = RED)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                @Suppress("DEPRECATION")
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val path = chamferPath(view.width.toFloat(), view.height.toFloat(), cut)
                    if (Build.VERSION.SDK_INT >= 30) outline.setPath(path) else outline.setConvexPath(path)
                }
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
                "ø ${d.avg ?: "--"}   ·   min ${d.min ?: "--"}   ·   ⚠ drops ${d.drops}"
            } else {
                "Turn on 📊 Overlay for live FPS"
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
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val text = label(a.label, if (a.active) a.accent else 0xFFCFD3DC.toInt(), 10.5f, bold = a.active).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            background = if (a.active) {
                ChamferDrawable((a.accent and 0x00FFFFFF) or 0x33000000, (a.accent and 0x00FFFFFF) or 0x14000000, dpf(9f), bottomBar = a.accent)
            } else {
                ChamferDrawable(0x12FFFFFF, 0x08FFFFFF, dpf(9f), strokeColor = (a.accent and 0x00FFFFFF) or 0x44000000)
            }
            addView(circle, LinearLayout.LayoutParams(dp(34), dp(28)))
            addView(text)
        }
        box.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
                    if (inside) {
                        if (a.closeAfter) closePanel()
                        a.run()
                        if (!a.closeAfter) refresh()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            true
        }
        return box
    }
}
