package cz.zukuboost

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.roundToInt

/** Aplikace a vrácení herního profilu. Vše běží přes Shizuku jako shell. */
object BoostEngine {

    const val JOYOSE = "com.xiaomi.joyose"
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private val PKG_RE = Regex("^[A-Za-z0-9._]+$")
    private val VALUE_RE = Regex("^[A-Za-z0-9._:,-]+$")
    private val ID_RE = Regex("^(global|system|secure)/[A-Za-z0-9_.]+$")

    // Nastavení ukládáme jako "namespace/klíč"
    private const val HEADS_UP = "global/heads_up_notifications_enabled"
    private const val ZEN = "global/zen_mode"
    private const val LOW_POWER = "global/low_power"
    private const val XIAOMI_POWER_MODE = "system/power_mode"
    private const val XIAOMI_SPEED_MODE = "system/speed_mode"
    private val ANIMS = listOf(
        "global/window_animation_scale",
        "global/transition_animation_scale",
        "global/animator_duration_scale",
    )
    private val STD_HZ = listOf("system/peak_refresh_rate", "system/min_refresh_rate")

    // Systémové appky, které na pozadí dělají nárazovou práci (aktualizace, skenování, analytika)
    private val SYSTEM_HOGS = listOf(
        "com.android.vending",
        "com.xiaomi.mipicks",
        "com.xiaomi.discover",
        "com.miui.analytics",
        "com.miui.msa.global",
        "com.miui.bugreport",
        "com.miui.cloudbackup",
        "com.miui.gallery",
        "com.google.android.apps.photos",
    )

    private fun ns(id: String) = id.substringBefore('/')
    private fun key(id: String) = id.substringAfter('/')
    private fun put(id: String, value: String) = "settings put ${ns(id)} ${key(id)} $value"
    private fun safe(pkgs: Collection<String>) = pkgs.filter { PKG_RE.matches(it) }

    private fun parseKv(out: String): Map<String, String> = out.lines().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.toMap()

    private suspend fun readSettings(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val script = ids.joinToString("; ") { "echo \"$it=\$(settings get ${ns(it)} ${key(it)})\"" }
        return parseKv(Shell.exec(script)).filterKeys { it in ids }
    }

    /** Standardní klíče + klíče výrobce (Xiaomi: user_refresh_rate, miui_refresh_rate…), pokud existují. */
    private suspend fun detectHzKeys(): List<String> {
        val out = Shell.exec(
            "for n in system secure; do settings list \$n | " +
                "grep -E '^(peak|min|user|miui)_refresh_rate=' | sed \"s|^|\$n/|\"; done"
        )
        val found = out.lines().map { it.substringBefore('=').trim() }.filter { ID_RE.matches(it) }
        return (STD_HZ + found).distinct()
    }

    private suspend fun readBgModes(pkgs: List<String>): Map<String, String> {
        if (pkgs.isEmpty()) return emptyMap()
        val script = pkgs.joinToString("; ") {
            "echo \"$it=\$(cmd appops get $it RUN_ANY_IN_BACKGROUND | tr '\\n' ' ')\""
        }
        return parseKv(Shell.exec(script))
    }

    fun refreshRates(ctx: Context): List<Float> {
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted() ?: listOf(60f)
    }

    /** Bez limitu nejvyšší Hz, s limitem nejvyšší Hz, které jsou jeho násobkem (rovnoměrné snímky). */
    private fun targetHz(ctx: Context, cap: Int): Float {
        val rates = refreshRates(ctx)
        val max = rates.maxOrNull() ?: 60f
        if (cap <= 0) return max
        return rates.filter { it.roundToInt() % cap == 0 }.maxOrNull() ?: max
    }

    private fun gameConfigCmd(pkg: String, scale: Float, cap: Int): String {
        if (scale >= 0.99f && cap <= 0) return "cmd game reset $pkg"
        val parts = mutableListOf("cmd game set --mode 2")
        if (scale < 0.99f) parts += "--downscale " + String.format(Locale.US, "%.1f", scale)
        if (cap > 0) parts += "--fps $cap"
        return "cmd game reset $pkg; ${parts.joinToString(" ")} $pkg"
    }

    suspend fun apply(ctx: Context, pkg: String) {
        if (!PKG_RE.matches(pkg)) return
        val p = Prefs(ctx)
        val hzKeys = if (p.opt(Opt.REFRESH)) detectHzKeys() else emptyList()

        // Ukládáme originál každého klíče, který ještě uložený není.
        // Díky tomu se vrátí i nastavení zapnutá uprostřed hraní.
        val wanted = buildList {
            if (p.opt(Opt.HEADS_UP)) add(HEADS_UP)
            addAll(hzKeys)
            if (p.opt(Opt.ANIMATIONS)) addAll(ANIMS)
            if (p.opt(Opt.DND)) add(ZEN)
            if (p.opt(Opt.BATTERY_SAVER)) add(LOW_POWER)
            if (p.opt(Opt.XIAOMI_PERF)) { add(XIAOMI_POWER_MODE); add(XIAOMI_SPEED_MODE) }
        }
        val saved = p.originals().keys
        val missing = wanted.filter { it !in saved }
        if (missing.isNotEmpty()) p.saveOriginals(readSettings(missing))
        p.boostActive = true

        val cmds = mutableListOf<String>()
        if (p.opt(Opt.DND)) cmds += "cmd notification set_dnd priority"
        if (p.opt(Opt.HEADS_UP)) cmds += put(HEADS_UP, "0")
        val cap = p.fpsCap(pkg)
        val scale = p.scale(pkg)
        if (hzKeys.isNotEmpty()) {
            val hz = targetHz(ctx, cap)
            val asFloat = String.format(Locale.US, "%.1f", hz)
            val asInt = hz.roundToInt().toString()
            hzKeys.forEach { id ->
                val k = key(id)
                cmds += put(id, if (k.startsWith("peak") || k.startsWith("min")) asFloat else asInt)
            }
        }
        if (p.opt(Opt.ANIMATIONS)) ANIMS.forEach { cmds += put(it, "0") }
        if (p.opt(Opt.BATTERY_SAVER)) cmds += "cmd power set-mode 0"
        if (p.opt(Opt.GAME_MODE) && Build.VERSION.SDK_INT >= 31) cmds += "cmd game mode performance $pkg"
        // Profil hry: render rozlišení a FPS limit (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 && (scale < 0.99f || cap > 0)) cmds += gameConfigCmd(pkg, scale, cap)
        if (p.opt(Opt.XIAOMI_PERF)) {
            cmds += put(XIAOMI_POWER_MODE, "high")
            cmds += put(XIAOMI_SPEED_MODE, "1")
        }
        if (p.opt(Opt.FIXED_PERF)) {
            p.fixedPerf = true
            cmds += "cmd power set-fixed-performance-mode-enabled true"
        }
        if (p.opt(Opt.SYSTEM_HOGS)) {
            SYSTEM_HOGS.filter { it != pkg }.forEach { cmds += "am force-stop $it 2>/dev/null" }
            cmds += "cmd package cancel-bg-dexopt-job 2>/dev/null"
        }
        if (p.opt(Opt.THERMAL)) {
            p.thermalOverride = true
            cmds += "cmd thermalservice override-status 0"
        }
        if (cmds.isNotEmpty()) Shell.exec(cmds.joinToString("; "))

        if (p.opt(Opt.SYNC_OFF)) {
            runCatching {
                if (p.syncWasOn == -1) p.syncWasOn = if (ContentResolver.getMasterSyncAutomatically()) 1 else 0
                ContentResolver.setMasterSyncAutomatically(false)
            }
        }

        if (p.opt(Opt.KILL_BG)) {
            val targets = safe(p.killList - pkg - ctx.packageName - SHIZUKU_PKG)
            val kill = mutableListOf<String>()
            val newlyRestricted = mutableSetOf<String>()
            if (p.opt(Opt.RESTRICT_BG) && targets.isNotEmpty()) {
                val modes = readBgModes(targets)
                targets.forEach { t ->
                    // Appky, které už má uživatel omezené, necháme být
                    if (modes[t]?.contains("ignore") != true) {
                        kill += "cmd appops set $t RUN_ANY_IN_BACKGROUND ignore"
                        newlyRestricted += t
                    }
                }
            }
            targets.forEach { kill += "am force-stop $it" }
            kill += "am kill-all"
            // Seznam ukládáme dřív, než appky omezíme – kdyby to spadlo, i tak se vrátí
            p.restricted = p.restricted + newlyRestricted
            Shell.exec(kill.joinToString("; "))
        }
    }

    suspend fun restore(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.boostActive) return

        val cmds = mutableListOf<String>()
        for ((id, value) in p.originals()) {
            if (!ID_RE.matches(id)) continue
            when (id) {
                ZEN -> cmds += "cmd notification set_dnd " + when (value) {
                    "1" -> "priority"
                    "2" -> "on"
                    "3" -> "alarms"
                    else -> "off"
                }
                LOW_POWER -> {
                    if (value == "1") cmds += "cmd power set-mode 1"
                }
                else -> {
                    if (value.isEmpty() || value == "null") {
                        cmds += "settings delete ${ns(id)} ${key(id)}" // klíč předtím neexistoval
                    } else if (VALUE_RE.matches(value)) {
                        cmds += put(id, value)
                    }
                }
            }
        }
        if (p.thermalOverride) cmds += "cmd thermalservice reset"
        if (p.fixedPerf) cmds += "cmd power set-fixed-performance-mode-enabled false"
        if (p.syncWasOn == 1) runCatching { ContentResolver.setMasterSyncAutomatically(true) }
        safe(p.restricted).forEach { cmds += "cmd appops set $it RUN_ANY_IN_BACKGROUND allow" }
        if (cmds.isNotEmpty()) Shell.exec(cmds.joinToString("; "))
        p.clearBoostState()
    }

    /** Pojistka: vrátí skutečné hlášení teplot, hra zůstává v boostu. */
    suspend fun releaseThermal(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.thermalOverride) return
        Shell.exec("cmd thermalservice reset")
        p.thermalOverride = false
    }

    /** AOT kompilace hry v režimu speed – rychlejší start a méně záseků v Java/Kotlin části. */
    suspend fun compileSpeed(pkg: String): String {
        if (!PKG_RE.matches(pkg)) return "Neplatný balíček"
        return Shell.exec("cmd package compile -m speed -f $pkg").trim()
    }

    /** Profil hry: render rozlišení (víc FPS, méně tepla) a FPS limit (stabilní snímky). Android 13+. */
    suspend fun setGameConfig(pkg: String, scale: Float, cap: Int): String {
        if (!PKG_RE.matches(pkg)) return "Neplatný balíček"
        if (Build.VERSION.SDK_INT < 33) return "Potřebuje Android 13+"
        return Shell.exec(gameConfigCmd(pkg, scale, cap)).trim()
    }

    /** null = Joyose na zařízení není, true = zapnutý, false = vypnutý. */
    fun joyoseState(ctx: Context): Boolean? = try {
        val s = ctx.packageManager.getApplicationEnabledSetting(JOYOSE)
        s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    } catch (e: IllegalArgumentException) {
        null
    }

    suspend fun setJoyose(enabled: Boolean): String = Shell.exec(
        if (enabled) "pm enable $JOYOSE" else "am force-stop $JOYOSE; pm disable-user --user 0 $JOYOSE"
    ).trim()

    /**
     * HyperOS blokuje shellu zápis do systémových nastavení, dokud není v Možnostech pro vývojáře
     * zapnuté „Ladění USB (Bezpečnostní nastavení)“. Bez toho nefunguje Hz, Nerušit ani profil Xiaomi.
     */
    suspend fun checkSettingsWrite(ctx: Context): Boolean {
        val out = Shell.exec(
            "settings put system zukuboost_probe 1; settings put secure zukuboost_probe 1; " +
                "echo \"S=\$(settings get system zukuboost_probe) X=\$(settings get secure zukuboost_probe)\"; " +
                "settings delete system zukuboost_probe; settings delete secure zukuboost_probe"
        )
        val ok = !out.contains("Exception") && out.contains("S=1") && out.contains("X=1")
        Prefs(ctx).settingsWriteOk = if (ok) 1 else 0
        return ok
    }

    /** Otevře skrytý výběr výkonového režimu Xiaomi (pokud ho firmware má). */
    suspend fun openXiaomiPowerMenu(): String {
        val out = Shell.exec("am start -n com.android.settings/com.android.settings.fuelgauge.PowerModeSettings")
        return if (out.contains("Error") || out.contains("Exception")) "Tenhle firmware skryté menu nemá" else "Otevřeno"
    }

    /** Udělí appce přes Shizuku všechna oprávnění, která potřebuje. */
    suspend fun grantSelf(ctx: Context): String {
        val me = ctx.packageName
        val cmds = mutableListOf(
            "cmd appops set $me GET_USAGE_STATS allow",
            "cmd appops set $me SYSTEM_ALERT_WINDOW allow",
            "cmd appops set $me RUN_ANY_IN_BACKGROUND allow",
            "dumpsys deviceidle whitelist +$me",
        )
        if (Build.VERSION.SDK_INT >= 33) cmds += "pm grant $me android.permission.POST_NOTIFICATIONS"
        return Shell.exec(cmds.joinToString("; "))
    }
}
