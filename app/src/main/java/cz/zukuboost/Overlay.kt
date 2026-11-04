package cz.zukuboost

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 120
        alpha = 0.8f // nad 0.8 by Android 12+ blokoval dotyky skrz overlay
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show() {
        if (view != null) return
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 10.5f
            // Pevná minimální šířka a počet řádků: okno nemění velikost při každé změně čísel
            // (změna velikosti okna = přepočet rozvržení v systému a může cuknout snímek)
            minWidth = (ctx.resources.displayMetrics.density * 214).toInt()
            minLines = 5
            typeface = Typeface.MONOSPACE
            setPadding(20, 12, 20, 12)
            background = GradientDrawable().apply {
                cornerRadius = 22f
                setColor(0xCC000000.toInt())
            }
            text = "ZukuBoost…"
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(tv, params) }.onSuccess { view = tv }
    }

    fun update(text: String) {
        val v = view ?: return
        if (v.text.toString() != text) v.text = text
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}

/**
 * Zámek dotyku (jako Touch protection u Samsungu / ochrana proti nechtěnému dotyku u OnePlus):
 * průhledná vrstva přes celou obrazovku spolkne všechny dotyky. Odemčení podržením tlačítka 1,5 s.
 * Systémová gesta (návrat, domů) fungují dál.
 */
class TouchLockController(private val ctx: Context, private val onUnlocked: () -> Unit) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null

    val isLocked: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun lock() {
        if (root != null) return
        val label = TextView(ctx).apply {
            text = "🔒 Touch locked – hold 1.5 s to unlock"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(40, 24, 40, 24)
            background = GradientDrawable().apply {
                cornerRadius = 44f
                setColor(0xDDFF2D55.toInt())
            }
        }
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(0x1A000000)
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { topMargin = 64 },
            )
            setOnTouchListener { _, _ -> true } // spolkne všechny dotyky mimo tlačítko
        }
        val unlockTask = Runnable {
            unlock()
            onUnlocked()
        }
        label.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    label.alpha = 0.6f
                    handler.postDelayed(unlockTask, 1_500)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    label.alpha = 1f
                    handler.removeCallbacks(unlockTask)
                }
            }
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(frame, params) }.onSuccess { root = frame }
    }

    fun unlock() {
        handler.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}

/** Blokované okraje (jako Blocked touch edges u ASUS ROG): pruhy vlevo a vpravo spolknou dotyk dlaně. */
class EdgeBlockController(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<View>()

    val isShown: Boolean get() = views.isNotEmpty()

    @SuppressLint("ClickableViewAccessibility")
    fun show(widthDp: Int) {
        if (views.isNotEmpty()) return
        val px = (widthDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        for (side in listOf(Gravity.LEFT, Gravity.RIGHT)) {
            val v = View(ctx).apply { setOnTouchListener { _, _ -> true } }
            val params = WindowManager.LayoutParams(
                px,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = side or Gravity.TOP
                // Neviditelné okno se neskládá do obrazu (nebere hardwarovou vrstvu displeje = žádná zátěž GPU),
                // dotyky ale dál zachytí
                alpha = 0f
            }
            runCatching { wm.addView(v, params) }.onSuccess { views += v }
        }
    }

    fun hide() {
        views.forEach { runCatching { wm.removeView(it) } }
        views.clear()
    }
}

fun batteryTemp(ctx: Context): Float {
    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
}

/** Statistiky herní relace: ø/min FPS, počet propadů, max. teploty. */
class SessionStats {
    private var count = 0
    private var sum = 0L
    private var minFps = Int.MAX_VALUE
    private var dropCount = 0
    private var inDrop = false
    private var cpuMax: Float? = null
    private var batMax: Float? = null

    @Synchronized
    fun add(fps: Int) {
        if (fps <= 0) return
        // Prvních 10 s je načítání – nepočítá se do minima ani propadů
        if (count >= 10) {
            val avgNow = sum.toFloat() / count
            val low = fps < avgNow * 0.75f
            if (low && !inDrop) dropCount++
            inDrop = low
            if (fps < minFps) minFps = fps
        }
        count++
        sum += fps
    }

    @Synchronized
    fun noteTemps(cpu: Float?, bat: Float?) {
        if (cpu != null) cpuMax = maxOf(cpuMax ?: cpu, cpu)
        if (bat != null && bat > 0f) batMax = maxOf(batMax ?: bat, bat)
    }

    @Synchronized
    fun reset() {
        count = 0
        sum = 0L
        minFps = Int.MAX_VALUE
        dropCount = 0
        inDrop = false
        cpuMax = null
        batMax = null
    }

    val avg: Int?
        @Synchronized get() = if (count == 0) null else (sum / count).toInt()

    val min: Int?
        @Synchronized get() = if (minFps == Int.MAX_VALUE) null else minFps

    val drops: Int
        @Synchronized get() = dropCount

    val maxCpu: Float?
        @Synchronized get() = cpuMax

    val maxBat: Float?
        @Synchronized get() = batMax
}

class Stats(private val ctx: Context) {
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val am = ctx.getSystemService(ActivityManager::class.java)
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val tempRe = Regex("mValue=(-?[0-9.]+), mType=(\\d+)")
    private var cpu: Float? = null
    private var gpu: Float? = null
    private var tick = 0
    private var prevTotal = 0L
    private var prevIdle = 0L
    private var cpuLoad: Int? = null
    private var gpuLoad: Int? = null
    private var curGhz: Float? = null
    private var maxGhz: Float? = null

    /** Poslední hodnoty pro herní panel. */
    val cpuTemp: Float? get() = cpu
    var lastBatTemp: Float = 0f
        private set
    val cpuLoadPct: Int? get() = cpuLoad

    /**
     * Jedním voláním: teploty z thermal HAL, vytížení CPU (/proc/stat), aktuální a maximální takty CPU
     * (pokles pod maximum = throttling) a vytížení GPU Adreno. Co systém nedovolí číst, se přeskočí.
     */
    private suspend fun readSensors() {
        val out = runCatching {
            Shell.exec(
                "dumpsys thermalservice; echo '@@STAT'; head -1 /proc/stat 2>/dev/null; " +
                    // GPU: Adreno (Snapdragon), MediaTek GED, Mali – co systém dovolí číst
                    "echo '@@GPU'; cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null || " +
                    "cat /sys/kernel/ged/hal/gpu_utilization 2>/dev/null || cat /sys/class/misc/mali0/device/utilization 2>/dev/null; " +
                    "echo '@@CUR'; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null; " +
                    "echo '@@MAX'; cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null; echo '@@END'"
            )
        }.getOrNull() ?: return

        fun section(name: String): String {
            val a = out.indexOf("@@$name")
            if (a < 0) return ""
            val b = out.indexOf("@@", a + 2 + name.length).let { if (it < 0) out.length else it }
            return out.substring(a + 2 + name.length, b)
        }

        // teploty
        val start = out.indexOf("Current temperatures from HAL")
        if (start >= 0) {
            val end = out.indexOf("Current cooling devices", start).let { if (it < 0) out.length else it }
            val byType = tempRe.findAll(out.substring(start, end)).mapNotNull { m ->
                val value = m.groupValues[1].toFloatOrNull()
                val type = m.groupValues[2].toIntOrNull()
                if (value == null || type == null || value <= 0f || value > 150f) null else type to value
            }.toList().groupBy({ it.first }, { it.second })
            cpu = byType[0]?.maxOrNull()
            gpu = byType[1]?.maxOrNull()
        }

        // vytížení CPU z rozdílu dvou měření
        val stat = section("STAT").trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (stat.size >= 5) {
            val idle = stat[3] + stat[4]
            val total = stat.sum()
            if (prevTotal > 0 && total > prevTotal) {
                cpuLoad = (100 * (1 - (idle - prevIdle).toDouble() / (total - prevTotal))).toInt().coerceIn(0, 100)
            }
            prevTotal = total
            prevIdle = idle
        }

        gpuLoad = Regex("(\\d+)").find(section("GPU"))?.value?.toIntOrNull()?.takeIf { it in 0..100 }
        curGhz = section("CUR").lines().mapNotNull { it.trim().toLongOrNull() }.maxOrNull()?.let { it / 1_000_000f }
        maxGhz = section("MAX").lines().mapNotNull { it.trim().toLongOrNull() }.maxOrNull()?.let { it / 1_000_000f }
    }

    suspend fun format(fps: Int?, session: SessionStats, elapsedMs: Long): String {
        if (tick++ % 4 == 0) readSensors()
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batTemp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        lastBatTemp = batTemp
        val voltageMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val watts = power(voltageMv)
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val sec = elapsedMs / 1000
        session.noteTemps(cpu, batTemp)
        val avg = session.avg
        val min = session.min
        val drops = session.drops

        return buildString {
            append("FPS ").append(fps?.toString() ?: "--")
            if (avg != null) append("  ø$avg")
            if (min != null) append("  min $min")
            if (drops > 0) append("  ⚠$drops")
            append('\n')
            append("CPU ").append(deg(cpu)).append("  GPU ").append(deg(gpu)).append("  BAT ").append(deg(batTemp))
            append('\n')
            if (watts != null) append(String.format(Locale.US, "%.1f W  ", watts))
            append(String.format(Locale.US, "RAM %.1f GB", mem.availMem / 1_073_741_824f))
            val label = thermalLabel(thermal)
            if (label.isNotEmpty()) append("  ").append(label)
            append('\n')
            // Řádek jako Game Genie u ROG: vytížení a takty (aktuální/max = vidíš throttling)
            val load = buildList {
                cpuLoad?.let { add("CPU $it%") }
                val c = curGhz
                val m = maxGhz
                if (c != null && m != null) add(String.format(Locale.US, "%.1f/%.1f GHz", c, m))
                gpuLoad?.let { add("GPU $it%") }
            }
            if (load.isNotEmpty()) append(load.joinToString("  ")).append('\n')
            append(String.format(Locale.US, "⏱ %d:%02d", sec / 60, sec % 60))
        }
    }

    private fun deg(v: Float?) = if (v == null) "--" else String.format(Locale.US, "%.0f°", v)

    /** Aktuální příkon z baterie ve wattech (výrobci hlásí proud v µA nebo mA). */
    private fun power(voltageMv: Int): Double? {
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == 0 || raw == Int.MIN_VALUE || voltageMv <= 0) return null
        var microAmps = abs(raw.toLong())
        if (microAmps < 20_000) microAmps *= 1000
        val watts = microAmps * voltageMv / 1e9
        return if (watts in 0.05..40.0) watts else null
    }

    private fun thermalLabel(s: Int) = when (s) {
        0 -> "OK"
        1 -> "warm"
        2 -> "throttling"
        3 -> "heavy throttling"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> ""
    }
}
