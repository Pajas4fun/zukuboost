package cz.zukuboost

import android.content.Context
import android.content.SharedPreferences

enum class Opt(val key: String, val title: String, val desc: String, val default: Boolean) {
    KILL_BG("opt_kill", "Vyčistit pozadí", "Při startu hry ukončí aplikace z kill listu a uvolní procesy v cache.", true),
    RESTRICT_BG("opt_restrict", "Držet kill list mimo pozadí", "Aplikace z kill listu se během hraní nemůžou samy znovu spustit na pozadí.", true),
    DND("opt_dnd", "Režim Nerušit", "Během hraní zapne Nerušit (projdou jen prioritní hovory).", true),
    HEADS_UP("opt_headsup", "Blokovat vyskakovací oznámení", "Žádné bannery přes hru.", true),
    REFRESH("opt_refresh", "Maximální Hz displeje", "Zamkne displej na nejvyšší Hz. Na Xiaomi přepíše i jeho vlastní klíče pro Hz.", true),
    BATTERY_SAVER("opt_saver", "Vypnout spořič baterie", "Spořič škrtí výkon, během hraní se vypne.", true),
    GAME_MODE("opt_gamemode", "Android Game Mode: výkon", "Přepne hru do performance režimu, pokud ho hra a systém podporují.", true),
    THERMAL(
        "opt_thermal",
        "Nehlásit hrám přehřátí",
        "Některé hry samy snižují grafiku, když systém hlásí teplo. Tohle hlášení zamkne na OK. " +
            "Hardwarové škrcení CPU to nevypne. Při 44 °C baterie se pojistka sama vrátí.",
        false,
    ),
    ANIMATIONS("opt_anim", "Vypnout systémové animace", "Rychlejší přechody v systému během hraní.", false),
    OVERLAY("opt_overlay", "Overlay s FPS a teplotami", "Plovoucí ukazatel (FPS, ø/min FPS, CPU/GPU °C, příkon, čas). Dá se přetáhnout.", true),
}

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

    var lastSession: String
        get() = sp.getString("last_session", "") ?: ""
        set(v) {
            sp.edit().putString("last_session", v).apply()
        }

    fun scale(pkg: String): Float = sp.getFloat("scale_$pkg", 1f)

    fun setScale(pkg: String, v: Float) {
        sp.edit().putFloat("scale_$pkg", v).apply()
    }

    fun opt(o: Opt): Boolean = sp.getBoolean(o.key, o.default)

    fun setOpt(o: Opt, v: Boolean) {
        sp.edit().putBoolean(o.key, v).apply()
    }

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
        e.putBoolean("boost_active", false)
        e.commit()
    }

    companion object {
        private const val ORIG = "orig_"
    }
}
