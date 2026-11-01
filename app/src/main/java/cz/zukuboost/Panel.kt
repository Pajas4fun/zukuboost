package cz.zukuboost

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Herní panel ve hře (jako sidebar v Game Turbu / Game Genie u ROG).
 * Moderní vzhled: tmavé „sklo“ s neonovým přechodovým rámečkem, živé statistiky jako čipy,
 * dlaždice s ikonami v kruhu, vysouvací animace a odezva na stisk.
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

        /** Dvojice (popisek, hodnota) – FPS, průměr, teploty… */
        fun chips(): List<Pair<String, String>>
    }

    private companion object {
        val RED = 0xFFFF2D55.toInt()
        val CYAN = 0xFF00E5FF.toInt()
        val GREEN = 0xFF00E676.toInt()
        val DIM = 0xFF8A90A2.toInt()
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private var handle: View? = null
    private var panel: View? = null

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    // ---------------- Úchyt ----------------

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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(RED, CYAN)).apply {
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
            addView(
                pill,
                FrameLayout.LayoutParams(dp(5), dp(64), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(3) },
            )
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
                    pill.animate().scaleX(1.6f).setDuration(120).start()
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

    /** Zavření s animací vysunutí doprava. */
    fun closePanel() {
        val p = panel ?: return
        panel = null
        p.animate()
            .alpha(0f)
            .translationX(dpf(48f))
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { runCatching { wm.removeView(p) } }
            .start()
    }

    private fun removePanelNow() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun refresh() {
        removePanelNow()
        openPanel(animate = false)
    }

    // ---------------- Panel ----------------

    /** Tmavé sklo s neonovým přechodovým rámečkem (LayerDrawable: přechod + vnitřní výplň). */
    private fun glass(radius: Float, border: Int = dp(1)): Drawable {
        val outer = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(RED, 0x66FF2D55, 0x6600E5FF, CYAN),
        ).apply { cornerRadius = radius }
        val inner = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF2161A26.toInt(), 0xF20A0C12.toInt()),
        ).apply { cornerRadius = radius - border }
        return LayerDrawable(arrayOf(outer, inner)).apply { setLayerInset(1, border, border, border, border) }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun openPanel(animate: Boolean = true) {
        if (panel != null) return
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = glass(dpf(26f))
            elevation = dpf(12f)
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = RED
                outlineAmbientShadowColor = CYAN
            }
        }

        // --- hlavička: neonové logo + název hry + zavřít ---
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val logo = TextView(ctx).apply {
            text = "ZUKUBOOST"
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 15f
            letterSpacing = 0.18f
            val w = paint.measureText(text.toString())
            paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.WHITE, RED, CYAN), null, Shader.TileMode.CLAMP)
        }
        val game = TextView(ctx).apply {
            text = "● " + listener.gameName().uppercase()
            setTextColor(GREEN)
            textSize = 10f
            letterSpacing = 0.1f
            maxLines = 1
        }
        titles.addView(logo)
        titles.addView(game)
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            TextView(ctx).apply {
                text = "✕"
                setTextColor(DIM)
                textSize = 16f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x22FFFFFF)
                }
                setOnClickListener { closePanel() }
            },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(10) },
        )
        box.addView(header)

        // --- živé statistiky jako čipy ---
        val chips = listener.chips()
        if (chips.isNotEmpty()) {
            val chipRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(4))
            }
            chips.forEachIndexed { i, (label, value) ->
                val accent = if (i == 0) CYAN else Color.WHITE
                val chip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    background = GradientDrawable().apply {
                        cornerRadius = dpf(12f)
                        setColor(if (i == 0) 0x2200E5FF else 0x14FFFFFF)
                        if (i == 0) setStroke(dp(1), 0x6600E5FF)
                    }
                    addView(
                        TextView(ctx).apply {
                            text = value
                            setTextColor(accent)
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            textSize = if (i == 0) 17f else 13f
                            gravity = Gravity.CENTER
                        },
                    )
                    addView(
                        TextView(ctx).apply {
                            text = label
                            setTextColor(DIM)
                            textSize = 8.5f
                            letterSpacing = 0.12f
                            gravity = Gravity.CENTER
                        },
                    )
                }
                chipRow.addView(
                    chip,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = dp(6)
                    },
                )
            }
            box.addView(chipRow, LinearLayout.LayoutParams(dp(276), LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // --- oddělovač ---
        box.addView(
            View(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0x00FF2D55, 0x88FF2D55.toInt(), 0x0000E5FF),
                )
            },
            LinearLayout.LayoutParams(dp(276), dp(1)).apply { setMargins(0, dp(10), 0, dp(10)) },
        )

        // --- dlaždice 3 × 2 ---
        val rec = listener.isRecording()
        val tiles = listOf(
            Tile("📸", "Snímek", false, RED, true) { listener.onScreenshot() },
            Tile(if (rec) "⏹" else "⏺", if (rec) "Stop" else "Nahrát", rec, RED, true) { listener.onRecord() },
            Tile("🔒", "Zámek", false, RED, true) { listener.onTouchLock() },
            Tile("📊", "Overlay", listener.isOverlayOn(), CYAN, false) { listener.onToggleOverlay() },
            Tile("🖐", "Okraje", listener.isEdgesOn(), CYAN, false) { listener.onToggleEdges() },
            Tile("🧹", "Vyčistit", false, GREEN, true) { listener.onCleanRam() },
        )
        tiles.chunked(3).forEach { rowTiles ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEachIndexed { i, t ->
                row.addView(
                    tileView(t),
                    LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        if (i > 0) marginStart = dp(6)
                        bottomMargin = dp(6)
                    },
                )
            }
            box.addView(row)
        }

        // Dotyk mimo panel ho zavře (a projde dál do hry)
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel()
            false
        }

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
            box.alpha = 0f
            box.translationX = dpf(56f)
            box.scaleX = 0.96f
            box.scaleY = 0.96f
        }
        runCatching { wm.addView(box, params) }.onSuccess {
            panel = box
            if (animate) {
                box.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(0.8f))
                    .start()
            }
        }
    }

    private class Tile(
        val icon: String,
        val label: String,
        val active: Boolean,
        val accent: Int,
        val closeAfter: Boolean,
        val action: () -> Unit,
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun tileView(t: Tile): View {
        val iconBubble = TextView(ctx).apply {
            text = t.icon
            textSize = 18f
            gravity = Gravity.CENTER
            background = if (t.active) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.accent, 0x33FFFFFF)).apply {
                    shape = GradientDrawable.OVAL
                }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor((t.accent and 0x00FFFFFF) or 0x2E000000)
                }
            }
        }
        val label = TextView(ctx).apply {
            text = t.label
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(if (t.active) t.accent else Color.WHITE)
            typeface = if (t.active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setPadding(0, dp(6), 0, 0)
        }
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dpf(16f)
                setColor(if (t.active) (t.accent and 0x00FFFFFF) or 0x1F000000 else 0x12FFFFFF)
                setStroke(dp(1), if (t.active) t.accent else 0x1AFFFFFF)
            }
            addView(iconBubble, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(label)
        }
        // odezva na stisk: krátké zmenšení
        tile.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
                    if (inside) {
                        if (t.closeAfter) closePanel()
                        t.action()
                        if (!t.closeAfter) refresh()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            true
        }
        return tile
    }
}
