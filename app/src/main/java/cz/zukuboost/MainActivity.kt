package cz.zukuboost

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val shizukuState = mutableStateOf(Shell.State.NOT_RUNNING)
    private val resumeTick = mutableIntStateOf(0)

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    private fun refresh() {
        shizukuState.value = Shell.state()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permResult)
        setContent {
            ZukuTheme {
                MainScreen(
                    shizuku = shizukuState.value,
                    tick = resumeTick.intValue,
                    onRequestShizuku = {
                        if (Shell.state() == Shell.State.NO_PERMISSION) Shizuku.requestPermission(100)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        resumeTick.intValue++
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permResult)
        super.onDestroy()
    }
}

// ---------- Data ----------

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap?, val isGame: Boolean)

@Suppress("DEPRECATION")
fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != ctx.packageName }
        .map { ai ->
            AppItem(
                label = ai.loadLabel(pm).toString(),
                pkg = ai.packageName,
                icon = runCatching { ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                isGame = ai.category == ApplicationInfo.CATEGORY_GAME ||
                    (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0,
            )
        }
        .sortedWith(compareByDescending<AppItem> { it.isGame }.thenBy { it.label.lowercase() })
}

fun hasUsageAccess(ctx: Context): Boolean {
    val aom = ctx.getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= 29) {
        aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    } else {
        @Suppress("DEPRECATION")
        aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun openShizuku(ctx: Context): Boolean {
    val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: return false
    ctx.startActivity(intent)
    return true
}

private val SCALES = listOf(1f, 0.9f, 0.8f, 0.7f, 0.6f)

private fun nextScale(cur: Float): Float {
    val i = SCALES.indexOfFirst { abs(it - cur) < 0.01f }
    return SCALES[(i + 1) % SCALES.size]
}

private fun nextCap(cur: Int, options: List<Int>): Int {
    val i = options.indexOf(cur)
    return options[(i + 1) % options.size]
}

// ---------- UI ----------

private val Accent = Color(0xFFFF5A36)

@Composable
fun ZukuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Color(0xFFFFB300),
            background = Color(0xFF0E0F13),
            surface = Color(0xFF0E0F13),
            surfaceVariant = Color(0xFF1C1F27),
        ),
        content = content,
    )
}

@Composable
fun MainScreen(shizuku: Shell.State, tick: Int, onRequestShizuku: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableIntStateOf(0) }
    var games by remember { mutableStateOf(prefs.games) }
    var killList by remember { mutableStateOf(prefs.killList) }
    var monitorOn by remember { mutableStateOf(GameMonitorService.running) }
    var busy by remember { mutableStateOf<String?>(null) }
    var recheck by remember { mutableIntStateOf(0) }
    val scales = remember { mutableStateMapOf<String, Float>() }
    val caps = remember { mutableStateMapOf<String, Int>() }
    var historyTick by remember { mutableIntStateOf(0) }
    val history = remember(tick, recheck, historyTick) { prefs.history() }
    val capOptions = remember {
        val maxHz = BoostEngine.refreshRates(ctx).maxOrNull()?.roundToInt() ?: 60
        listOf(0, 30, 45, 60, 90, 120, 144).filter { it == 0 || it <= maxHz }
    }
    val opts = remember { mutableStateMapOf<Opt, Boolean>().apply { Opt.entries.forEach { put(it, prefs.opt(it)) } } }
    val apps by produceState<List<AppItem>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }

    val ready = shizuku == Shell.State.READY
    val usageOk = remember(tick, shizuku, recheck) { hasUsageAccess(ctx) }
    val overlayOk = remember(tick, shizuku, recheck) { Settings.canDrawOverlays(ctx) }
    val joyose = remember(tick, recheck) { BoostEngine.joyoseState(ctx) }
    val paused = remember(tick, recheck) { prefs.paused }
    var writeOk by remember { mutableIntStateOf(prefs.settingsWriteOk) }
    val lastSession = remember(tick, recheck) { prefs.lastSession }

    // Hlídání se samo obnoví, pokud bylo zapnuté (po restartu, po zabití systémem…)
    LaunchedEffect(tick, shizuku) {
        if (shizuku == Shell.State.READY && prefs.monitorEnabled && !GameMonitorService.running) {
            runCatching { GameMonitorService.start(ctx) }
            delay(500)
        }
        monitorOn = GameMonitorService.running
        // Zjistíme, jestli HyperOS dovoluje zápis do systémových nastavení
        if (shizuku == Shell.State.READY) {
            writeOk = if (runCatching { BoostEngine.checkSettingsWrite(ctx) }.getOrDefault(false)) 1 else 0
        }
    }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    fun labelOf(pkg: String) = apps?.firstOrNull { it.pkg == pkg }?.label ?: pkg

    fun runTask(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch {
            val result = runCatching { block() }
            busy = null
            recheck++
            toast(result.fold({ it.ifBlank { "Hotovo" }.take(180) }, { "Chyba: ${it.message}" }))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text(
                "⚡ ZukuBoost",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("Boost", "Hry", "Kill list", "Historie").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            busy?.let { text ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }

            when (tab) {
                0 -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        InfoCard("Shizuku") {
                            Text(
                                when (shizuku) {
                                    Shell.State.NOT_RUNNING -> "❌ Shizuku neběží. Spusť ho v aplikaci Shizuku Next."
                                    Shell.State.NO_PERMISSION -> "⚠️ Shizuku běží, ale appka nemá oprávnění."
                                    Shell.State.READY -> "✅ Připojeno"
                                }
                            )
                            when (shizuku) {
                                Shell.State.NO_PERMISSION -> {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = onRequestShizuku) { Text("Povolit přístup") }
                                }
                                Shell.State.NOT_RUNNING -> {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = {
                                        if (!openShizuku(ctx)) toast("Shizuku není nainstalované")
                                    }) { Text("Otevřít Shizuku") }
                                }
                                Shell.State.READY -> Unit
                            }
                        }
                    }
                    item {
                        InfoCard("Oprávnění") {
                            Text(if (usageOk) "✅ Detekce her (přístup k využití)" else "❌ Detekce her (přístup k využití)")
                            Text(if (overlayOk) "✅ Overlay přes hru" else "❌ Overlay přes hru")
                            when (writeOk) {
                                1 -> Text("✅ Zápis systémových nastavení")
                                0 -> {
                                    Text("❌ Zápis systémových nastavení je blokovaný")
                                    Text(
                                        "HyperOS ho povolí až po zapnutí: Nastavení → Další nastavení → Možnosti pro vývojáře → " +
                                            "Ladění USB (Bezpečnostní nastavení). Vyžaduje přihlášený Xiaomi účet a SIM kartu. " +
                                            "Bez toho nefunguje Hz, Nerušit, spořič ani Xiaomi výkonový profil. Pak se sem vrať.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFFB300),
                                    )
                                }
                                else -> Unit
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(enabled = ready && busy == null, onClick = {
                                runTask("Nastavuji oprávnění…") {
                                    BoostEngine.grantSelf(ctx)
                                    "Oprávnění nastavena"
                                }
                            }) { Text("Nastavit vše přes Shizuku") }
                            Text(
                                "Na HyperOS ještě v nastavení appky povol Autostart a baterii nastav na Bez omezení, jinak systém službu zabije.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    item {
                        InfoCard("Automatický boost") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when {
                                        !monitorOn -> "Vypnuto"
                                        paused -> "Pozastaveno (dlaždice)"
                                        else -> "Hlídám spuštění her"
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = monitorOn,
                                    enabled = ready || monitorOn,
                                    onCheckedChange = { on ->
                                        if (on) {
                                            when {
                                                games.isEmpty() -> toast("Nejdřív vyber hry v záložce Hry")
                                                !usageOk -> toast("Chybí přístup k využití – klikni na Nastavit vše")
                                                else -> {
                                                    prefs.monitorEnabled = true
                                                    prefs.paused = false
                                                    GameMonitorService.start(ctx)
                                                    monitorOn = true
                                                    recheck++
                                                }
                                            }
                                        } else {
                                            prefs.monitorEnabled = false
                                            GameMonitorService.stop(ctx)
                                            monitorOn = false
                                        }
                                    },
                                )
                            }
                            if (monitorOn && paused) {
                                TextButton(onClick = {
                                    prefs.paused = false
                                    recheck++
                                }) { Text("Pokračovat") }
                            }
                            if (lastSession.isNotBlank()) {
                                Text(
                                    "Poslední hra: $lastSession",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            Text(
                                "Tip: přidej si dlaždici ZukuBoost do rychlého nastavení – jedním klepnutím boost pozastavíš.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    val joyoseOn = joyose
                    if (joyoseOn != null) {
                        item {
                            InfoCard("Xiaomi: výkon") {
                                Text(
                                    "Skrytý výběr výkonového režimu Xiaomi (Úspora / Vyvážený / Výkon). Nastavení vydrží i po hře.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(enabled = ready && busy == null, onClick = {
                                    runTask("Otevírám…") { BoostEngine.openXiaomiPowerMenu() }
                                }) { Text("Otevřít výkonový režim Xiaomi") }
                                Spacer(Modifier.height(16.dp))
                                Text("Joyose", fontWeight = FontWeight.Bold)
                                Text(
                                    "Systémová služba Xiaomi, která podle cloudových profilů omezuje výkon a FPS ve hrách. " +
                                        "Vypnutí může na některých telefonech zvednout výkon, ale i teploty. " +
                                        "Pozor: Joyose na některých modelech povoluje i režim vysokých FPS – když po vypnutí " +
                                        "hra spadne na 60 FPS, zapni ho zpět.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(if (joyoseOn) "Stav: zapnutá (omezuje)" else "Stav: vypnutá ✅")
                                Spacer(Modifier.height(8.dp))
                                Button(enabled = ready && busy == null, onClick = {
                                    runTask(if (joyoseOn) "Vypínám Joyose…" else "Zapínám Joyose…") {
                                        BoostEngine.setJoyose(!joyoseOn)
                                    }
                                }) { Text(if (joyoseOn) "Vypnout Joyose" else "Zapnout Joyose zpět") }
                            }
                        }
                    }
                    item {
                        Column {
                            Text(
                                "Co se zapne při hraní",
                                fontWeight = FontWeight.Bold,
                                color = Accent,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                "Proti propadům FPS nejvíc pomáhá FPS limit na hodnotu, kterou hra udrží i zahřátá " +
                                    "(nastavíš v záložce Hry), vypnutý Joyose a stabilní výkon. Hlavní příčina propadů je teplo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    items(Opt.entries.toList(), key = { it.key }) { o ->
                        OptionRow(o, opts[o] == true) { v ->
                            opts[o] = v
                            prefs.setOpt(o, v)
                        }
                    }
                    item {
                        Column {
                            OutlinedButton(
                                enabled = ready && busy == null,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    runTask("Vracím nastavení…") {
                                        BoostEngine.restore(ctx)
                                        "Původní nastavení vráceno"
                                    }
                                },
                            ) { Text("Vrátit původní nastavení") }
                            Text(
                                "Tip: pro vybrané hry vypni HyperOS Game Turbo, ať se s ZukuBoostem nepere o nastavení.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }

                1 -> AppList(
                    apps = apps,
                    selected = games,
                    exclude = emptySet(),
                    hint = "Zaškrtni hry. 🎮 = appka se sama hlásí jako hra.",
                    onToggle = { pkg, on ->
                        games = if (on) games + pkg else games - pkg
                        prefs.games = games
                        if (on && pkg in killList) {
                            killList = killList - pkg
                            prefs.killList = killList
                        }
                    },
                    header = {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = apps != null,
                                onClick = {
                                    val found = apps.orEmpty().filter { it.isGame }.map { it.pkg }.toSet()
                                    games = games + found
                                    prefs.games = games
                                    killList = killList - found
                                    prefs.killList = killList
                                    toast("Přidáno ${found.size} her")
                                },
                            ) { Text("Přidat všechny 🎮") }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = ready && busy == null && games.isNotEmpty(),
                                onClick = {
                                    val list = games.toList()
                                    runTask("Kompiluji hry…") {
                                        var ok = 0
                                        list.forEachIndexed { i, pkg ->
                                            busy = "Kompiluji ${i + 1}/${list.size}: ${labelOf(pkg)}"
                                            if (BoostEngine.compileSpeed(pkg).contains("Success")) ok++
                                        }
                                        "Zkompilováno $ok/${list.size} her"
                                    }
                                },
                            ) { Text("Kompilovat vše") }
                        }
                    },
                    extra = { app ->
                        if (app.pkg in games) {
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(
                                    enabled = ready && busy == null,
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    onClick = {
                                        runTask("Kompiluji ${app.label}… (může trvat i minutu)") {
                                            BoostEngine.compileSpeed(app.pkg)
                                        }
                                    },
                                ) { Text("Kompilovat", style = MaterialTheme.typography.labelMedium) }
                                if (Build.VERSION.SDK_INT >= 33) {
                                    val curScale = scales[app.pkg] ?: prefs.scale(app.pkg)
                                    val curCap = caps[app.pkg] ?: prefs.fpsCap(app.pkg)
                                    TextButton(
                                        enabled = ready && busy == null,
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        onClick = {
                                            val next = nextCap(curCap, capOptions)
                                            caps[app.pkg] = next
                                            prefs.setFpsCap(app.pkg, next)
                                            runTask("Nastavuji FPS limit…") {
                                                val out = BoostEngine.setGameConfig(app.pkg, curScale, next)
                                                val name = if (next == 0) "vypnutý" else "$next FPS"
                                                "FPS limit $name: ${out.ifBlank { "OK" }}"
                                            }
                                        },
                                    ) {
                                        Text(
                                            if (curCap == 0) "FPS limit: vyp" else "FPS limit: $curCap",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    TextButton(
                                        enabled = ready && busy == null,
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        onClick = {
                                            val next = nextScale(curScale)
                                            scales[app.pkg] = next
                                            prefs.setScale(app.pkg, next)
                                            runTask("Nastavuji rozlišení…") {
                                                val out = BoostEngine.setGameConfig(app.pkg, next, curCap)
                                                "Rozlišení ${(next * 100).roundToInt()} %: ${out.ifBlank { "OK" }}"
                                            }
                                        },
                                    ) {
                                        Text(
                                            "Rozlišení ${(curScale * 100).roundToInt()} %",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                    },
                )

                2 -> AppList(
                    apps = apps,
                    selected = killList,
                    exclude = games,
                    hint = "Appky, které se při spuštění hry ukončí (Facebook, Shopee, TikTok…). " +
                        "Nedávej sem Discord, pokud s ním hraješ.",
                    onToggle = { pkg, on ->
                        killList = if (on) killList + pkg else killList - pkg
                        prefs.killList = killList
                    },
                )

                else -> HistoryList(history) {
                    prefs.clearHistory()
                    historyTick++
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Accent)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun OptionRow(o: Opt, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(o.title, style = MaterialTheme.typography.bodyLarge)
            Text(o.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AppList(
    apps: List<AppItem>?,
    selected: Set<String>,
    exclude: Set<String>,
    hint: String,
    onToggle: (String, Boolean) -> Unit,
    header: (@Composable () -> Unit)? = null,
    extra: (@Composable (AppItem) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        header?.invoke()
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Hledat") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (apps == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val shown = remember(apps, query, exclude) {
                apps.filter {
                    it.pkg !in exclude && (
                        query.isBlank() ||
                            it.label.contains(query, ignoreCase = true) ||
                            it.pkg.contains(query, ignoreCase = true)
                        )
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.pkg }) { app ->
                    val checked = app.pkg in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(app.pkg, !checked) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = app.icon
                        if (icon != null) {
                            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                        } else {
                            Spacer(Modifier.size(40.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label + if (app.isGame) "  🎮" else "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                app.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            extra?.invoke(app)
                        }
                        Checkbox(checked = checked, onCheckedChange = { onToggle(app.pkg, it) })
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryList(history: List<Session>, onClear: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d. M. HH:mm", Locale.forLanguageTag("cs")) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text(
                    "Každá hra delší než minuta se tu zapíše. Zahraj si stejnou hru s Game Turbem a se ZukuBoostem " +
                        "a porovnej ø FPS, minimum a propady (⚠ = FPS kleslo pod 75 % průměru).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) { Text("Smazat historii") }
                }
            }
        }
        if (history.isEmpty()) {
            item { Text("Zatím žádné záznamy.", modifier = Modifier.padding(top = 16.dp)) }
        }
        items(history, key = { "${it.pkg}_${it.start}" }) { h ->
            InfoCard(h.label) {
                Text("${fmt.format(Date(h.start))} · ${h.minutes} min", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("ø ").append(h.avg?.toString() ?: "--").append(" FPS")
                        append("   min ").append(h.min?.toString() ?: "--")
                        append("   ⚠ propady ").append(h.drops)
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    buildString {
                        append("CPU max ").append(h.maxCpu?.let { "${it.roundToInt()} °C" } ?: "--")
                        append("   baterie max ").append(h.maxBat?.let { String.format(Locale.US, "%.1f °C", it) } ?: "--")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
