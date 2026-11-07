package cz.zukuboost

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Profil telefonu: výrobce, nadstavba, čip, RAM, displej – a z toho doporučené nastavení boostu.
 * Čte se z Build a systémových vlastností (getprop přes Shizuku, když běží).
 */
data class DeviceProfile(
    val model: String,
    val brand: Brand,
    val skin: String,
    val soc: String,
    val socVendor: SocVendor,
    val ramGb: Int,
    val maxHz: Int,
    val android: Int,
)

enum class Brand(val title: String) {
    XIAOMI("Xiaomi / Redmi / POCO"),
    SAMSUNG("Samsung"),
    OPLUS("OnePlus / OPPO / Realme"),
    VIVO("vivo / iQOO"),
    GOOGLE("Google Pixel"),
    MOTOROLA("Motorola"),
    ASUS("ASUS"),
    NOTHING("Nothing"),
    OTHER("Other"),
}

enum class SocVendor(val title: String) {
    SNAPDRAGON("Qualcomm Snapdragon"),
    MEDIATEK("MediaTek Dimensity / Helio"),
    EXYNOS("Samsung Exynos"),
    TENSOR("Google Tensor"),
    OTHER("Other"),
}

/** Doporučení pro konkrétní telefon. */
data class DeviceRecommendation(
    val opts: Map<Opt, Boolean>,
    val coolStartC: Int,
    val killMode: Int,
    val tips: List<String>,
)

object DeviceDetector {

    private fun brandOf(manufacturer: String, brand: String): Brand {
        val m = "$manufacturer $brand".lowercase(Locale.ROOT)
        return when {
            listOf("xiaomi", "redmi", "poco").any { it in m } -> Brand.XIAOMI
            "samsung" in m -> Brand.SAMSUNG
            listOf("oneplus", "oppo", "realme").any { it in m } -> Brand.OPLUS
            listOf("vivo", "iqoo").any { it in m } -> Brand.VIVO
            "google" in m -> Brand.GOOGLE
            "motorola" in m || "lenovo" in m -> Brand.MOTOROLA
            "asus" in m -> Brand.ASUS
            "nothing" in m -> Brand.NOTHING
            else -> Brand.OTHER
        }
    }

    private fun socVendorOf(text: String): SocVendor {
        val t = text.lowercase(Locale.ROOT)
        return when {
            listOf("qcom", "qti", "snapdragon", "sm8", "sm7", "sm6", "sm4", "sdm", "msm", "kalama", "pineapple", "sun", "taro", "lahaina").any { it in t } -> SocVendor.SNAPDRAGON
            listOf("mediatek", "mt6", "mt8", "dimensity", "helio").any { it in t } -> SocVendor.MEDIATEK
            listOf("exynos", "s5e", "universal").any { it in t } -> SocVendor.EXYNOS
            listOf("tensor", "gs101", "gs201", "zuma").any { it in t } -> SocVendor.TENSOR
            else -> SocVendor.OTHER
        }
    }

    suspend fun detect(ctx: Context): DeviceProfile {
        // Systémové vlastnosti (přes Shizuku jsou přesnější: obchodní název, nadstavba, čip)
        val props = runCatching {
            Shell.exec(
                listOf(
                    "ro.product.marketname", "ro.product.vendor.marketname", "ro.mi.os.version.name",
                    "ro.miui.ui.version.name", "ro.build.version.oneui", "ro.build.version.oplusrom",
                    "ro.vivo.os.version", "ro.soc.model", "ro.board.platform", "ro.hardware",
                ).joinToString("; ") { "echo \"$it=\$(getprop $it)\"" }
            )
        }.getOrDefault("")
        val kv = props.lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim()
        }.toMap().filterValues { it.isNotBlank() }

        val brand = brandOf(Build.MANUFACTURER, Build.BRAND)
        val market = kv["ro.product.marketname"] ?: kv["ro.product.vendor.marketname"]
        val model = market ?: "${Build.MANUFACTURER.replaceFirstChar { it.titlecase() }} ${Build.MODEL}"

        val skin = when {
            kv["ro.mi.os.version.name"] != null -> "HyperOS ${kv["ro.mi.os.version.name"]!!.removePrefix("OS")}"
            kv["ro.miui.ui.version.name"] != null -> "MIUI ${kv["ro.miui.ui.version.name"]!!.removePrefix("V")}"
            kv["ro.build.version.oneui"] != null -> {
                val v = kv["ro.build.version.oneui"]!!.toIntOrNull()
                if (v != null) "One UI ${v / 10000}.${(v / 100) % 100}" else "One UI"
            }
            kv["ro.build.version.oplusrom"] != null -> "ColorOS / OxygenOS ${kv["ro.build.version.oplusrom"]}"
            kv["ro.vivo.os.version"] != null -> "OriginOS / Funtouch ${kv["ro.vivo.os.version"]}"
            else -> "Android"
        } + " (Android ${Build.VERSION.RELEASE})"

        val socApi = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        val socRaw = listOfNotNull(socApi.takeIf { it.isNotBlank() }, kv["ro.soc.model"], kv["ro.board.platform"], kv["ro.hardware"], Build.HARDWARE)
            .joinToString(" ")
        val vendor = socVendorOf(socRaw)
        val socShown = (kv["ro.soc.model"] ?: (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null) ?: kv["ro.board.platform"] ?: Build.HARDWARE)
            .uppercase(Locale.ROOT)

        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = (mi.totalMem / 1_000_000_000.0).roundToInt().coerceAtLeast(1)
        val maxHz = BoostEngine.refreshRates(ctx).maxOrNull()?.roundToInt() ?: 60

        return DeviceProfile(model, brand, skin, socShown, vendor, ramGb, maxHz, Build.VERSION.SDK_INT)
    }

    /** Pravidla: co zapnout pro tenhle telefon a proč. */
    fun recommend(d: DeviceProfile): DeviceRecommendation {
        val o = mutableMapOf<Opt, Boolean>()
        val tips = mutableListOf<String>()
        // Základ pro všechny telefony
        listOf(
            Opt.KILL_BG, Opt.RESTRICT_BG, Opt.SYSTEM_HOGS, Opt.NET_BLOCK_BG, Opt.SCAN_OFF, Opt.SYNC_OFF,
            Opt.HIDE_NOTIF, Opt.HEADS_UP, Opt.BATTERY_SAVER, Opt.GAME_MODE, Opt.PROTECT_VOICE,
            Opt.SMART_COOL, Opt.MEASURE, Opt.GAME_PANEL, Opt.DISPLAY,
        ).forEach { o[it] = true }
        o[Opt.THERMAL] = false
        o[Opt.FIXED_PERF] = false

        // Displej
        if (d.maxHz >= 90) {
            o[Opt.REFRESH] = true
            tips += "${d.maxHz} Hz display: the game gets the full ${d.maxHz} Hz – nothing lowers it."
        } else {
            o[Opt.REFRESH] = false
            tips += "60 Hz display: an Hz lock makes no sense, turned off."
        }

        // Výrobce
        when (d.brand) {
            Brand.XIAOMI -> {
                o[Opt.MILLET] = true
                o[Opt.XIAOMI_PERF] = true
                tips += "Xiaomi: HyperOS performance profile, game and Discord “no restrictions” (Millet), Xiaomi services throttled."
                tips += "Keep Joyose enabled – 90/120 FPS games go through it. Turn off Game Turbo for the selected games."
            }
            Brand.SAMSUNG -> {
                o[Opt.MILLET] = false
                o[Opt.XIAOMI_PERF] = false
                tips += "Samsung: Bixby, Samsung Free and Galaxy Store are throttled. In Game Booster set Game optimization to “Performance”."
            }
            Brand.OPLUS -> {
                o[Opt.MILLET] = false
                o[Opt.XIAOMI_PERF] = false
                tips += "OnePlus/OPPO/Realme: HeyTap store and cloud are throttled. Keep the game assistant, but turn off its “Power saving mode”."
            }
            Brand.VIVO -> {
                o[Opt.MILLET] = false
                o[Opt.XIAOMI_PERF] = false
                tips += "vivo/iQOO: the vivo store is throttled. In Ultra Game Mode, enable “Esports mode” only if you don't use the ZukuBoost panel."
            }
            else -> {
                o[Opt.MILLET] = false
                o[Opt.XIAOMI_PERF] = false
            }
        }

        // Čip
        val cool = when (d.socVendor) {
            SocVendor.EXYNOS, SocVendor.TENSOR -> {
                tips += "${d.socVendor.title} heats up quickly: earlier cooling (42 °C), consider a 60 FPS cap for demanding games."
                42
            }
            SocVendor.MEDIATEK -> {
                tips += "MediaTek: cooling from 44 °C. GPU load is read from the MediaTek driver if the system exposes it."
                44
            }
            SocVendor.SNAPDRAGON -> {
                tips += "Snapdragon: cooling from 45 °C, try the game GPU driver for the game in the Games tab (if your phone has it)."
                45
            }
            SocVendor.OTHER -> 44
        }

        // Paměť
        val killMode = 1
        if (d.ramGb <= 6) {
            tips += "${d.ramGb} GB RAM is low for gaming: everything except exceptions gets closed, and turn on the App freezer too (Settings → System)."
        } else if (d.ramGb >= 12) {
            tips += "${d.ramGb} GB RAM: plenty of memory, closing background apps mainly saves CPU and heat."
        }

        return DeviceRecommendation(o, cool, killMode, tips)
    }

    /** Výrobce → systémové appky, které na pozadí zbytečně pracují (utlumí se během hry). */
    fun vendorHogs(brand: Brand): List<String> = when (brand) {
        Brand.SAMSUNG -> listOf(
            "com.samsung.android.bixby.agent",
            "com.samsung.android.visionintelligence",
            "com.samsung.android.app.spage",
            "com.samsung.android.smartsuggestions",
            "com.sec.android.app.samsungapps",
            "com.samsung.android.scloud",
        )
        Brand.OPLUS -> listOf("com.heytap.market", "com.oppo.market", "com.heytap.cloud", "com.heytap.browser")
        Brand.VIVO -> listOf("com.bbk.appstore", "com.vivo.browser")
        else -> emptyList()
    }

    /** Výrobce bez Shizuku (z Build) – pro rychlé rozhodnutí v boostu. */
    fun quickBrand(): Brand = brandOf(Build.MANUFACTURER, Build.BRAND)
}
