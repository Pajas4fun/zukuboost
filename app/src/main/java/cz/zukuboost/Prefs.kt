package cz.zukuboost

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class Opt(val key: String, val title: String, val desc: String, val default: Boolean) {
    KILL_BG("opt_kill", "Clean background", "When a game starts, closes apps from the kill list and frees cached processes.", true),
    RESTRICT_BG("opt_restrict", "Keep closed apps out of the background", "Closed apps can't restart themselves in the background while you play.", true),
    SYSTEM_HOGS(
        "opt_hogs",
        "Throttle system resource hogs",
        "Stops Play Store / GetApps updates, Xiaomi analytics and ads, gallery scanning and background app optimization. Common causes of sudden stutters.",
        true,
    ),
    SYNC_OFF("opt_sync", "Turn off account sync", "Google, e-mail and cloud don't sync while you play (no sudden storage load). Restored after the game.", true),
    DISPLAY(
        "opt_display",
        "Display: turn off auto brightness and reading mode",
        "Recommended by Xiaomi itself: brightness doesn't change during the game and extra image processing is skipped. Restored after the game.",
        true,
    ),
    LOCATION_OFF(
        "opt_location",
        "Turn off location while playing",
        "Background apps won't look up your location (GPS, Wi-Fi and Bluetooth scanning for location). Don't enable if the game needs location.",
        false,
    ),
    NET_BLOCK_BG(
        "opt_netblock",
        "Cut internet for background apps",
        "Closed apps (and with throttling also Play Store and GetApps) have no internet during the game – nothing downloads and the game keeps the bandwidth. " +
            "Voice apps and the game are never blocked. Everything is restored after the game; a phone restart clears the rules too.",
        true,
    ),
    NETWORK_QUIET(
        "opt_net",
        "Network: quiet mode",
        "During the game turns off “mobile data always active”, Xiaomi Wi-Fi assistant and nearby device scanning. Less heat and fewer ping spikes. " +
            "Downside: if Wi-Fi drops, the phone won't switch to mobile data on its own.",
        false,
    ),
    SCAN_OFF(
        "opt_scan",
        "Turn off background Wi-Fi and Bluetooth scanning",
        "Scanning briefly interrupts the Wi-Fi connection and causes ping spikes. Off during the game, restored after.",
        true,
    ),
    MILLET(
        "opt_millet",
        "HyperOS: don't freeze the game and voice apps",
        "Adds the game and Discord/WhatsApp/Telegram to Xiaomi's “No restrictions” list (MILLET_NO_RESTRICT_APP). " +
            "When you briefly switch to another app, neither the game nor the call freezes. Xiaomi/Redmi/POCO only.",
        true,
    ),
    PROTECT_VOICE(
        "opt_voice",
        "Protect voice apps",
        "Discord, WhatsApp, Telegram, Skype and Teams won't be put to sleep or restricted while you play – calls and streams from the phone won't drop. Apps in the kill list aren't protected.",
        true,
    ),
    HIDE_NOTIF(
        "opt_hidenotif",
        "Hide notifications completely",
        "Notifications don't show at all during the game – no banners, status bar icons, icon dots or blinking. " +
            "They wait and you'll see them after the game. Also turns on Do Not Disturb.",
        true,
    ),
    DND("opt_dnd", "Do Not Disturb", "Turns on Do Not Disturb while you play (only priority calls get through).", true),
    BLOCK_CALLS(
        "opt_blockcalls",
        "Block calls too",
        "Do Not Disturb in “alarms only” mode – no calls or notifications get through during the game (like Samsung's Priority mode or OnePlus Pro Gaming). Works with Do Not Disturb on.",
        false,
    ),
    HEADS_UP("opt_headsup", "Block heads-up notifications", "No banners over the game – every banner adds rendering load.", true),
    REFRESH("opt_refresh", "Display Hz for the game", "Without an FPS cap, maximum Hz. With a cap, Hz that is a multiple of it (e.g. cap 45 → 90 Hz) so frames are evenly paced.", true),
    FORCE_MODE(
        "opt_forcemode",
        "Force display mode",
        "If the game still runs at 90 Hz despite the Hz lock, try this: sets the preferred display mode (resolution + Hz) directly " +
            "through Android's display manager. Restored after the game. Experimental.",
        false,
    ),
    BATTERY_SAVER("opt_saver", "Turn off battery saver", "Battery saver throttles performance, so it's turned off while you play.", true),
    GAME_MODE("opt_gamemode", "Android Game Mode: performance", "Switches the game to performance mode if the game and system support it.", true),
    XIAOMI_PERF(
        "opt_xiaomiperf",
        "Xiaomi performance profile (experimental)",
        "While you play, switches Xiaomi's system profile to performance (power_mode) and enables high-FPS mode (speed_mode). More performance, more heat. Xiaomi/Redmi/POCO only.",
        false,
    ),
    FIXED_PERF(
        "opt_fixedperf",
        "Stable clocks (fixed performance)",
        "According to Google this does NOT give maximum performance – it locks CPU/GPU at a lower, sustainable point. Fewer swings, but a lower peak. " +
            "Only useful for long sessions at stable FPS. Works on some phones only.",
        false,
    ),
    THERMAL(
        "opt_thermal",
        "Hide overheating from games (experimental)",
        "Some games lower their graphics when the system reports heat. This locks the report at OK. " +
            "It does NOT turn off real CPU throttling (controlled by the manufacturer) – it only helps games that limit themselves. At 44 °C battery the safety net restores it.",
        false,
    ),
    SMART_COOL(
        "opt_smartcool",
        "Smart cooling",
        "Watches battery temperature. When the phone stays above the set limit for a while, it lowers display Hz by one step, " +
            "3 °C higher to 60 Hz and brightness −15 %. 3 °C below the limit everything is restored. Set the limit below. Also warns when you play while charging.",
        true,
    ),
    AUTO_HZ(
        "opt_autohz",
        "Hz matched to the game's real FPS",
        "After a minute of play, measures the game's real FPS and lowers display Hz to the nearest higher value (e.g. a 60 FPS game → 60 Hz). " +
            "Less heat and more even frames – like RedMagic's Touch Choreographer or ASUS ROG's advice. A 90 FPS game on a 120 Hz display otherwise judders (frames aren't evenly paced). Lobby and loading aren't counted, and when the game wants more, Hz goes back up. Only without an FPS cap.",
        true,
    ),
    EDGE_BLOCK(
        "opt_edge",
        "Block touches at the edges",
        "Narrow strips at the left and right edges ignore touch – your palm won't press anything (like ASUS ROG's Blocked touch edges). Width can be set below.",
        false,
    ),
    ANIMATIONS("opt_anim", "Turn off system animations", "Faster system transitions while you play.", false),
    MEASURE("opt_measure", "Measure FPS and temperatures", "Saves average FPS, minimum, drop count and max temperatures of every game to History.", true),
    GAME_PANEL(
        "opt_panel",
        "In-game panel",
        "A narrow handle appears at the right edge in game. Tap it to slide out the panel: screenshot, recording, overlay, edge blocking, " +
            "RAM cleanup and touch lock. Drag the handle up and down to move it.",
        true,
    ),
    OVERLAY("opt_overlay", "In-game overlay", "Floating display of FPS, drops, temperatures and power draw. Can be dragged.", true),
}

data class Session(
    val pkg: String,
    val label: String,
    val start: Long,
    val minutes: Long,
    val avg: Int?,
    val min: Int?,
    val drops: Int,
    val maxCpu: Float?,
    val maxBat: Float?,
)

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("zukuboost", Context.MODE_PRIVATE)

    private fun getSet(key: String): Set<String> = sp.getStringSet(key, null)?.toSet() ?: emptySet()

    var games: Set<String>
        get() = getSet("games")
        set(v) {
            sp.edit().putStringSet("games", HashSet(v)).apply()
        }

    var killList: Set<String>
        get() = getSet("kill_list")
        set(v) {
            sp.edit().putStringSet("kill_list", HashSet(v)).apply()
        }

    /** Režim ukončování: 0 = jen appky z kill listu, 1 = vše kromě výjimek. */
    var killMode: Int
        get() = sp.getInt("kill_mode", 1)
        set(v) {
            sp.edit().putInt("kill_mode", v).apply()
        }

    /** Výjimky – appky, které se při spuštění hry NEukončí (režim „vše kromě výjimek“). */
    var keepList: Set<String>
        get() = getSet("keep_list")
        set(v) {
            sp.edit().putStringSet("keep_list", HashSet(v)).apply()
        }

    /** Doporučené nastavení podle telefonu už jednou použité? */
    var deviceApplied: Boolean
        get() = sp.getBoolean("device_applied", false)
        set(v) {
            sp.edit().putBoolean("device_applied", v).apply()
        }

    /** Doporučené výjimky už jednou předvyplněné? */
    var keepInitDone: Boolean
        get() = sp.getBoolean("keep_init", false)
        set(v) {
            sp.edit().putBoolean("keep_init", v).apply()
        }

    /** Aplikace, kterým jsme během hraní zakázali běh na pozadí (vrací se po hře). */
    var restricted: Set<String>
        get() = getSet("restricted")
        set(v) {
            sp.edit().putStringSet("restricted", HashSet(v)).commit()
        }

    /** Hlasové appky, které jsme přidali na seznam výjimek z úspory (po hře se odeberou). */
    var voiceAdded: Set<String>
        get() = getSet("voice_added")
        set(v) {
            sp.edit().putStringSet("voice_added", HashSet(v)).commit()
        }

    /** Appky, kterým jsme během hry zablokovali síť (po hře se odblokují). */
    var netBlocked: Set<String>
        get() = getSet("net_blocked")
        set(v) {
            sp.edit().putStringSet("net_blocked", HashSet(v)).commit()
        }

    /** Původní režim synchronizace device_config (null = nesahali jsme). */
    var syncModeOrig: String?
        get() = sp.getString("sync_mode_orig", null)
        set(v) {
            sp.edit().putString("sync_mode_orig", v).commit()
        }

    /** Původní standby buckety appek z kill listu ve formátu "package=number". */
    var bucketOrig: Set<String>
        get() = getSet("bucket_orig")
        set(v) {
            sp.edit().putStringSet("bucket_orig", HashSet(v)).commit()
        }

    /** Uživatel chce mít zapnuté zmrazování appek v cache – HyperOS ho po restartu vypíná, tak ho obnovujeme. */
    var freezerWanted: Boolean
        get() = sp.getBoolean("freezer_wanted", false)
        set(v) {
            sp.edit().putBoolean("freezer_wanted", v).apply()
        }

    /** Uživatel chce mít odemčené GPU nastavení v Game Turbu – cloud ho resetuje, takže ho obnovujeme. */
    var gpuTunerWanted: Boolean
        get() = sp.getBoolean("gputuner_wanted", false)
        set(v) {
            sp.edit().putBoolean("gputuner_wanted", v).apply()
        }

    /** Záloha pravidel Nerušit (priorityCategories,call,message,effects,conversation). */
    var policyBackup: String?
        get() = sp.getString("policy_backup", null)
        set(v) {
            sp.edit().putString("policy_backup", v).commit()
        }

    /** Spuštění z domovské obrazovky – do té doby služba nesmí boost vracet (hra se ještě načítá). */
    var launchGraceUntil: Long
        get() = sp.getLong("launch_grace", 0L)
        set(v) {
            sp.edit().putLong("launch_grace", v).commit()
        }

    var boostActive: Boolean
        get() = sp.getBoolean("boost_active", false)
        set(v) {
            sp.edit().putBoolean("boost_active", v).commit()
        }

    var thermalOverride: Boolean
        get() = sp.getBoolean("thermal_override", false)
        set(v) {
            sp.edit().putBoolean("thermal_override", v).commit()
        }

    var fixedPerf: Boolean
        get() = sp.getBoolean("fixed_perf", false)
        set(v) {
            sp.edit().putBoolean("fixed_perf", v).commit()
        }

    /** Stav synchronizace před hrou: -1 = nesahali jsme na ni, 0 = vypnutá, 1 = zapnutá. */
    var syncWasOn: Int
        get() = sp.getInt("sync_was", -1)
        set(v) {
            sp.edit().putInt("sync_was", v).commit()
        }

    /** Výsledek testu zápisu do systémových nastavení: -1 neznámo, 0 blokováno, 1 OK. */
    var settingsWriteOk: Int
        get() = sp.getInt("settings_write_ok", -1)
        set(v) {
            sp.edit().putInt("settings_write_ok", v).apply()
        }

    var monitorEnabled: Boolean
        get() = sp.getBoolean("monitor_enabled", false)
        set(v) {
            sp.edit().putBoolean("monitor_enabled", v).apply()
        }

    /** Pozastavení z dlaždice v rychlém nastavení. */
    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) {
            sp.edit().putBoolean("paused", v).commit()
        }

    /** Naposledy vybraná hra na domovské obrazovce. */
    var lastGame: String?
        get() = sp.getString("last_game", null)
        set(v) {
            sp.edit().putString("last_game", v).apply()
        }

    var lastSession: String
        get() = sp.getString("last_session", "") ?: ""
        set(v) {
            sp.edit().putString("last_session", v).apply()
        }

    // ----- profil hry -----

    /** Obnovovací frekvence zjištěné z dumpsys display (HyperOS je někdy v API nehlásí). */
    var extraRates: List<Float>
        get() = (sp.getString("extra_rates", "") ?: "").split(",").mapNotNull { it.toFloatOrNull() }
        set(v) {
            sp.edit().putString("extra_rates", v.joinToString(",")).apply()
        }

    /** Jas před ztlumením chlazením (null = neztlumeno) – vrací ho restore() i po pádu služby. */
    var coolBrightness: String?
        get() = sp.getString("cool_brightness", null)
        set(v) {
            sp.edit().putString("cool_brightness", v).commit()
        }

    /** Ruční volba Hz ve hře: 0 = automaticky, jinak 60/90/120/144. */
    var hzMode: Int
        get() = sp.getInt("hz_mode", 0)
        set(v) {
            sp.edit().putInt("hz_mode", v).apply()
        }

    /** Preferovaný režim displeje před hrou ("none" = nebyl nastavený). */
    var userModeBackup: String?
        get() = sp.getString("user_mode_backup", null)
        set(v) {
            sp.edit().putString("user_mode_backup", v).commit()
        }

    /** Teplota baterie, od které začne chytré chlazení (°C). */
    var coolStartC: Int
        get() = sp.getInt("cool_start", 44)
        set(v) {
            sp.edit().putInt("cool_start", v).apply()
        }

    /** Šířka blokovaných okrajů v dp. */
    var edgeWidthDp: Int
        get() = sp.getInt("edge_width", 16)
        set(v) {
            sp.edit().putInt("edge_width", v).apply()
        }

    fun scale(pkg: String): Float = sp.getFloat("scale_$pkg", 1f)

    fun setScale(pkg: String, v: Float) {
        sp.edit().putFloat("scale_$pkg", v).apply()
    }

    /** Původní (výrobcem nastavená) konfigurace game_overlay pro hru – "null" = žádná. */
    fun gameOverlayBackup(pkg: String): String? = sp.getString("go_backup_$pkg", null)

    fun setGameOverlayBackup(pkg: String, v: String) {
        sp.edit().putString("go_backup_$pkg", v).apply()
    }

    /** 0 = bez limitu. */
    fun fpsCap(pkg: String): Int = sp.getInt("fps_$pkg", 0)

    fun setFpsCap(pkg: String, v: Int) {
        sp.edit().putInt("fps_$pkg", v).apply()
    }

    // ----- volby -----

    fun opt(o: Opt): Boolean = sp.getBoolean(o.key, o.default)

    fun setOpt(o: Opt, v: Boolean) {
        sp.edit().putBoolean(o.key, v).apply()
    }

    // ----- uložené původní hodnoty -----

    fun saveOriginals(values: Map<String, String>) {
        val e = sp.edit()
        values.forEach { (k, v) -> e.putString(ORIG + k, v) }
        e.commit()
    }

    fun originals(): Map<String, String> =
        sp.all.filterKeys { it.startsWith(ORIG) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(ORIG) to it } }
            .toMap()

    fun clearBoostState() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith(ORIG) }.forEach { e.remove(it) }
        e.remove("restricted")
        e.remove("thermal_override")
        e.remove("fixed_perf")
        e.remove("sync_was")
        e.remove("voice_added")
        e.remove("net_blocked")
        e.remove("sync_mode_orig")
        e.remove("bucket_orig")
        e.remove("policy_backup")
        e.remove("user_mode_backup")
        e.putBoolean("boost_active", false)
        e.commit()
    }

    // ----- historie -----

    fun history(): List<Session> = runCatching {
        val arr = JSONArray(sp.getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Session(
                pkg = o.getString("pkg"),
                label = o.getString("label"),
                start = o.getLong("start"),
                minutes = o.getLong("minutes"),
                avg = if (o.has("avg")) o.getInt("avg") else null,
                min = if (o.has("min")) o.getInt("min") else null,
                drops = o.optInt("drops", 0),
                maxCpu = if (o.has("cpu")) o.getDouble("cpu").toFloat() else null,
                maxBat = if (o.has("bat")) o.getDouble("bat").toFloat() else null,
            )
        }
    }.getOrDefault(emptyList())

    fun addHistory(s: Session) {
        val list = (listOf(s) + history()).take(50)
        val arr = JSONArray()
        list.forEach { h ->
            val o = JSONObject()
            o.put("pkg", h.pkg)
            o.put("label", h.label)
            o.put("start", h.start)
            o.put("minutes", h.minutes)
            h.avg?.let { o.put("avg", it) }
            h.min?.let { o.put("min", it) }
            o.put("drops", h.drops)
            h.maxCpu?.let { o.put("cpu", it.toDouble()) }
            h.maxBat?.let { o.put("bat", it.toDouble()) }
            arr.put(o)
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() {
        sp.edit().remove("history").apply()
    }

    companion object {
        private const val ORIG = "orig_"
    }
}
