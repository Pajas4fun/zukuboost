package cz.zukuboost

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
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

    private val CFG_RE = Regex("^[A-Za-z0-9=,.:_-]+$")
    private const val DRIVER_KEY = "updatable_driver_production_opt_in_apps"

    /**
     * Game Mode intervence podle dokumentace Androidu: konfigurace se zapisuje do
     * device_config game_overlay (mode=2 = výkon), pak se hra přepne do performance režimu.
     */
    private fun gameConfigCmd(pkg: String, scale: Float, cap: Int): String {
        val cfg = mutableListOf("mode=2")
        if (scale < 0.99f) cfg += "downscaleFactor=" + String.format(Locale.US, "%.2f", scale)
        if (cap > 0) cfg += "fps=$cap"
        return "device_config put game_overlay $pkg ${cfg.joinToString(",")}; cmd game mode performance $pkg"
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

    /** Úklid těsně před spuštěním hry z domovské obrazovky – víc volné RAM při načítání. */
    suspend fun preLaunchClean(ctx: Context, pkg: String): Int {
        val p = Prefs(ctx)
        val targets = safe(p.killList - pkg - ctx.packageName - SHIZUKU_PKG)
        val cmds = targets.map { "am force-stop $it" } + "am kill-all"
        Shell.exec(cmds.joinToString("; "))
        return targets.size
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
    suspend fun setGameConfig(ctx: Context, pkg: String, scale: Float, cap: Int): String {
        if (!PKG_RE.matches(pkg)) return "Neplatný balíček"
        if (Build.VERSION.SDK_INT < 33) return "Potřebuje Android 13+"
        val p = Prefs(ctx)
        // Záloha konfigurace od výrobce, ať ji při vypnutí vrátíme místo smazání
        if (p.gameOverlayBackup(pkg) == null) {
            val cur = Shell.exec("device_config get game_overlay $pkg").trim()
            p.setGameOverlayBackup(pkg, if (CFG_RE.matches(cur)) cur else "null")
        }
        if (scale >= 0.99f && cap <= 0) {
            val orig = p.gameOverlayBackup(pkg)
            val restore = if (orig == null || orig == "null") {
                "device_config delete game_overlay $pkg"
            } else {
                "device_config put game_overlay $pkg $orig"
            }
            return Shell.exec("$restore; cmd game reset $pkg 2>/dev/null").trim()
        }
        return Shell.exec(gameConfigCmd(pkg, scale, cap)).trim()
    }

    /** Má telefon aktualizovatelný (herní) GPU ovladač? */
    suspend fun gameDriverSupported(): Boolean = Shell.exec("getprop ro.gfx.driver.0").trim().isNotEmpty()

    suspend fun gameDriverApps(): Set<String> {
        val cur = Shell.exec("settings get global $DRIVER_KEY").trim()
        if (cur.isBlank() || cur == "null") return emptySet()
        return cur.split(",").map { it.trim() }.filter { PKG_RE.matches(it) }.toSet()
    }

    /** Přepne hru na herní GPU ovladač (projeví se po restartu hry). */
    suspend fun setGameDriver(pkg: String, on: Boolean): String {
        if (!PKG_RE.matches(pkg)) return "Neplatný balíček"
        val list = gameDriverApps().toMutableSet()
        if (on) list += pkg else list -= pkg
        val value = list.joinToString(",")
        val out = Shell.exec(
            if (value.isEmpty()) "settings delete global $DRIVER_KEY" else "settings put global $DRIVER_KEY $value"
        )
        return when {
            out.contains("Exception") -> out.trim()
            on -> "Herní GPU ovladač zapnutý – restartuj hru"
            else -> "Výchozí GPU ovladač – restartuj hru"
        }
    }

    /** null = Joyose na zařízení není, true = zapnutý, false = vypnutý nebo odebraný pro uživatele. */
    @Suppress("DEPRECATION")
    fun joyoseState(ctx: Context): Boolean? {
        val ai = try {
            ctx.packageManager.getApplicationInfo(
                JOYOSE,
                PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        if ((ai.flags and ApplicationInfo.FLAG_INSTALLED) == 0) return false
        return ai.enabled
    }

    /**
     * HyperOS nedovolí shellu systémové balíčky vypnout (disable-user),
     * proto při chybě Joyose odebereme jen pro uživatele 0. Vrátí se přes install-existing.
     */
    suspend fun setJoyose(enabled: Boolean): String {
        if (enabled) {
            val out = Shell.exec("cmd package install-existing --user 0 $JOYOSE; pm enable $JOYOSE")
            return if (out.contains("Exception")) out.trim() else "Joyose je zpět zapnutý"
        }
        val first = Shell.exec("am force-stop $JOYOSE; pm disable-user --user 0 $JOYOSE")
        if (!first.contains("Exception")) return "Joyose vypnutý"
        val second = Shell.exec("am force-stop $JOYOSE; pm uninstall -k --user 0 $JOYOSE")
        return if (second.contains("Success")) {
            "Joyose odebrán pro tvého uživatele. Vrátíš ho tlačítkem Zapnout Joyose zpět."
        } else {
            "Nepodařilo se: ${second.trim()}"
        }
    }

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

    /** Udělí appce přes Shizuku všechna oprávnění, která potřebuje. Vrací výsledek včetně chyb systému. */
    suspend fun grantSelf(ctx: Context): String {
        val me = ctx.packageName
        val cmds = mutableListOf(
            // Přístup k využití dvěma cestami: appops i „development“ oprávnění přes pm grant
            "cmd appops set $me GET_USAGE_STATS allow",
            "pm grant $me android.permission.PACKAGE_USAGE_STATS",
            "cmd appops set $me SYSTEM_ALERT_WINDOW allow",
            "cmd appops set $me RUN_ANY_IN_BACKGROUND allow",
            "dumpsys deviceidle whitelist +$me",
        )
        if (Build.VERSION.SDK_INT >= 33) cmds += "pm grant $me android.permission.POST_NOTIFICATIONS"
        val out = Shell.exec(cmds.joinToString("; "))
        val errors = out.lines().map { it.trim() }.filter {
            it.contains("Exception") || it.startsWith("Error") || it.contains("not allowed", ignoreCase = true)
        }
        return if (errors.isEmpty()) {
            "Oprávnění nastavena"
        } else {
            "Systém část odmítl: ${errors.first().take(140)} – povol to ručně tlačítky níž"
        }
    }
}
