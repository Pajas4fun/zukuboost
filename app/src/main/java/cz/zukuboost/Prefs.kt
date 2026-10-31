package cz.zukuboost

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class Opt(val key: String, val title: String, val desc: String, val default: Boolean) {
    KILL_BG("opt_kill", "Vyčistit pozadí", "Při startu hry ukončí aplikace z kill listu a uvolní procesy v cache.", true),
    RESTRICT_BG("opt_restrict", "Držet kill list mimo pozadí", "Aplikace z kill listu se během hraní nemůžou samy znovu spustit na pozadí.", true),
    SYSTEM_HOGS(
        "opt_hogs",
        "Utlumit systémové žrouty",
        "Zastaví aktualizace z Obchodu Play / GetApps, analytiku a reklamy Xiaomi, skenování galerie a optimalizaci appek na pozadí. Časté příčiny náhlých záseků.",
        true,
    ),
    SYNC_OFF("opt_sync", "Vypnout synchronizaci účtů", "Google, e-mail a cloud během hraní nesynchronizují (žádné náhlé zátěže úložiště). Po hře se vrátí.", true),
    DND("opt_dnd", "Režim Nerušit", "Během hraní zapne Nerušit (projdou jen prioritní hovory).", true),
    HEADS_UP("opt_headsup", "Blokovat vyskakovací oznámení", "Žádné bannery přes hru – každý banner navíc zatěžuje vykreslování.", true),
    REFRESH("opt_refresh", "Hz displeje podle hry", "Bez FPS limitu maximální Hz. S limitem Hz, které jsou jeho násobkem (např. limit 45 → 90 Hz), aby snímky šly rovnoměrně.", true),
    BATTERY_SAVER("opt_saver", "Vypnout spořič baterie", "Spořič škrtí výkon, během hraní se vypne.", true),
    GAME_MODE("opt_gamemode", "Android Game Mode: výkon", "Přepne hru do performance režimu, pokud ho hra a systém podporují.", true),
    XIAOMI_PERF(
        "opt_xiaomiperf",
        "Xiaomi výkonový profil (experimentální)",
        "Během hraní přepne systémový profil Xiaomi na výkon (power_mode) a zapne režim vysokých FPS (speed_mode). Víc výkonu, víc tepla. Jen Xiaomi/Redmi/POCO.",
        false,
    ),
    FIXED_PERF(
        "opt_fixedperf",
        "Stabilní výkon (fixed performance)",
        "Nastaví CPU a GPU pevnou horní i dolní hranici taktů. Nezrychlí, ale srovná výkyvy – méně propadů. Funguje jen na části telefonů.",
        false,
    ),
    THERMAL(
        "opt_thermal",
        "Nehlásit hrám přehřátí",
        "Některé hry samy snižují grafiku, když systém hlásí teplo. Tohle hlášení zamkne na OK. " +
            "Hardwarové škrcení CPU to nevypne. Při 44 °C baterie se pojistka sama vrátí.",
        false,
    ),
    ANIMATIONS("opt_anim", "Vypnout systémové animace", "Rychlejší přechody v systému během hraní.", false),
    MEASURE("opt_measure", "Měřit FPS a teploty", "Ukládá do Historie ø FPS, minimum, počet propadů a max. teploty každé hry.", true),
    OVERLAY("opt_overlay", "Overlay ve hře", "Plovoucí ukazatel FPS, propadů, teplot a příkonu. Dá se přetáhnout.", true),
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

    /** Aplikace, kterým jsme během hraní zakázali běh na pozadí (vrací se po hře). */
    var restricted: Set<String>
        get() = getSet("restricted")
        set(v) {
            sp.edit().putStringSet("restricted", HashSet(v)).commit()
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
