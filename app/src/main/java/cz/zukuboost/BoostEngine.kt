package cz.zukuboost

import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
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
    private const val BRIGHT_MODE = "system/screen_brightness_mode"
    private const val PAPER_MODE = "system/screen_paper_mode_enabled"
    private const val LOCATION_MODE = "secure/location_mode"
    private const val MOBILE_ALWAYS_ON = "global/mobile_data_always_on"
    private val XIAOMI_NET_KEYS = listOf(
        "system/wifi_assistant",
        "system/nearby_scanning_enabled",
        "system/mishare_near_share",
    )
    private const val XIAOMI_SPEED_MODE = "system/speed_mode"
    private const val XIAOMI_SPEED_MODE_ENABLE = "secure/speed_mode_enable"
    private const val MILLET = "system/MILLET_NO_RESTRICT_APP"
    private const val WIFI_SCAN_ALWAYS = "global/wifi_scan_always_enabled"
    private const val BLE_SCAN_ALWAYS = "global/ble_scan_always_enabled"
    private const val WIFI_SCAN_THROTTLE = "global/wifi_scan_throttle_enabled"
    private val SYNC_MODES = setOf("none", "persistent", "until_reboot")
    private val BUCKET_NAMES = mapOf(
        "10" to "active",
        "20" to "working_set",
        "30" to "frequent",
        "40" to "rare",
        "45" to "restricted",
    )

    fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")
    private val ANIMS = listOf(
        "global/window_animation_scale",
        "global/transition_animation_scale",
        "global/animator_duration_scale",
    )
    private val STD_HZ = listOf("system/peak_refresh_rate", "system/min_refresh_rate")

    private val VOICE_APPS = listOf(
        "com.discord",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.skype.raider",
        "com.microsoft.teams",
    )

    /**
     * Doporučené výjimky: zprávy, hovory, hudba a hodinky. Ukončená appka do dalšího otevření
     * nedostává oznámení – bez těchhle výjimek by po hře nechodily zprávy.
     */
    val DEFAULT_KEEP = listOf(
        "com.discord", "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.viber.voip", "com.snapchat.android", "com.instagram.android",
        "com.microsoft.teams", "com.skype.raider", "com.spotify.music", "com.google.android.apps.youtube.music",
        "deezer.android.app", "com.xiaomi.wearable", "com.mi.health", "com.huami.watch.hmwatchmanager",
        "com.google.android.apps.wearables.maestro.companion", "com.google.android.wearable.app",
        "com.samsung.android.app.watchmanager", "com.garmin.android.apps.connectmobile",
    )

    @Volatile var lastKilled = 0
        private set

    /** Appka nainstalovaná uživatelem (ne systémová ani aktualizovaná systémová). */
    private fun isUserApp(ctx: Context, pkg: String): Boolean = runCatching {
        val f = ctx.packageManager.getApplicationInfo(pkg, 0).flags
        (f and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
            (f and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    }.getOrDefault(false)

    /**
     * Co se při spuštění hry ukončí. V režimu „vše kromě výjimek“: všechny běžící uživatelské appky
     * kromě výjimek a kromě toho, co nesmí spadnout – launcher, klávesnice, služby usnadnění,
     * appky čtoucí oznámení (hodinky), trvalá VPN, živá tapeta, hlasové appky, hra, Shizuku, ZukuBoost.
     */
    /** Výsledek analýzy pozadí: co běželo, co se ukončí (s pamětí v MB), kolik zůstane. */
    data class BgAnalysis(val running: Int, val killed: List<Pair<String, Int>>, val kept: Int) {
        val killedMb: Int get() = killed.sumOf { it.second }
    }

    @Volatile var lastAnalysis: BgAnalysis? = null
        private set

    suspend fun killTargets(ctx: Context, pkg: String?): List<String> {
        val p = Prefs(ctx)
        val base = setOfNotNull(pkg, ctx.packageName, SHIZUKU_PKG)

        // Jedním voláním: kritické appky (klávesnice, usnadnění, čtení oznámení, VPN) + běžící procesy s pamětí
        val out = Shell.exec(
            "echo \"IME=\$(settings get secure default_input_method)\"; " +
                "echo \"A11Y=\$(settings get secure enabled_accessibility_services)\"; " +
                "echo \"NL=\$(settings get secure enabled_notification_listeners)\"; " +
                "echo \"VPN=\$(settings get secure always_on_vpn_app)\"; " +
                "echo '@@PS'; ps -A -o RSS,NAME"
        )
        val kv = parseKv(out.substringBefore("@@PS"))
        val critical = mutableSetOf<String>()
        listOf("IME", "A11Y", "NL", "VPN").forEach { k ->
            kv[k]?.split(':', ',')?.map { it.substringBefore('/').trim() }?.filter { PKG_RE.matches(it) }?.let { critical += it }
        }
        runCatching {
            ctx.packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()?.let { critical += it }
        runCatching { android.app.WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName }.getOrNull()?.let { critical += it }
        if (p.opt(Opt.PROTECT_VOICE)) critical += VOICE_APPS

        // Paměť (RSS v kB) sečtená za všechny procesy jedné appky
        val memKb = mutableMapOf<String, Long>()
        out.substringAfter("@@PS").lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val kb = parts[0].toLongOrNull() ?: return@forEach
                val name = parts[1].substringBefore(':')
                if (PKG_RE.matches(name) && name.contains('.')) memKb[name] = (memKb[name] ?: 0L) + kb
            }
        }
        val userRunning = memKb.keys.filter { it !in base && isUserApp(ctx, it) }

        val targets = if (p.killMode == 0) {
            safe(p.killList - base)
        } else {
            userRunning.filter { it !in p.keepList && it !in critical }
        }
        val killedWithMem = targets.filter { it in memKb }
            .map { it to ((memKb[it] ?: 0L) / 1024).toInt() }
            .sortedByDescending { it.second }
        lastAnalysis = BgAnalysis(userRunning.size, killedWithMem, (userRunning - targets.toSet()).size)
        return targets
    }

    /** Čitelný název appky. */
    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Shrnutí analýzy pro animaci a notifikaci, např. „ukončeno 9 appek (~1,2 GB): TikTok, Chrome, Facebook“. */
    fun analysisSummary(ctx: Context): String {
        val a = lastAnalysis ?: return ""
        if (a.killed.isEmpty()) return "Nothing unnecessary is running in the background"
        val mb = a.killedMb
        val size = if (mb >= 1024) String.format(Locale.US, "~%.1f GB", mb / 1024f) else "~$mb MB"
        val top = a.killed.take(3).joinToString(", ") { appLabel(ctx, it.first) }
        return "Closed ${a.killed.size} apps ($size): $top" + if (a.killed.size > 3) "…" else ""
    }

    private fun isInstalled(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

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
        // Xiaomi propojení zařízení / sdílení obrazovky – neustále skenují Wi-Fi a Bluetooth
        "com.xiaomi.mi_connect_service",
        "com.milink.service",
        // Analýza obsahu obrazovky a „rychlé aplikace“ běžící na pozadí
        "com.miui.contentcatcher",
        "com.miui.hybrid",
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
                "grep -E '^((peak|min|user|miui)_refresh_rate|is_smart_fps)=' | sed \"s|^|\$n/|\"; done"
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

    /**
     * Podporované Hz. HyperOS v API občas nevrací všechny režimy (např. jen do 90 Hz),
     * proto přidáváme i hodnoty zjištěné z dumpsys display.
     */
    fun refreshRates(ctx: Context): List<Float> {
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val api = display?.supportedModes?.map { it.refreshRate } ?: emptyList()
        val all = (api + Prefs(ctx).extraRates).filter { it in 24f..241f }
        return all.distinctBy { it.roundToInt() }.sorted().ifEmpty { listOf(60f) }
    }

    /** Přečte skutečné režimy displeje přes dumpsys (jen hodnoty od 60 Hz výš). */
    suspend fun detectDisplayRates(ctx: Context): List<Float> {
        val out = Shell.exec("dumpsys display | grep -oE '(fps|refreshRate|peakRefreshRate)=[0-9.]+' | sort -u")
        val found = Regex("=([0-9.]+)").findAll(out)
            .mapNotNull { it.groupValues[1].toFloatOrNull() }
            .filter { it in 59f..241f }
            .distinctBy { it.roundToInt() }
            .toList()
        if (found.isNotEmpty()) Prefs(ctx).extraRates = found
        return refreshRates(ctx)
    }

    /** Hz, na kterých hra startuje (bez chlazení). */
    fun sessionHz(ctx: Context, pkg: String): Float =
        if (Prefs(ctx).opt(Opt.REFRESH)) targetHz(ctx, Prefs(ctx).fpsCap(pkg)) else currentHz(ctx)

    fun currentHz(ctx: Context): Float =
        ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f

    /** Ztlumení jasu při přehřátí; vrací původní hodnotu (pro návrat), nebo null. */
    suspend fun dimBrightness(factor: Float): String? {
        val cur = Shell.exec("settings get system screen_brightness").trim()
        val v = cur.toIntOrNull() ?: return null
        Shell.exec("settings put system screen_brightness ${(v * factor).toInt().coerceAtLeast(1)}")
        return cur
    }

    /** Vrátí Hz klíče na hodnoty před hrou (chlazení bez volby „Hz displeje podle hry“). */
    suspend fun restoreHz(ctx: Context) {
        val hzRe = Regex("^(system|secure)/((peak|min|user|miui)_refresh_rate|is_smart_fps)$")
        val cmds = Prefs(ctx).originals().filterKeys { hzRe.matches(it) }.map { (id, value) ->
            if (value.isEmpty() || value == "null") "settings delete ${ns(id)} ${key(id)}" else put(id, value)
        }
        if (cmds.isNotEmpty()) Shell.exec(cmds.joinToString("; "))
    }

    suspend fun setBrightness(value: String) {
        if (value.toIntOrNull() != null) Shell.exec("settings put system screen_brightness $value")
    }

    /** Bez limitu nejvyšší Hz, s limitem nejvyšší Hz, které jsou jeho násobkem (rovnoměrné snímky). */
    private fun targetHz(ctx: Context, cap: Int): Float {
        // Ruční volba má přednost (i když ji systém v seznamu režimů nehlásí)
        val manual = Prefs(ctx).hzMode
        if (manual > 0) return manual.toFloat()
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
        val scaleStr = String.format(Locale.US, "%.2f", scale)
        val cfg = mutableListOf("mode=2")
        if (scale < 0.99f) cfg += "downscaleFactor=$scaleStr"
        if (cap > 0) cfg += "fps=$cap"
        // Druhá cesta přímo přes GameManager (oficiální adb příkaz) – pojistka, kdyby device_config systém ignoroval
        val set = mutableListOf("cmd game set --mode 2")
        if (scale < 0.99f) set += "--downscale $scaleStr"
        if (cap > 0) set += "--fps $cap"
        return "device_config put game_overlay $pkg ${cfg.joinToString(",")}; " +
            "${set.joinToString(" ")} $pkg; cmd game mode performance $pkg"
    }

    suspend fun apply(ctx: Context, pkg: String) {
        if (!PKG_RE.matches(pkg)) return
        val p = Prefs(ctx)
        // Hz klíče ukládáme i pro chytré chlazení (mění Hz za běhu), zapisujeme je jen s volbou REFRESH
        val hzKeys = if (p.opt(Opt.REFRESH) || p.opt(Opt.SMART_COOL)) detectHzKeys() else emptyList()
        if (hzKeys.isNotEmpty()) runCatching { detectDisplayRates(ctx) }

        // Ukládáme originál každého klíče, který ještě uložený není.
        // Díky tomu se vrátí i nastavení zapnutá uprostřed hraní.
        val wanted = buildList {
            if (p.opt(Opt.HEADS_UP)) add(HEADS_UP)
            addAll(hzKeys)
            if (p.opt(Opt.ANIMATIONS)) addAll(ANIMS)
            if (p.opt(Opt.DND) || p.opt(Opt.HIDE_NOTIF)) add(ZEN)
            if (p.opt(Opt.BATTERY_SAVER)) add(LOW_POWER)
            if (p.opt(Opt.XIAOMI_PERF) && isXiaomi()) {
                add(XIAOMI_POWER_MODE)
                add(XIAOMI_SPEED_MODE)
                add(XIAOMI_SPEED_MODE_ENABLE)
            }
            if (p.opt(Opt.MILLET) && isXiaomi()) add(MILLET)
            if (p.opt(Opt.SCAN_OFF)) {
                add(WIFI_SCAN_ALWAYS)
                add(BLE_SCAN_ALWAYS)
                add(WIFI_SCAN_THROTTLE)
            }
            if (p.opt(Opt.DISPLAY)) { add(BRIGHT_MODE); add(PAPER_MODE) }
            if (p.opt(Opt.LOCATION_OFF)) add(LOCATION_MODE)
            if (p.opt(Opt.NETWORK_QUIET)) { add(MOBILE_ALWAYS_ON); addAll(XIAOMI_NET_KEYS) }
        }
        val saved = p.originals().keys
        val missing = wanted.filter { it !in saved }
        if (missing.isNotEmpty()) p.saveOriginals(readSettings(missing))
        p.boostActive = true

        val cmds = mutableListOf<String>()
        if (p.opt(Opt.DND) || p.opt(Opt.HIDE_NOTIF)) {
            cmds += "cmd notification set_dnd " + if (p.opt(Opt.BLOCK_CALLS)) "alarms" else "priority"
        }
        if (p.opt(Opt.HEADS_UP)) cmds += put(HEADS_UP, "0")
        val cap = p.fpsCap(pkg)
        val scale = p.scale(pkg)
        if (hzKeys.isNotEmpty() && p.opt(Opt.REFRESH)) {
            val hz = targetHz(ctx, cap)
            val asFloat = String.format(Locale.US, "%.1f", hz)
            val asInt = hz.roundToInt().toString()
            hzKeys.forEach { id ->
                val k = key(id)
                cmds += put(
                    id,
                    when {
                        k == "is_smart_fps" -> "0" // Xiaomi „chytré“ snižování Hz vypnout
                        k.startsWith("peak") || k.startsWith("min") -> asFloat
                        else -> asInt
                    },
                )
            }
            // Vynucení preferovaného režimu displeje (rozlišení + Hz) přes DisplayManager
            if (p.opt(Opt.FORCE_MODE) && Build.VERSION.SDK_INT >= 31) {
                val mode = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
                if (mode != null) {
                    if (p.userModeBackup == null) {
                        val cur = Shell.exec("cmd display get-user-preferred-display-mode 2>&1").trim()
                        val nums = Regex("[0-9]+(\\.[0-9]+)?").findAll(cur).map { it.value }.toList()
                        p.userModeBackup = if (nums.size >= 3 && !cur.contains("null", true)) nums.take(3).joinToString(" ") else "none"
                    }
                    cmds += "cmd display set-user-preferred-display-mode ${mode.physicalWidth} ${mode.physicalHeight} $asFloat"
                }
            }
        }
        if (p.opt(Opt.ANIMATIONS)) ANIMS.forEach { cmds += put(it, "0") }
        if (p.opt(Opt.BATTERY_SAVER)) cmds += "cmd power set-mode 0"
        if (p.opt(Opt.GAME_MODE) && Build.VERSION.SDK_INT >= 31) cmds += "cmd game mode performance $pkg"
        // Profil hry: render rozlišení a FPS limit (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 && (scale < 0.99f || cap > 0)) {
            // Google Play služby umí device_config přepsat ze serveru – během hry synchronizaci zastavíme
            if (p.syncModeOrig == null) {
                val mode = Shell.exec("device_config get_sync_disabled_for_tests").trim()
                p.syncModeOrig = if (mode in SYNC_MODES) mode else "none"
            }
            cmds += "device_config set_sync_disabled_for_tests persistent"
            cmds += gameConfigCmd(pkg, scale, cap)
        }
        if (p.opt(Opt.DISPLAY)) {
            cmds += put(BRIGHT_MODE, "0") // 0 = ruční jas, zůstane aktuální úroveň
            // Režim čtení (Xiaomi) jen pokud ho systém má
            val paper = p.originals()[PAPER_MODE]
            if (paper != null && paper != "null") cmds += put(PAPER_MODE, "0")
        }
        if (p.opt(Opt.LOCATION_OFF)) cmds += "cmd location set-location-enabled false"
        if (p.opt(Opt.NETWORK_QUIET)) {
            cmds += put(MOBILE_ALWAYS_ON, "0")
            val orig = p.originals()
            // Klíče Xiaomi měníme jen tam, kde v systému existují
            XIAOMI_NET_KEYS.forEach { id ->
                val v = orig[id]
                if (v != null && v != "null") cmds += put(id, "0")
            }
        }
        if (p.opt(Opt.XIAOMI_PERF) && isXiaomi()) {
            cmds += put(XIAOMI_POWER_MODE, "high")
            // speed_mode se po restartu vrací na 0 – nastavujeme ho při každé hře
            cmds += put(XIAOMI_SPEED_MODE, "1")
            cmds += put(XIAOMI_SPEED_MODE_ENABLE, "1")
        }
        // Cloud Xiaomi GPU volbu v Game Turbu po čase skryje – obnovíme ji, pokud ji uživatel chce
        if (p.gpuTunerWanted) cmds += "settings put global GPUTUNER_SWITCH true"
        if (p.opt(Opt.MILLET) && isXiaomi()) {
            val orig = p.originals()[MILLET]
            val empty = orig == null || orig == "null" || orig.isBlank()
            // Seznam Xiaomi měníme jen když mu rozumíme – jinak by ho po hře nešlo přesně vrátit
            if (empty || VALUE_RE.matches(orig!!)) {
                val base = if (empty) emptyList() else orig!!.split(",").filter { it.isNotBlank() }
                val voice = VOICE_APPS.filter { (p.killMode == 1 || it !in p.killList) && isInstalled(ctx, it) }
                val added = (listOf(pkg) + voice).filter { PKG_RE.matches(it) && it !in base }
                if (added.isNotEmpty()) cmds += put(MILLET, (base + added).joinToString(","))
            }
        }
        if (p.opt(Opt.SCAN_OFF)) {
            cmds += "cmd wifi set-scan-always-available disabled"
            cmds += put(BLE_SCAN_ALWAYS, "0")
            cmds += put(WIFI_SCAN_THROTTLE, "1")
        }
        if (p.opt(Opt.FIXED_PERF)) {
            p.fixedPerf = true
            cmds += "cmd power set-fixed-performance-mode-enabled true"
        }
        if (p.opt(Opt.SYSTEM_HOGS)) {
            (SYSTEM_HOGS + DeviceDetector.vendorHogs(DeviceDetector.quickBrand()))
                .filter { it != pkg }
                .forEach { cmds += "am force-stop $it 2>/dev/null" }
            cmds += "cmd package cancel-bg-dexopt-job 2>/dev/null"
            cmds += "pm art cancel-bg-dexopt-job 2>/dev/null" // Android 14+ (ART Service)
        }
        if (p.opt(Opt.THERMAL)) {
            p.thermalOverride = true
            cmds += "cmd thermalservice override-status 0"
        }
        if (cmds.isNotEmpty()) Shell.exec(cmds.joinToString("; "))

        if (p.opt(Opt.HIDE_NOTIF)) runCatching { hideNotifications(ctx, p) }

        if (p.opt(Opt.SYNC_OFF)) {
            runCatching {
                if (p.syncWasOn == -1) p.syncWasOn = if (ContentResolver.getMasterSyncAutomatically()) 1 else 0
                ContentResolver.setMasterSyncAutomatically(false)
            }
        }

        if (p.opt(Opt.PROTECT_VOICE)) {
            // Hlasové appky + samotná hra: systém je během hraní neuspí ani neomezí
            val targets = (VOICE_APPS.filter { it != pkg && (p.killMode == 1 || it !in p.killList) && isInstalled(ctx, it) } + pkg).distinct()
            if (targets.isNotEmpty()) {
                val whitelist = Shell.exec("dumpsys deviceidle whitelist")
                val added = targets.filter { !whitelist.contains(it) }
                p.voiceAdded = p.voiceAdded + added
                val voiceCmds = added.map { "dumpsys deviceidle whitelist +$it" } +
                    targets.map { "am set-standby-bucket $it active" }
                Shell.exec(voiceCmds.joinToString("; "))
            }
        }

        // Co se ukončí (a komu se odřízne internet) – spočítá se jednou
        val killList = if (p.opt(Opt.KILL_BG) || p.opt(Opt.NET_BLOCK_BG)) runCatching { killTargets(ctx, pkg) }.getOrDefault(emptyList()) else emptyList()

        // Firewall přes connectivity chain 3 (stejný mechanismus jako ShizuWall), Android 11+
        if (p.opt(Opt.NET_BLOCK_BG) && Build.VERSION.SDK_INT >= 30) {
            val stores = if (p.opt(Opt.SYSTEM_HOGS)) {
                listOf("com.android.vending", "com.xiaomi.mipicks", "com.xiaomi.discover")
            } else {
                emptyList()
            }
            val targets = safe(
                (killList + stores) - pkg - ctx.packageName - SHIZUKU_PKG - VOICE_APPS.toSet()
            ).filter { isInstalled(ctx, it) }
            if (targets.isNotEmpty()) {
                // Uložit předem, ať se odblokují i při pádu uprostřed
                p.netBlocked = p.netBlocked + targets
                Shell.exec(
                    "cmd connectivity set-chain3-enabled true; " +
                        targets.joinToString("; ") { "cmd connectivity set-package-networking-enabled false $it" }
                )
            }
        }

        if (p.opt(Opt.KILL_BG)) {
            val targets = killList
            lastKilled = targets.size
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
            // Standby bucket „restricted“ – omezí joby, alarmy a síť appek na pozadí
            if (p.opt(Opt.RESTRICT_BG) && targets.isNotEmpty()) {
                val buckets = parseKv(
                    Shell.exec(targets.joinToString("; ") { "echo \"$it=\$(am get-standby-bucket $it)\"" })
                )
                val saved = mutableSetOf<String>()
                targets.forEach { t ->
                    val b = buckets[t]?.trim()
                    // 5 = výjimka systému, 45 = už omezené – ty necháme být
                    if (b != null && b in BUCKET_NAMES.keys && b != "45") {
                        saved += "$t=$b"
                        kill += "am set-standby-bucket $t restricted"
                    }
                }
                val already = p.bucketOrig.map { it.substringBefore('=') }.toSet()
                p.bucketOrig = p.bucketOrig + saved.filter { it.substringBefore('=') !in already }
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
        val targets = killTargets(ctx, pkg)
        val cmds = targets.map { "am force-stop $it" } + "am kill-all"
        Shell.exec(cmds.joinToString("; "))
        return targets.size
    }

    /** Změna Hz během hry (automatické Hz podle FPS). Originály už jsou uložené z apply(). */
    suspend fun setSessionHz(ctx: Context, hz: Float): String {
        val keys = detectHzKeys().filter { !it.endsWith("is_smart_fps") }
        val asFloat = String.format(Locale.US, "%.1f", hz)
        val asInt = hz.roundToInt().toString()
        val cmds = keys.map { id ->
            val k = key(id)
            put(id, if (k.startsWith("peak") || k.startsWith("min")) asFloat else asInt)
        }.toMutableList()
        // Při vynuceném režimu displeje je potřeba změnit i ten (jinak by chlazení nefungovalo)
        if (Prefs(ctx).userModeBackup != null && Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.let { m ->
                cmds += "cmd display set-user-preferred-display-mode ${m.physicalWidth} ${m.physicalHeight} $asFloat"
            }
        }
        return Shell.exec(cmds.joinToString("; "))
    }

    private fun freeStorage(): Long = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(0L)

    /**
     * Čistič úložiště: mezipaměť všech aplikací (pm trim-caches – systém ji smaže sám, jako by docházelo místo),
     * miniatury galerie (vytvoří se znovu) a systémový log. Vrací uvolněné MB.
     */
    suspend fun cleanStorage(): Long {
        val before = freeStorage()
        Shell.exec(
            "pm trim-caches 999G 2>/dev/null; " +
                "rm -rf /sdcard/DCIM/.thumbnails/* /sdcard/Pictures/.thumbnails/* 2>/dev/null; " +
                "logcat -c 2>/dev/null"
        )
        return ((freeStorage() - before) / 1_048_576).coerceAtLeast(0)
    }

    /** Čistič RAM mimo hru: kill list + procesy v cache. Vrací počet ukončených appek z kill listu. */
    suspend fun cleanRam(ctx: Context): Int {
        val p = Prefs(ctx)
        val targets = killTargets(ctx, null).filter { isInstalled(ctx, it) }
        Shell.exec((targets.map { "am force-stop $it" } + "am kill-all").joinToString("; "))
        return targets.size
    }

    /** Snímek obrazovky přes shell (screencap). Vrací cestu, nebo prázdný text při chybě. */
    suspend fun screenshot(): String {
        val dir = "/sdcard/Pictures/ZukuBoost"
        val path = "$dir/ZukuBoost_${System.currentTimeMillis()}.png"
        val out = Shell.exec("cmd statusbar collapse; sleep 0.8; mkdir -p $dir && screencap -p $path && echo ZB_OK")
        return if (out.contains("ZB_OK")) path else ""
    }

    /** Nahrávání obrazovky (bez zvuku, max 3 min) – běží na pozadí, zastaví se stopRecording(). */
    suspend fun startRecording(): String {
        val dir = "/sdcard/Movies/ZukuBoost"
        val path = "$dir/ZukuBoost_${System.currentTimeMillis()}.mp4"
        Shell.exec(
            "cmd statusbar collapse; mkdir -p $dir; " +
                "nohup screenrecord --time-limit 180 $path >/dev/null 2>&1 &"
        )
        return path
    }

    suspend fun stopRecording() {
        Shell.exec("pkill -2 -x screenrecord 2>/dev/null || killall -2 screenrecord 2>/dev/null")
    }

    // ---------- Úplné skrytí oznámení (pravidla Nerušit) ----------

    private val HIDE_EFFECTS =
        NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
            NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR or
            NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE or
            NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT or
            NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST or
            NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT or
            NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS

    private fun makePolicy(categories: Int, calls: Int, messages: Int, effects: Int, conversations: Int) =
        if (Build.VERSION.SDK_INT >= 30) {
            NotificationManager.Policy(categories, calls, messages, effects, conversations)
        } else {
            NotificationManager.Policy(categories, calls, messages, effects)
        }

    private fun hideNotifications(ctx: Context, p: Prefs) {
        if (Build.VERSION.SDK_INT < 28) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val cur = nm.notificationPolicy
        val conv = if (Build.VERSION.SDK_INT >= 30) cur.priorityConversationSenders else 0
        if (p.policyBackup == null) {
            p.policyBackup = listOf(
                cur.priorityCategories,
                cur.priorityCallSenders,
                cur.priorityMessageSenders,
                cur.suppressedVisualEffects,
                conv,
            ).joinToString(",")
        }
        nm.notificationPolicy = makePolicy(
            cur.priorityCategories,
            cur.priorityCallSenders,
            cur.priorityMessageSenders,
            HIDE_EFFECTS,
            conv,
        )
    }

    private fun restoreNotifications(ctx: Context, p: Prefs) {
        val backup = p.policyBackup ?: return
        if (Build.VERSION.SDK_INT < 28) return
        val v = backup.split(",").mapNotNull { it.toIntOrNull() }
        if (v.size < 5) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        nm.notificationPolicy = makePolicy(v[0], v[1], v[2], v[3], v[4])
    }

    /** Kroky, které se ukážou v animaci spuštění hry (podle zapnutých voleb). */
    fun launchSteps(ctx: Context, pkg: String): List<String> {
        val p = Prefs(ctx)
        val kill = if (p.killMode == 1) 0 else (p.killList - pkg).count { isInstalled(ctx, it) }
        val cap = p.fpsCap(pkg)
        val scale = p.scale(pkg)
        return buildList {
            if (p.opt(Opt.KILL_BG)) {
                add(
                    when {
                        p.killMode == 1 -> "Closing all background apps except exceptions"
                        kill > 0 -> "Closing background apps ($kill)"
                        else -> "Cleaning background processes"
                    },
                )
            }
            add("Freeing RAM")
            if (p.opt(Opt.SYSTEM_HOGS)) add("Throttling system services and updates")
            if (p.opt(Opt.NET_BLOCK_BG)) add("Cutting internet for background apps")
            if (p.opt(Opt.HIDE_NOTIF) || p.opt(Opt.DND)) {
                add(if (p.opt(Opt.BLOCK_CALLS)) "Silencing notifications and calls" else "Silencing notifications")
            }
            if (p.opt(Opt.REFRESH)) add("Locking display at ${targetHz(ctx, cap).roundToInt()} Hz")
            if (cap > 0 || scale < 0.99f) {
                val parts = buildList {
                    if (cap > 0) add("FPS $cap")
                    if (scale < 0.99f) add("resolution ${(scale * 100).roundToInt()} %")
                }
                add("Game profile: ${parts.joinToString(" · ")}")
            }
            if (p.opt(Opt.GAME_MODE)) add("Game Mode: performance")
            if (p.opt(Opt.XIAOMI_PERF) && isXiaomi()) add("Xiaomi performance profile")
            if (p.opt(Opt.BATTERY_SAVER)) add("Turning off battery saver")
            if (p.opt(Opt.PROTECT_VOICE)) add("Protecting Discord and calls")
            if (p.opt(Opt.SMART_COOL)) add("Smart cooling ready")
        }
    }

    suspend fun restore(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.boostActive) return
        runCatching { restoreNotifications(ctx, p) }
        // Jas ztlumený chytrým chlazením (kdyby služba spadla dřív, než ho vrátila)
        p.coolBrightness?.let { b ->
            runCatching { setBrightness(b) }
            p.coolBrightness = null
        }

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
                LOCATION_MODE -> {
                    if (value != "0" && value != "null" && value.isNotEmpty()) {
                        cmds += "cmd location set-location-enabled true"
                    }
                }
                WIFI_SCAN_ALWAYS -> {
                    if (value == "1") cmds += "cmd wifi set-scan-always-available enabled"
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
        p.userModeBackup?.let { b ->
            cmds += if (b == "none" || b.split(" ").size < 3) {
                "cmd display clear-user-preferred-display-mode"
            } else {
                "cmd display set-user-preferred-display-mode $b"
            }
        }
        cmds += "dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1"
        if (p.fixedPerf) cmds += "cmd power set-fixed-performance-mode-enabled false"
        p.syncModeOrig?.let { mode ->
            cmds += "device_config set_sync_disabled_for_tests ${if (mode in SYNC_MODES) mode else "none"}"
        }
        p.bucketOrig.forEach { entry ->
            val pkgName = entry.substringBefore('=')
            val name = BUCKET_NAMES[entry.substringAfter('=')] ?: "active"
            if (PKG_RE.matches(pkgName)) cmds += "am set-standby-bucket $pkgName $name"
        }
        if (p.syncWasOn == 1) runCatching { ContentResolver.setMasterSyncAutomatically(true) }
        safe(p.restricted).forEach { cmds += "cmd appops set $it RUN_ANY_IN_BACKGROUND allow" }
        safe(p.voiceAdded).forEach { cmds += "dumpsys deviceidle whitelist -$it" }
        // Chain 3 necháváme zapnutý (může ho používat ShizuWall), jen odblokujeme naše appky
        safe(p.netBlocked).forEach { cmds += "cmd connectivity set-package-networking-enabled true $it" }
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
        if (!PKG_RE.matches(pkg)) return "Invalid package"
        return Shell.exec("cmd package compile -m speed -f $pkg").trim()
    }

    /** Profil hry: render rozlišení (víc FPS, méně tepla) a FPS limit (stabilní snímky). Android 13+. */
    suspend fun setGameConfig(ctx: Context, pkg: String, scale: Float, cap: Int): String {
        if (!PKG_RE.matches(pkg)) return "Invalid package"
        if (Build.VERSION.SDK_INT < 33) return "Requires Android 13+"
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

    /**
     * Odstraní všechno, co v ZukuBoostu může držet hru pod maximem Hz:
     * ruční Hz = maximum displeje, zámek Hz zapnutý, FPS limity her zrušené (Game Mode bez fps=),
     * stabilní takty vypnuté a na Xiaomi zapnutý Joyose (bez něj HyperOS hry nad 90 FPS nepustí).
     */
    suspend fun unlockMaxHz(ctx: Context): String {
        val p = Prefs(ctx)
        val changes = mutableListOf<String>()
        val max = (detectDisplayRates(ctx).maxOrNull() ?: refreshRates(ctx).maxOrNull() ?: 120f).roundToInt()
        val target = if (max < 120) 120 else max
        if (p.hzMode != target) {
            p.hzMode = target
            changes += "refresh rate set to $target Hz"
        }
        if (!p.opt(Opt.REFRESH)) {
            p.setOpt(Opt.REFRESH, true)
            changes += "Hz lock on"
        }
        if (p.opt(Opt.FIXED_PERF)) {
            p.setOpt(Opt.FIXED_PERF, false)
            changes += "stable clocks off"
        }
        val capped = p.games.filter { p.fpsCap(it) > 0 }
        capped.forEach { g ->
            p.setFpsCap(g, 0)
            runCatching { setGameConfig(ctx, g, p.scale(g), 0) }
        }
        if (capped.isNotEmpty()) changes += "FPS caps removed (${capped.size})"
        if (isXiaomi() && joyoseState(ctx) == false) {
            runCatching { setJoyose(true) }
            changes += "Joyose re-enabled"
        }
        return if (changes.isEmpty()) {
            "Nothing in ZukuBoost limits Hz – if the game stays at 90, check the game's own FPS setting"
        } else {
            "Unlocked: " + changes.joinToString(", ") + ". Restart the game."
        }
    }

    /** Skryté GPU nastavení v Xiaomi Game Turbu (Xiaomi ho vypíná vzdálenou konfigurací). */
    suspend fun gpuTunerEnabled(): Boolean = Shell.exec("settings get global GPUTUNER_SWITCH").trim() == "true"

    suspend fun setGpuTuner(ctx: Context, on: Boolean): String {
        Prefs(ctx).gpuTunerWanted = on
        val out = Shell.exec(
            if (on) "settings put global GPUTUNER_SWITCH true" else "settings delete global GPUTUNER_SWITCH"
        )
        return when {
            out.contains("Exception") -> out.trim()
            on -> "Unlocked – ZukuBoost re-applies it for every game. Open Game Turbo for the game and look for GPU settings."
            else -> "Game Turbo GPU settings hidden"
        }
    }

    /** setting = uložená hodnota, active = co Android opravdu používá (null = nejde zjistit). */
    data class FreezerInfo(val setting: String, val active: Boolean?)

    /**
     * Herní překážka = nastavení (často zapnuté omylem nebo podle „gaming tipů“), které hraní zhoršuje.
     * fix = příkaz, který ho vypne.
     */
    data class Obstacle(val id: String, val title: String, val why: String, val fix: String)

    private val OBSTACLES = listOf(
        Triple("settings get system show_touches", "1", Obstacle(
            "touches", "Show taps", "Draws a circle under every touch – needless load and distraction.",
            "settings put system show_touches 0",
        )),
        Triple("settings get system pointer_location", "1", Obstacle(
            "pointer", "Pointer location", "Overlay with touch coordinates – adds rendering load.",
            "settings put system pointer_location 0",
        )),
        Triple("settings get global always_finish_activities", "1", Obstacle(
            "finish", "Don't keep activities", "The game closes and reloads every time you switch to another app.",
            "settings put global always_finish_activities 0",
        )),
        Triple("getprop debug.egl.force_msaa", "true", Obstacle(
            "msaa", "Force 4x MSAA", "A popular “tip” that heavily loads the GPU in games and raises temperature.",
            "setprop debug.egl.force_msaa false",
        )),
        Triple("getprop debug.hwui.profile", "visual_bars", Obstacle(
            "profile", "Profile GPU rendering (bars)", "Draws a graph over the screen – extra load.",
            "setprop debug.hwui.profile false",
        )),
        Triple("getprop debug.hwui.overdraw", "show", Obstacle(
            "overdraw", "Debug GPU overdraw", "Tints the image and slows rendering.",
            "setprop debug.hwui.overdraw false",
        )),
        Triple("getprop debug.layout", "true", Obstacle(
            "layout", "Show layout bounds", "Draws boxes around every element – slows the UI.",
            "setprop debug.layout false",
        )),
    )

    /** Vrátí nalezené překážky (čte se jedním voláním). */
    suspend fun findObstacles(): List<Obstacle> {
        val script = OBSTACLES.mapIndexed { i, (cmd, _, _) -> "echo \"$i=\$($cmd)\"" }.joinToString("; ")
        val values = parseKv(Shell.exec(script))
        return OBSTACLES.filterIndexed { i, (_, bad, _) -> values[i.toString()]?.trim() == bad }.map { it.third }
    }

    /** Opraví všechny nalezené překážky; běžící appky si debug vlastnosti načtou znovu. */
    suspend fun fixObstacles(list: List<Obstacle>): String {
        if (list.isEmpty()) return "Nothing to fix"
        Shell.exec((list.map { it.fix } + "service call activity 1599295570 >/dev/null 2>&1").joinToString("; "))
        return "Fixed: ${list.joinToString { it.title }}. Restart the game."
    }

    /** Zmrazování appek v cache: appky na pozadí nebrání hře v CPU. */
    suspend fun freezerState(): FreezerInfo {
        val out = Shell.exec(
            "echo \"S=\$(settings get global cached_apps_freezer)\"; " +
                "dumpsys activity settings | grep -m 1 -i 'use_freezer'"
        )
        val setting = out.lines().firstOrNull { it.startsWith("S=") }?.removePrefix("S=")?.trim() ?: ""
        val line = out.lines().firstOrNull { it.contains("use_freezer", ignoreCase = true) }?.lowercase()
        val active = when {
            line == null -> null
            line.contains("true") -> true
            line.contains("false") -> false
            else -> null
        }
        return FreezerInfo(setting, active)
    }

    suspend fun setFreezer(ctx: Context, on: Boolean): String {
        Prefs(ctx).freezerWanted = on
        val out = Shell.exec(
            if (on) "settings put global cached_apps_freezer enabled" else "settings delete global cached_apps_freezer"
        )
        if (out.contains("Exception")) return out.trim()
        val info = freezerState()
        return when {
            on && info.active == true -> "Freezer is running ✅"
            on -> "Saved. Android enables it at boot – after a restart ZukuBoost sets it again automatically."
            else -> "Reset to system default"
        }
    }

    /** HyperOS hodnotu po startu přepisuje – pokud ji uživatel chce, vrátíme ji. */
    suspend fun ensureFreezer(ctx: Context) {
        if (!Prefs(ctx).freezerWanted) return
        if (freezerState().setting != "enabled") Shell.exec("settings put global cached_apps_freezer enabled")
    }

    /** Ověření, co systém opravdu používá (settings put nikdy nehlásí chybu, proto čteme dumpsys). */
    suspend fun diagnose(ctx: Context, pkg: String?): String {
        val p = Prefs(ctx)
        val game = pkg?.takeIf { PKG_RE.matches(it) }
        val script = buildList {
            add("echo '== Display =='")
            add("echo \"Modes: \$(dumpsys display | grep -oE 'fps=[0-9.]+' | sort -u | tr '\\n' ' ')\"")
            add("echo \"preferred mode: \$(cmd display get-user-preferred-display-mode 2>&1 | head -1)\"")
            add("echo \"peak=\$(settings get system peak_refresh_rate) min=\$(settings get system min_refresh_rate) user=\$(settings get secure user_refresh_rate) miui=\$(settings get system miui_refresh_rate)\"")
            add("dumpsys display | grep -m 4 -E 'mActiveMode|renderFrameRate|mActiveRefreshRate|refreshRate='")
            add("echo; echo '== Thermal =='")
            add("dumpsys thermalservice | grep -m 3 -E 'IsStatusOverride|Thermal Status'")
            add("echo; echo '== Performance =='")
            add("dumpsys power | grep -m 3 -E 'mLowPowerModeEnabled|mBatterySaverEnabled'")
            add("echo \"power_mode=\$(settings get system power_mode) speed_mode=\$(settings get system speed_mode)\"")
            add("echo \"freezer=\$(settings get global cached_apps_freezer) sync=\$(device_config get_sync_disabled_for_tests)\"")
            add("dumpsys activity settings | grep -m 1 -i 'use_freezer'")
            if (game != null) {
                add("echo; echo '== Game Mode: $game =='")
                add("cmd game list-modes $game 2>&1 | head -3")
                add("cmd game list-configs $game 2>&1 | head -5")
                add("echo \"game_overlay=\$(device_config get game_overlay $game)\"")
                if (isXiaomi()) {
                    add("settings get system MILLET_NO_RESTRICT_APP | grep -q $game && echo 'No restrictions (Millet): YES' || echo 'No restrictions (Millet): no'")
                }
            }
        }.joinToString("; ")
        val out = Shell.exec(script).trim()
        val boost = if (p.boostActive) "Boost: ACTIVE" else "Boost: inactive (no game running)"
        return "$boost\n\n$out".take(4000)
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
        if (!PKG_RE.matches(pkg)) return "Invalid package"
        val list = gameDriverApps().toMutableSet()
        if (on) list += pkg else list -= pkg
        val value = list.joinToString(",")
        val out = Shell.exec(
            if (value.isEmpty()) "settings delete global $DRIVER_KEY" else "settings put global $DRIVER_KEY $value"
        )
        return when {
            out.contains("Exception") -> out.trim()
            on -> "Game GPU driver enabled – restart the game"
            else -> "Default GPU driver – restart the game"
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
            return if (out.contains("Exception")) out.trim() else "Joyose is enabled again"
        }
        val first = Shell.exec("am force-stop $JOYOSE; pm disable-user --user 0 $JOYOSE")
        if (!first.contains("Exception")) return "Joyose disabled"
        val second = Shell.exec("am force-stop $JOYOSE; pm uninstall -k --user 0 $JOYOSE")
        return if (second.contains("Success")) {
            "Joyose removed for your user. Bring it back with the Re-enable Joyose button."
        } else {
            "Failed: ${second.trim()}"
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
        return if (out.contains("Error") || out.contains("Exception")) "This firmware doesn't have the hidden menu" else "Opened"
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
            "cmd notification allow_dnd $me", // přístup k pravidlům Nerušit (úplné skrytí oznámení)
        )
        if (Build.VERSION.SDK_INT >= 33) cmds += "pm grant $me android.permission.POST_NOTIFICATIONS"
        if (isXiaomi()) {
            cmds += "cmd appops set $me 10008 allow 2>/dev/null" // MIUI/HyperOS Autostart
            // Trvale na seznam „Bez omezení“ – jinak HyperOS službu uspí a po hře by nešlo nic vrátit
            val cur = Shell.exec("settings get system MILLET_NO_RESTRICT_APP").trim()
            val empty = cur.isEmpty() || cur == "null"
            if (empty || (VALUE_RE.matches(cur) && me !in cur.split(","))) {
                cmds += put(MILLET, if (empty) me else "$cur,$me")
            }
        }
        val out = Shell.exec(cmds.joinToString("; "))
        val errors = out.lines().map { it.trim() }.filter {
            it.contains("Exception") || it.startsWith("Error") || it.contains("not allowed", ignoreCase = true)
        }
        return if (errors.isEmpty()) {
            "Permissions granted"
        } else {
            "The system refused part of it: ${errors.first().take(140)} – grant it manually with the buttons below"
        }
    }
}
