package cz.zukuboost

import android.app.NotificationManager
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
                val voice = VOICE_APPS.filter { it !in p.killList && isInstalled(ctx, it) }
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
            SYSTEM_HOGS.filter { it != pkg }.forEach { cmds += "am force-stop $it 2>/dev/null" }
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
            val targets = (VOICE_APPS.filter { it != pkg && it !in p.killList && isInstalled(ctx, it) } + pkg).distinct()
            if (targets.isNotEmpty()) {
                val whitelist = Shell.exec("dumpsys deviceidle whitelist")
                val added = targets.filter { !whitelist.contains(it) }
                p.voiceAdded = p.voiceAdded + added
                val voiceCmds = added.map { "dumpsys deviceidle whitelist +$it" } +
                    targets.map { "am set-standby-bucket $it active" }
                Shell.exec(voiceCmds.joinToString("; "))
            }
        }

        // Firewall přes connectivity chain 3 (stejný mechanismus jako ShizuWall), Android 11+
        if (p.opt(Opt.NET_BLOCK_BG) && Build.VERSION.SDK_INT >= 30) {
            val stores = if (p.opt(Opt.SYSTEM_HOGS)) {
                listOf("com.android.vending", "com.xiaomi.mipicks", "com.xiaomi.discover")
            } else {
                emptyList()
            }
            val targets = safe(
                (p.killList + stores) - pkg - ctx.packageName - SHIZUKU_PKG - VOICE_APPS.toSet()
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
        val targets = safe(p.killList - pkg - ctx.packageName - SHIZUKU_PKG)
        val cmds = targets.map { "am force-stop $it" } + "am kill-all"
        Shell.exec(cmds.joinToString("; "))
        return targets.size
    }

    /** Změna Hz během hry (automatické Hz podle FPS). Originály už jsou uložené z apply(). */
    suspend fun setSessionHz(hz: Float): String {
        val keys = detectHzKeys().filter { !it.endsWith("is_smart_fps") }
        val asFloat = String.format(Locale.US, "%.1f", hz)
        val asInt = hz.roundToInt().toString()
        val cmds = keys.map { id ->
            val k = key(id)
            put(id, if (k.startsWith("peak") || k.startsWith("min")) asFloat else asInt)
        }
        return Shell.exec(cmds.joinToString("; "))
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
        val kill = (p.killList - pkg).count { isInstalled(ctx, it) }
        val cap = p.fpsCap(pkg)
        val scale = p.scale(pkg)
        return buildList {
            if (p.opt(Opt.KILL_BG)) add(if (kill > 0) "Ukončuji appky na pozadí ($kill)" else "Čistím procesy na pozadí")
            add("Uvolňuji paměť RAM")
            if (p.opt(Opt.SYSTEM_HOGS)) add("Utlumuji systémové služby a aktualizace")
            if (p.opt(Opt.NET_BLOCK_BG)) add("Odpojuji internet appkám na pozadí")
            if (p.opt(Opt.HIDE_NOTIF) || p.opt(Opt.DND)) {
                add(if (p.opt(Opt.BLOCK_CALLS)) "Vypínám oznámení i hovory" else "Vypínám oznámení")
            }
            if (p.opt(Opt.REFRESH)) add("Zamykám ${targetHz(ctx, cap).roundToInt()} Hz displeje")
            if (cap > 0 || scale < 0.99f) {
                val parts = buildList {
                    if (cap > 0) add("FPS $cap")
                    if (scale < 0.99f) add("rozlišení ${(scale * 100).roundToInt()} %")
                }
                add("Profil hry: ${parts.joinToString(" · ")}")
            }
            if (p.opt(Opt.GAME_MODE)) add("Game Mode: výkon")
            if (p.opt(Opt.XIAOMI_PERF) && isXiaomi()) add("Xiaomi výkonový profil")
            if (p.opt(Opt.BATTERY_SAVER)) add("Vypínám spořič baterie")
            if (p.opt(Opt.PROTECT_VOICE)) add("Chráním Discord a hovory")
            if (p.opt(Opt.SMART_COOL)) add("Chytré chlazení připraveno")
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

    /** Skryté GPU nastavení v Xiaomi Game Turbu (Xiaomi ho vypíná vzdálenou konfigurací). */
    suspend fun gpuTunerEnabled(): Boolean = Shell.exec("settings get global GPUTUNER_SWITCH").trim() == "true"

    suspend fun setGpuTuner(ctx: Context, on: Boolean): String {
        Prefs(ctx).gpuTunerWanted = on
        val out = Shell.exec(
            if (on) "settings put global GPUTUNER_SWITCH true" else "settings delete global GPUTUNER_SWITCH"
        )
        return when {
            out.contains("Exception") -> out.trim()
            on -> "Odemčeno – ZukuBoost ho obnoví při každé hře. Otevři Game Turbo u hry a hledej GPU nastavení."
            else -> "GPU nastavení v Game Turbu skryto"
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
            "touches", "Zobrazování dotyků", "Kreslí kolečko pod každým dotykem – zbytečná zátěž a rušení.",
            "settings put system show_touches 0",
        )),
        Triple("settings get system pointer_location", "1", Obstacle(
            "pointer", "Umístění ukazatele", "Překryv se souřadnicemi dotyku – zátěž vykreslování.",
            "settings put system pointer_location 0",
        )),
        Triple("settings get global always_finish_activities", "1", Obstacle(
            "finish", "Nezachovávat aktivity", "Hra se při každém odskočení do jiné appky zavře a načítá znovu.",
            "settings put global always_finish_activities 0",
        )),
        Triple("getprop debug.egl.force_msaa", "true", Obstacle(
            "msaa", "Vynutit 4× MSAA", "Populární „tip“, který ve hrách výrazně zatěžuje GPU a zvyšuje teplotu.",
            "setprop debug.egl.force_msaa false",
        )),
        Triple("getprop debug.hwui.profile", "visual_bars", Obstacle(
            "profile", "Profil vykreslování GPU (pruhy)", "Kreslí graf přes obrazovku – zátěž navíc.",
            "setprop debug.hwui.profile false",
        )),
        Triple("getprop debug.hwui.overdraw", "show", Obstacle(
            "overdraw", "Ladit překreslování GPU", "Obarvuje obraz a zpomaluje vykreslování.",
            "setprop debug.hwui.overdraw false",
        )),
        Triple("getprop debug.layout", "true", Obstacle(
            "layout", "Zobrazit hranice rozvržení", "Kreslí rámečky kolem všech prvků – zpomaluje UI.",
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
        if (list.isEmpty()) return "Nic k opravě"
        Shell.exec((list.map { it.fix } + "service call activity 1599295570 >/dev/null 2>&1").joinToString("; "))
        return "Opraveno: ${list.joinToString { it.title }}. Hru spusť znovu."
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
            on && info.active == true -> "Zmrazování běží ✅"
            on -> "Uloženo. Android ho zapíná při startu – po restartu ho ZukuBoost znovu nastaví sám."
            else -> "Vráceno na výchozí systému"
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
            add("echo '== Displej =='")
            add("echo \"Režimy: \$(dumpsys display | grep -oE 'fps=[0-9.]+' | sort -u | tr '\\n' ' ')\"")
            add("echo \"peak=\$(settings get system peak_refresh_rate) min=\$(settings get system min_refresh_rate) user=\$(settings get secure user_refresh_rate) miui=\$(settings get system miui_refresh_rate)\"")
            add("dumpsys display | grep -m 4 -E 'mActiveMode|renderFrameRate|mActiveRefreshRate|refreshRate='")
            add("echo; echo '== Thermal =='")
            add("dumpsys thermalservice | grep -m 3 -E 'IsStatusOverride|Thermal Status'")
            add("echo; echo '== Výkon =='")
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
                    add("settings get system MILLET_NO_RESTRICT_APP | grep -q $game && echo 'Bez omezení (Millet): ANO' || echo 'Bez omezení (Millet): ne'")
                }
            }
        }.joinToString("; ")
        val out = Shell.exec(script).trim()
        val boost = if (p.boostActive) "Boost: AKTIVNÍ" else "Boost: neaktivní (hra neběží)"
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
            "Oprávnění nastavena"
        } else {
            "Systém část odmítl: ${errors.first().take(140)} – povol to ručně tlačítky níž"
        }
    }
}
