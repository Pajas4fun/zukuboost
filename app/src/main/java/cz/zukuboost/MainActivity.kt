package cz.zukuboost

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.math.abs
import kotlin.math.max
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
                icon = runCatching { ai.loadIcon(pm).toBitmap(128, 128).asImageBitmap() }.getOrNull(),
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
    // MODE_DEFAULT znamená „rozhoduje oprávnění“ – to se dá udělit přes pm grant
    return mode == AppOpsManager.MODE_ALLOWED ||
        (
            mode == AppOpsManager.MODE_DEFAULT &&
                ctx.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            )
}

/** Ruční cesta, kdyby systém udělení přes Shizuku odmítl. */
fun openUsageAccessSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openOverlaySettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun openShizuku(ctx: Context): Boolean {
    val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: return false
    ctx.startActivity(intent)
    return true
}

private val SCALES = listOf(1f, 0.9f, 0.8f, 0.7f, 0.6f)

fun nextScale(cur: Float): Float {
    val i = SCALES.indexOfFirst { abs(it - cur) < 0.01f }
    return SCALES[(i + 1) % SCALES.size]
}

fun nextCap(cur: Int, options: List<Int>): Int {
    val i = options.indexOf(cur)
    return options[(i + 1) % options.size]
}

private fun freeRamMb(ctx: Context): Long {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return mi.availMem / 1_048_576
}

// ---------- Animace spuštění hry ----------

/** state: 0 čeká, 1 právě běží, 2 hotovo */
data class LaunchStep(val title: String, val state: Int)

class LaunchUi(val app: AppItem?, val label: String) {
    var steps by mutableStateOf(listOf<LaunchStep>())
    var freedMb by mutableIntStateOf(0)
    var killed by mutableIntStateOf(0)
    var progress by mutableFloatStateOf(0f)
    var phase by mutableStateOf("")
}

// ---------- Stav UI sdílený mezi obrazovkami ----------

class UiState(val ctx: Context, private val scope: CoroutineScope, private val snackbar: SnackbarHostState) {
    val prefs = Prefs(ctx)

    var games by mutableStateOf(prefs.games)
    var killList by mutableStateOf(prefs.killList)
    var monitorOn by mutableStateOf(GameMonitorService.running)
    var busy by mutableStateOf<String?>(null)
    var recheck by mutableIntStateOf(0)
    var writeOk by mutableIntStateOf(prefs.settingsWriteOk)
    var writeChecked = false
    var driverSupported by mutableStateOf(false)
    var driverApps by mutableStateOf(emptySet<String>())
    var gpuTuner by mutableStateOf(false)
    var freezer by mutableStateOf<BoostEngine.FreezerInfo?>(null)
    var diagText by mutableStateOf<String?>(null)
    var apps by mutableStateOf<List<AppItem>?>(null)
    var selected by mutableStateOf(prefs.lastGame)
    var historyTick by mutableIntStateOf(0)
    var launch by mutableStateOf<LaunchUi?>(null)
    var obstacles by mutableStateOf<List<BoostEngine.Obstacle>?>(null)

    val scales = mutableStateMapOf<String, Float>()
    val caps = mutableStateMapOf<String, Int>()
    val opts = mutableStateMapOf<Opt, Boolean>().apply { Opt.entries.forEach { put(it, prefs.opt(it)) } }

    var maxHz by mutableIntStateOf(BoostEngine.refreshRates(ctx).maxOrNull()?.roundToInt() ?: 60)

    val capOptions: List<Int>
        get() = listOf(0, 30, 45, 60, 90, 120, 144).filter { it == 0 || it <= maxHz + 1 }

    fun toast(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
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

    /** Ověří přes dumpsys, co systém z boostu opravdu používá. */
    fun runDiagnostics() {
        if (busy != null) return
        busy = "Ověřuji, co systém používá…"
        scope.launch {
            diagText = runCatching { BoostEngine.diagnose(ctx, selected) }.getOrElse { "Chyba: ${it.message}" }
            busy = null
        }
    }

    fun updateGames(v: Set<String>) {
        games = v
        prefs.games = v
        if (killList.any { it in v }) updateKill(killList - v)
    }

    fun updateKill(v: Set<String>) {
        killList = v
        prefs.killList = v
    }

    /** Zapne automatický boost (hlídání her). Vrací false, když chybí přístup k využití. */
    fun startMonitor(): Boolean {
        if (!hasUsageAccess(ctx)) return false
        prefs.monitorEnabled = true
        prefs.paused = false
        runCatching { GameMonitorService.start(ctx) }
        monitorOn = true
        recheck++
        return true
    }

    fun stopMonitor() {
        prefs.monitorEnabled = false
        GameMonitorService.stop(ctx)
        monitorOn = false
    }

    /**
     * Spuštění z domovské obrazovky s animací: celý boost se provede ještě před startem hry
     * (úklid, oznámení, Hz, profil…), kroky se postupně odškrtávají, pak se spustí hra.
     */
    fun launchGame(pkg: String) {
        if (busy != null || launch != null) return
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("Hru nejde spustit")
            return
        }
        prefs.lastGame = pkg
        val ui = LaunchUi(apps?.firstOrNull { it.pkg == pkg }, labelOf(pkg))
        launch = ui
        scope.launch {
          try {
            val shizukuOk = Shell.state() == Shell.State.READY
            // Bez přístupu k využití by po hře nikdo boost nevrátil – raději ho vůbec nespouštíme
            val usageOk = hasUsageAccess(ctx)
            val ready = shizukuOk && usageOk
            val titles = when {
                ready -> BoostEngine.launchSteps(ctx, pkg) + "Hra připravena"
                !shizukuOk -> listOf("Shizuku neběží – spouštím bez boostu")
                else -> listOf("Chybí přístup k využití – spouštím bez boostu")
            }
            ui.steps = titles.map { LaunchStep(it, 0) }
            val before = freeRamMb(ctx)
            ui.killed = (prefs.killList - pkg).count {
                runCatching { ctx.packageManager.getApplicationInfo(it, 0) }.isSuccess
            }

            // Služba hry nesmí boost vracet, dokud se hra nenačte do popředí
            prefs.launchGraceUntil = System.currentTimeMillis() + 20_000
            val work = async {
                if (ready) runCatching { BoostEngine.apply(ctx, pkg) } else null
            }

            titles.indices.forEach { i ->
                ui.steps = ui.steps.mapIndexed { j, st -> if (j == i) st.copy(state = 1) else st }
                // Poslední krok čeká, až je boost opravdu hotový
                if (i == titles.lastIndex) work.await() else delay(if (i == 0) 450L else 260L)
                ui.steps = ui.steps.mapIndexed { j, st -> if (j == i) st.copy(state = 2) else st }
                ui.progress = (i + 1f) / titles.size
                ui.freedMb = max(0L, freeRamMb(ctx) - before).toInt()
            }
            val result = work.await()
            ui.freedMb = max(0L, freeRamMb(ctx) - before).toInt()

            // Auto boost hlídá hru a po jejím konci vše vrátí
            var note = ""
            if (result?.isFailure == true) note = "Boost se nepodařil: ${result.exceptionOrNull()?.message}"
            if (ready && !GameMonitorService.running) startMonitor()
            if (shizukuOk && !usageOk) note = "Zapni přístup k využití v Nastavení → Oprávnění"
            ui.phase = "SPOUŠTÍM HRU"
            delay(550)
            val started = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            delay(700)
            if (!started) note = "Hru se nepodařilo spustit"
            if (note.isNotEmpty()) toast(note)
          } finally {
            launch = null // animace nesmí zůstat viset ani při chybě
          }
        }
    }
}

// ---------- Hlavní obrazovka s navigací ----------

private val TABS = listOf(
    "🏠" to "Domů",
    "🎮" to "Hry",
    "🧹" to "Kill list",
    "📊" to "Historie",
    "⚙️" to "Nastavení",
)

@Composable
fun MainScreen(shizuku: Shell.State, tick: Int, onRequestShizuku: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val s = remember { UiState(ctx, scope, snackbar) }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        s.apps = withContext(Dispatchers.IO) { loadApps(ctx) }
    }

    LaunchedEffect(tick, shizuku) {
        // Hlídání se samo obnoví, pokud bylo zapnuté (po restartu, po zabití systémem…)
        if (shizuku == Shell.State.READY && s.prefs.monitorEnabled && !GameMonitorService.running) {
            runCatching { GameMonitorService.start(ctx) }
            delay(500)
        }
        s.monitorOn = GameMonitorService.running
        if (shizuku == Shell.State.READY) {
            // Test zapisuje do nastavení – po úspěchu ho už při každém návratu do appky neopakujeme
            // Jednou za spuštění appky (uživatel mohl bezpečnostní ladění mezitím vypnout), jinak jen dokud neprojde
            if (s.writeOk != 1 || !s.writeChecked) {
                s.writeChecked = true
                s.writeOk = if (runCatching { BoostEngine.checkSettingsWrite(ctx) }.getOrDefault(false)) 1 else 0
            }
            s.driverSupported = runCatching { BoostEngine.gameDriverSupported() }.getOrDefault(false)
            if (s.driverSupported) {
                s.driverApps = runCatching { BoostEngine.gameDriverApps() }.getOrDefault(emptySet())
            }
            s.gpuTuner = runCatching { BoostEngine.gpuTunerEnabled() }.getOrDefault(false)
            s.obstacles = runCatching { BoostEngine.findObstacles() }.getOrNull()
            runCatching { BoostEngine.detectDisplayRates(ctx) }.getOrNull()?.maxOrNull()?.let { s.maxHz = it.roundToInt() }
            runCatching { BoostEngine.ensureFreezer(ctx) }
            s.freezer = runCatching { BoostEngine.freezerState() }.getOrNull()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Bg,
        bottomBar = { FloatingNavBar(TABS, tab) { tab = it } },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            GamingBackground()
            when (tab) {
                0 -> HomeScreen(s, shizuku, tick, onOpenGames = { tab = 1 }, onOpenSettings = { tab = 4 })
                1 -> GamesScreen(s, shizuku)
                2 -> KillListScreen(s)
                3 -> HistoryScreen(s, tick)
                else -> SettingsScreen(s, shizuku, tick, onRequestShizuku)
            }
            s.busy?.let { BusyOverlay(it) }
            s.launch?.let { LaunchOverlay(it) }
        }
    }
}
