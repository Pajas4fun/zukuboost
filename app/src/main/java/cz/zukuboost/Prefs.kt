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
    DISPLAY(
        "opt_display",
        "Displej: vypnout auto jas a režim čtení",
        "Doporučení přímo od Xiaomi: jas se během hry nemění a odpadá dodatečné zpracování obrazu. Po hře se vrátí.",
        true,
    ),
    LOCATION_OFF(
        "opt_location",
        "Vypnout polohu během hraní",
        "Appky na pozadí nebudou zjišťovat polohu (GPS, skenování Wi-Fi a Bluetooth kvůli poloze). Nezapínej, pokud hra polohu potřebuje.",
        false,
    ),
    NET_BLOCK_BG(
        "opt_netblock",
        "Odříznout internet appkám na pozadí",
        "Appky z kill listu (a s utlumením i Obchod Play a GetApps) během hry nemají přístup k internetu – nic nestahují a nebere to hře linku. " +
            "Hlasové appky a hra se neblokují. Po hře se vše vrátí, restart telefonu pravidla smaže taky.",
        true,
    ),
    NETWORK_QUIET(
        "opt_net",
        "Síť: klidný režim",
        "Během hry vypne „mobilní data vždy aktivní“, Wi-Fi asistenta Xiaomi a skenování zařízení v okolí. Méně zahřívání a méně výkyvů pingu. " +
            "Nevýhoda: když Wi-Fi vypadne, telefon nepřepne na data sám.",
        false,
    ),
    SCAN_OFF(
        "opt_scan",
        "Vypnout skenování Wi-Fi a Bluetooth na pozadí",
        "Skenování na chvíli přerušuje Wi-Fi spojení a způsobuje skoky pingu. Během hry se vypne, po hře vrátí.",
        true,
    ),
    MILLET(
        "opt_millet",
        "HyperOS: nezmrazovat hru a hlasové appky",
        "Přidá hru a Discord/WhatsApp/Telegram do seznamu „Bez omezení“ systému Xiaomi (MILLET_NO_RESTRICT_APP). " +
            "Když na chvíli odskočíš do jiné appky, hra ani hovor nezamrzne. Jen Xiaomi/Redmi/POCO.",
        true,
    ),
    PROTECT_VOICE(
        "opt_voice",
        "Chránit hlasové appky",
        "Discord, WhatsApp, Telegram, Skype a Teams nebudou během hraní uspané ani omezené – hovor ani stream z telefonu nevypadne. Appky z kill listu se nechrání.",
        true,
    ),
    HIDE_NOTIF(
        "opt_hidenotif",
        "Úplně skrýt oznámení",
        "Během hry se oznámení vůbec neukážou – žádné bannery, ikony v liště, tečky na ikonách ani blikání. " +
            "Počkají a uvidíš je po hře. Zapne i Nerušit.",
        true,
    ),
    DND("opt_dnd", "Režim Nerušit", "Během hraní zapne Nerušit (projdou jen prioritní hovory).", true),
    BLOCK_CALLS(
        "opt_blockcalls",
        "Blokovat i hovory",
        "Nerušit v režimu „jen budíky“ – během hry neprojde žádný hovor ani oznámení (jako Priority mode u Samsungu nebo Pro Gaming u OnePlus). Funguje se zapnutým Nerušit.",
        false,
    ),
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
        "Stabilní takty (fixed performance)",
        "Podle Googlu NEDÁVÁ maximální výkon – zamkne CPU/GPU na nižší, udržitelný bod. Méně výkyvů, ale nižší špička. " +
            "Hodí se jen pro dlouhé hraní na stabilní FPS. Funguje jen na části telefonů.",
        false,
    ),
    THERMAL(
        "opt_thermal",
        "Nehlásit hrám přehřátí (experimentální)",
        "Některé hry samy snižují grafiku, když systém hlásí teplo. Tohle hlášení zamkne na OK. " +
            "Skutečné škrcení CPU (řídí ho výrobce) to NEVYPNE – pomáhá jen hrám, které se samy omezují. Při 44 °C baterie se pojistka sama vrátí.",
        false,
    ),
    SMART_COOL(
        "opt_smartcool",
        "Chytré chlazení",
        "Hlídá teplotu baterie. Když je telefon delší dobu přes nastavenou hranici, sníží Hz displeje o stupeň, " +
            "o 3 °C výš na 60 Hz a jas −15 %. O 3 °C pod hranicí se vše vrátí. Hranici nastavíš níž. Upozorní i na hraní na nabíječce.",
        true,
    ),
    AUTO_HZ(
        "opt_autohz",
        "Hz podle skutečných FPS hry",
        "Po minutě hraní změří, kolik FPS hra opravdu dává, a sníží Hz displeje na nejbližší vyšší hodnotu (např. hra na 60 FPS → 60 Hz). " +
            "Méně tepla a rovnoměrnější snímky – jako Touch Choreographer u RedMagic nebo doporučení ASUS ROG. Platí jen bez FPS limitu.",
        false,
    ),
    EDGE_BLOCK(
        "opt_edge",
        "Blokovat dotyky na okrajích",
        "Úzké pruhy u levého a pravého okraje nereagují na dotyk – dlaň pod palcem nic nezmáčkne (jako Blocked touch edges u ASUS ROG). Šířka jde nastavit níž.",
        false,
    ),
    ANIMATIONS("opt_anim", "Vypnout systémové animace", "Rychlejší přechody v systému během hraní.", false),
    MEASURE("opt_measure", "Měřit FPS a teploty", "Ukládá do Historie ø FPS, minimum, počet propadů a max. teploty každé hry.", true),
    GAME_PANEL(
        "opt_panel",
        "Herní panel ve hře",
        "U pravého okraje se ve hře objeví úzký úchyt. Klepnutím vysuneš panel: snímek, nahrávání, overlay, blokování okrajů, " +
            "vyčištění RAM a zámek dotyku. Úchytem jde posouvat nahoru a dolů.",
        true,
    ),
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

    /** Původní standby buckety appek z kill listu ve formátu "balíček=číslo". */
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
