package cz.zukuboost

import android.app.ActivityManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ======================= DOMŮ =======================

@Composable
fun HomeScreen(s: UiState, shizuku: Shell.State, tick: Int, onOpenGames: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = s.ctx
    val ready = shizuku == Shell.State.READY
    var batTemp by remember { mutableFloatStateOf(0f) }
    var freeRam by remember { mutableFloatStateOf(0f) }
    var totalRam by remember { mutableFloatStateOf(1f) }

    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
          while (true) {
            batTemp = batteryTemp(ctx)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            freeRam = mi.availMem / 1_073_741_824f
            totalRam = (mi.totalMem / 1_073_741_824f).coerceAtLeast(1f)
            delay(2_000)
          }
        }
    }

    val gameItems = remember(s.games, s.apps) {
        s.apps.orEmpty().filter { it.pkg in s.games }.sortedBy { it.label.lowercase() }
    }
    LaunchedEffect(gameItems) {
        if (gameItems.none { it.pkg == s.selected }) s.selected = gameItems.firstOrNull()?.pkg
    }
    val sel = gameItems.firstOrNull { it.pkg == s.selected }
    val lastSession = remember(tick, s.historyTick) { s.prefs.lastSession }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // --- horní lišta: logo + stav ---
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(SkewShape(8.dp)).background(NeonRed),
                    contentAlignment = Alignment.Center,
                ) { Text("⚡", fontSize = 16.sp) }
                Spacer(Modifier.width(10.dp))
                GradientTitle("ZukuBoost", 24)
                Spacer(Modifier.weight(1f))
                val c = if (ready) NeonGreen else NeonRed
                Row(
                    Modifier.clickable { onOpenSettings() }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Diamond(c)
                    Spacer(Modifier.width(7.dp))
                    Text(if (ready) "Connected" else "Shizuku not running", color = c, fontSize = 13.sp, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // --- banner hry: jediný výrazný prvek ---
        item {
            if (sel == null) {
                NeonCard(title = "No games") {
                    Text("Add games and ZukuBoost will launch them boosted.", color = TextDim)
                    Spacer(Modifier.height(14.dp))
                    NeonButton("+ Add games", onOpenGames)
                }
            } else {
                val shape = ChamferShape(18.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(shape)
                        .background(Brush.linearGradient(listOf(Color(0xFF2B1A5C), Color(0xFF4A1036), Color(0xFF120E22))))
                        .hudBrackets(NeonCyan),
                ) {
                    // umění hry: zvětšená rozmazaná ikona vpravo nahoře
                    s.hiRes(sel)?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 40.dp, y = (-10).dp)
                                .size(260.dp)
                                .rotate(-8f)
                                .blur(18.dp)
                                .alpha(0.55f),
                        )
                    }
                    SpeedLines(Modifier.matchParentSize())
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Brush.verticalGradient(0.3f to Color.Transparent, 0.75f to Bg.copy(alpha = 0.85f), 1f to Bg)),
                    )
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GameIcon(sel.copy(icon = s.hiRes(sel)), 46)
                            Spacer(Modifier.width(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Diamond(NeonGreen, 6.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Ready to boost", color = NeonGreen, fontSize = 13.sp, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            sel.label,
                            fontSize = 34.sp,
                            lineHeight = 36.sp,
                            fontFamily = ChakraPetch,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        val cap = s.caps[sel.pkg] ?: s.prefs.fpsCap(sel.pkg)
                        val scale = s.scales[sel.pkg] ?: s.prefs.scale(sel.pkg)
                        val hz = s.prefs.hzMode
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ProfileChip(if (cap == 0) "Max FPS" else "$cap FPS")
                            ProfileChip(if (hz == 0) "Auto Hz" else "$hz Hz", NeonPurple)
                            if (scale < 0.99f) ProfileChip("${(scale * 100).roundToInt()} %", NeonYellow)
                            ProfileChip(if (s.killMode == 1) "Close all except ${s.keepList.size}" else "Kill list ${s.killList.size}", NeonRed)
                        }
                        Spacer(Modifier.height(16.dp))
                        LaunchButton(enabled = s.busy == null && s.launch == null) { s.launchGame(sel.pkg) }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (s.monitorOn) "Auto boost is watching for games" else "Auto boost turns on at launch",
                            color = if (s.monitorOn) TextDim else NeonYellow,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { onOpenSettings() },
                        )
                    }
                }
            }
        }

        // --- HUD ukazatele ---
        item {
            NeonCard(accent = NeonCyan) {
                HudMetric(
                    "Temperature",
                    String.format(Locale.US, "%.0f°", batTemp),
                    (batTemp - 20f) / 30f,
                    when {
                        batTemp >= 44f -> NeonRed
                        batTemp >= 40f -> NeonYellow
                        else -> NeonGreen
                    },
                )
                HudMetric("Free RAM", String.format(Locale.US, "%.1f GB", freeRam), freeRam / totalRam, NeonCyan)
                HudMetric("Display", "${s.maxHz} Hz", s.maxHz / 144f, NeonPurple)
            }
        }

        // --- knihovna her ---
        if (gameItems.isNotEmpty()) {
            item {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Game library", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            when (gameItems.size) {
                                1 -> "1 game"
                                in 2..4 -> "${gameItems.size} games"
                                else -> "${gameItems.size} games"
                            },
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(gameItems, key = { it.pkg }) { g ->
                            GameTile(g.copy(icon = s.hiRes(g)), g.pkg == s.selected) {
                                s.selected = g.pkg
                                s.prefs.lastGame = g.pkg
                            }
                        }
                        item { AddTile(onOpenGames) }
                    }
                }
            }
        }

        // --- čistič ---
        item { CleanerCard(s, ready) }

        if (lastSession.isNotBlank()) {
            item {
                NeonCard(title = "Last game", accent = NeonPurple) {
                    Text(lastSession, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!ready) {
            item {
                NeonCard(title = "Shizuku not running", accent = NeonYellow) {
                    Text("Without Shizuku, games launch without boost.", color = TextDim)
                    Spacer(Modifier.height(12.dp))
                    NeonButton("Open settings", onOpenSettings, color = NeonYellow, outlined = true)
                }
            }
        }
    }
}

@Composable
fun GameIcon(app: AppItem, sizeDp: Int) {
    val shape = ChamferShape((sizeDp / 6).dp)
    val icon = app.icon
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(Color(0xFF171A27)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
        } else {
            Text("🎮", fontSize = (sizeDp / 2).sp)
        }
    }
}

@Composable
fun GameTile(app: AppItem, selected: Boolean, onClick: () -> Unit) {
    val shape = ChamferShape(12.dp)
    Column(
        Modifier
            .width(74.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            if (selected) {
                Modifier.border(2.dp, NeonRed, shape).padding(3.dp)
            } else {
                Modifier.padding(3.dp)
            },
        ) {
            GameIcon(app, 64)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.label,
            fontSize = 11.sp,
            color = if (selected) Color.White else TextDim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AddTile(onClick: () -> Unit) {
    val shape = ChamferShape(12.dp)
    Column(
        Modifier.width(74.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(64.dp)
                .clip(shape)
                .background(NeonCyan.copy(alpha = 0.07f))
                .border(1.dp, NeonCyan.copy(alpha = 0.45f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = NeonCyan, fontSize = 30.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(6.dp))
        Text("Add", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
    }
}

// ======================= HRY =======================

@Composable
fun GamesScreen(s: UiState, shizuku: Shell.State) {
    val ready = shizuku == Shell.State.READY
    AppList(
        title = "Games",
        apps = s.apps,
        selected = s.games,
        exclude = emptySet(),
        hint = "Check your games. 🎮 = the app reports itself as a game. Selected games get a profile.",
        onToggle = { pkg, on -> s.updateGames(if (on) s.games + pkg else s.games - pkg) },
        header = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeonButton(
                    "+ All 🎮",
                    onClick = {
                        val found = s.apps.orEmpty().filter { it.isGame }.map { it.pkg }.toSet()
                        s.updateGames(s.games + found)
                        s.toast("Added ${found.size} games")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = s.apps != null,
                    color = NeonCyan,
                    outlined = true,
                )
                NeonButton(
                    "Compile all",
                    onClick = {
                        val list = s.games.toList()
                        s.runTask("Compiling games…") {
                            var ok = 0
                            list.forEachIndexed { i, pkg ->
                                s.busy = "Compiling ${i + 1}/${list.size}: ${s.labelOf(pkg)}"
                                if (BoostEngine.compileSpeed(pkg).contains("Success")) ok++
                            }
                            "Compiled $ok/${list.size} games"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = ready && s.busy == null && s.games.isNotEmpty(),
                    outlined = true,
                )
            }
        },
        extra = { app ->
            if (app.pkg in s.games) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    SmallAction("Compile", ready && s.busy == null) {
                        s.runTask("Compiling ${app.label}… (may take a minute)") { BoostEngine.compileSpeed(app.pkg) }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        val curScale = s.scales[app.pkg] ?: s.prefs.scale(app.pkg)
                        val curCap = s.caps[app.pkg] ?: s.prefs.fpsCap(app.pkg)
                        SmallAction(if (curCap == 0) "FPS: max" else "FPS: $curCap", ready && s.busy == null) {
                            val next = nextCap(curCap, s.capOptions)
                            s.caps[app.pkg] = next
                            s.prefs.setFpsCap(app.pkg, next)
                            s.runTask("Setting FPS cap…") {
                                val out = BoostEngine.setGameConfig(s.ctx, app.pkg, curScale, next)
                                val name = if (next == 0) "off" else "$next FPS"
                                "FPS cap $name: ${out.ifBlank { "OK" }}"
                            }
                        }
                        SmallAction("Resolution ${(curScale * 100).roundToInt()} %", ready && s.busy == null) {
                            val next = nextScale(curScale)
                            s.scales[app.pkg] = next
                            s.prefs.setScale(app.pkg, next)
                            s.runTask("Setting resolution…") {
                                val out = BoostEngine.setGameConfig(s.ctx, app.pkg, next, curCap)
                                "Resolution ${(next * 100).roundToInt()} %: ${out.ifBlank { "OK" }}"
                            }
                        }
                    }
                    if (s.driverSupported) {
                        val gameDriver = app.pkg in s.driverApps
                        SmallAction(if (gameDriver) "GPU: game" else "GPU: default", ready && s.busy == null) {
                            val on = !gameDriver
                            s.driverApps = if (on) s.driverApps + app.pkg else s.driverApps - app.pkg
                            s.runTask("Setting GPU driver…") { BoostEngine.setGameDriver(app.pkg, on) }
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun SmallAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp), onClick = onClick) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (enabled) NeonCyan else TextDim)
    }
}

// ======================= KILL LIST =======================

@Composable
fun KillListScreen(s: UiState) {
    val allExcept = s.killMode == 1
    AppList(
        title = if (allExcept) "Exceptions" else "Kill list",
        apps = s.apps,
        selected = if (allExcept) s.keepList else s.killList,
        exclude = s.games,
        hint = if (allExcept) {
            "When a game starts, all background apps are closed except the checked ones. Check what should keep running – " +
                "messages, music, watch. A closed app gets no notifications until you open it again."
        } else {
            "When a game starts, only the checked apps are closed (Facebook, Shopee, TikTok…)."
        },
        onToggle = { pkg, on ->
            if (allExcept) {
                s.updateKeep(if (on) s.keepList + pkg else s.keepList - pkg)
            } else {
                s.updateKill(if (on) s.killList + pkg else s.killList - pkg)
            }
        },
        header = {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "All except exceptions", 0 to "Selected only").forEach { (mode, name) ->
                        val sel = s.killMode == mode
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(ChamferShape(9.dp))
                                .background(if (sel) NeonRed.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f))
                                .border(1.dp, if (sel) NeonRed else Color.White.copy(alpha = 0.12f), ChamferShape(9.dp))
                                .clickable {
                                    s.setKillMode(mode)
                                    if (mode == 1) s.ensureDefaultKeep()
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name,
                                color = if (sel) Color.White else TextDim,
                                fontFamily = ChakraPetch,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
                if (allExcept) {
                    Text(
                        "Always kept running: launcher, keyboard, accessibility services, apps reading notifications (watch), " +
                            "always-on VPN, live wallpaper, system apps" +
                            if (s.opts[Opt.PROTECT_VOICE] == true) ", voice apps (Discord…)." else ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    NeonButton(
                        "+ Recommended exceptions",
                        {
                            val added = BoostEngine.DEFAULT_KEEP.filter { p ->
                                p !in s.keepList && s.apps.orEmpty().any { it.pkg == p }
                            }
                            s.updateKeep(s.keepList + added)
                            s.toast(if (added.isEmpty()) "You already have the recommended exceptions" else "Added ${added.size} exceptions (messages, music, watch)")
                        },
                        color = NeonCyan,
                        outlined = true,
                    )
                }
            }
        },
    )
}

// ======================= HISTORIE =======================

@Composable
fun HistoryScreen(s: UiState, tick: Int) {
    val history = remember(tick, s.recheck, s.historyTick) { s.prefs.history() }
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("History") }
        item {
            Column {
                Text(
                    "Every game longer than a minute is logged here. Play the same game with Game Turbo and with ZukuBoost " +
                        "and compare average FPS, minimum and drops (⚠ = FPS fell below 75 % of the average).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    NeonButton("Clear history", {
                        s.prefs.clearHistory()
                        s.historyTick++
                    }, outlined = true)
                }
            }
        }
        if (history.isEmpty()) {
            item { Text("No records yet.", color = TextDim, modifier = Modifier.padding(top = 16.dp)) }
        }
        items(history, key = { "${it.pkg}_${it.start}" }) { h ->
            NeonCard(title = h.label, accent = NeonCyan) {
                Text("${fmt.format(Date(h.start))} · ${h.minutes} min", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("Ø FPS", h.avg?.toString() ?: "--", NeonCyan, Modifier.weight(1f))
                    StatChip("Minimum", h.min?.toString() ?: "--", NeonYellow, Modifier.weight(1f))
                    StatChip("Drops", h.drops.toString(), if (h.drops > 5) NeonRed else NeonGreen, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("CPU max ").append(h.maxCpu?.let { "${it.roundToInt()} °C" } ?: "--")
                        append("   ·   battery max ").append(h.maxBat?.let { String.format(Locale.US, "%.1f °C", it) } ?: "--")
                    },
                    color = TextDim,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// ======================= NASTAVENÍ =======================

@Composable
fun SettingsScreen(s: UiState, shizuku: Shell.State, tick: Int, onRequestShizuku: () -> Unit) {
    val ctx = s.ctx
    val ready = shizuku == Shell.State.READY
    val usageOk = remember(tick, shizuku, s.recheck) { hasUsageAccess(ctx) }
    val overlayOk = remember(tick, shizuku, s.recheck) { Settings.canDrawOverlays(ctx) }
    val joyose = remember(tick, s.recheck) { BoostEngine.joyoseState(ctx) }
    val paused = remember(tick, s.recheck) { s.prefs.paused }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Settings") }
        item { DeviceCard(s, ready) }

        // ---------- Připojení ----------
        item { SectionHeader("Connection") }
        item {
            NeonCard(title = "Shizuku", accent = if (ready) NeonGreen else NeonRed) {
                Text(
                    when (shizuku) {
                        Shell.State.NOT_RUNNING -> "❌ Shizuku isn't running. Start it in the Shizuku Next app."
                        Shell.State.NO_PERMISSION -> "⚠️ Shizuku is running, but the app has no permission."
                        Shell.State.READY -> "✅ Connected"
                    }
                )
                when (shizuku) {
                    Shell.State.NO_PERMISSION -> {
                        Spacer(Modifier.height(10.dp))
                        NeonButton("Allow access", onRequestShizuku)
                    }
                    Shell.State.NOT_RUNNING -> {
                        Spacer(Modifier.height(10.dp))
                        NeonButton("Open Shizuku", { if (!openShizuku(ctx)) s.toast("Shizuku isn't installed") })
                    }
                    Shell.State.READY -> Unit
                }
            }
        }
        item {
            NeonCard(title = "Permissions") {
                Text(if (usageOk) "✅ Game detection (usage access)" else "❌ Game detection (usage access)")
                Text(if (overlayOk) "✅ Overlay over games" else "❌ Overlay over games")
                when (s.writeOk) {
                    1 -> Text("✅ Writing system settings")
                    0 -> {
                        Text("❌ Writing system settings is blocked")
                        Text(
                            "HyperOS only allows it after enabling: Settings → Additional settings → Developer options → " +
                                "USB debugging (Security settings). Requires a signed-in Xiaomi account and a SIM card. " +
                                "Without it, Hz, Do Not Disturb, battery saver and the Xiaomi performance profile don't work.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonYellow,
                        )
                    }
                    else -> Unit
                }
                Spacer(Modifier.height(10.dp))
                NeonButton("Set up everything via Shizuku", {
                    s.runTask("Granting permissions…") {
                        val result = BoostEngine.grantSelf(ctx)
                        delay(500) // systém chvíli propisuje změny
                        result
                    }
                }, enabled = ready && s.busy == null)
                if (!usageOk || !overlayOk) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Still ❌? Allow it manually – find ZukuBoost in the list and turn it on:",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonYellow,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!usageOk) {
                            NeonButton(
                                "Usage access",
                                { openUsageAccessSettings(ctx) },
                                modifier = Modifier.weight(1f),
                                color = NeonCyan,
                                outlined = true,
                            )
                        }
                        if (!overlayOk) {
                            NeonButton(
                                "Overlay",
                                { openOverlaySettings(ctx) },
                                modifier = Modifier.weight(1f),
                                color = NeonCyan,
                                outlined = true,
                            )
                        }
                    }
                }
                Text(
                    "On HyperOS also enable Autostart in the app settings and set battery to No restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ---------- Automatika ----------
        item { SectionHeader("Automation") }
        item {
            NeonCard(title = "Automatic boost") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !s.monitorOn -> "Off"
                            paused -> "Paused (tile)"
                            else -> "Watching for games"
                        },
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                    )
                    Switch(
                        checked = s.monitorOn,
                        enabled = ready || s.monitorOn,
                        onCheckedChange = { on ->
                            if (on) {
                                when {
                                    s.games.isEmpty() -> s.toast("Select games in the Games tab first")
                                    !s.startMonitor() -> s.toast("Usage access missing – tap Set up everything")
                                }
                            } else {
                                s.stopMonitor()
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = NeonRed, checkedThumbColor = Color.White),
                    )
                }
                if (s.monitorOn && paused) {
                    TextButton(onClick = {
                        s.prefs.paused = false
                        s.recheck++
                    }) { Text("Resume", color = NeonCyan) }
                }
                Text(
                    "Tip: add the ZukuBoost tile to Quick Settings – pause the boost with one tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ---------- Při hraní ----------
        item { SectionHeader("While playing") }
        item {
            NeonCard {
                Text(
                    "What helps most against FPS drops is an FPS cap the game can hold even when hot (Games tab), " +
                        "disabled Joyose and stable clocks. Heat is the main cause of drops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(6.dp))
                Opt.entries.forEach { o ->
                    OptionRow(o, s.opts[o] == true) { v ->
                        s.opts[o] = v
                        s.prefs.setOpt(o, v)
                    }
                }
                HzChooser(s)
                if (s.opts[Opt.SMART_COOL] == true) {
                    var coolStart by remember { mutableIntStateOf(s.prefs.coolStartC) }
                    TextButton(onClick = {
                        val next = if (coolStart >= 47) 41 else coolStart + 1
                        coolStart = next
                        s.prefs.coolStartC = next
                    }) {
                        Text(
                            "Cooling starts at: $coolStart °C (step 2 at ${coolStart + 3} °C, back below ${coolStart - 3} °C)",
                            color = NeonCyan,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (s.opts[Opt.EDGE_BLOCK] == true) {
                    var width by remember { mutableIntStateOf(s.prefs.edgeWidthDp) }
                    TextButton(onClick = {
                        val next = when (width) {
                            12 -> 16
                            16 -> 24
                            24 -> 32
                            else -> 12
                        }
                        width = next
                        s.prefs.edgeWidthDp = next
                    }) {
                        Text("Blocked edge width: $width dp (applies from the next game)", color = NeonCyan, fontSize = 13.sp)
                    }
                }
            }
        }

        item { Hz120Help() }

        // ---------- Xiaomi ----------
        val joyoseOn = joyose
        if (joyoseOn != null) {
            item { SectionHeader("Xiaomi", NeonYellow) }
            item {
                NeonCard(title = "Performance mode", accent = NeonYellow) {
                    Text(
                        "Xiaomi's hidden performance mode picker (Battery saver / Balanced / Performance). The setting persists after the game.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    NeonButton("Open performance mode", {
                        s.runTask("Opening…") { BoostEngine.openXiaomiPowerMenu() }
                    }, enabled = ready && s.busy == null, color = NeonYellow, outlined = true)
                }
            }
            item {
                NeonCard(title = "Game Turbo GPU settings", accent = NeonYellow) {
                    Text(
                        "On many phones Xiaomi hid the GPU performance option in Game Turbo. This tries to unlock it. " +
                            "Doesn't work on some models – then nothing happens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(if (s.gpuTuner) "Status: unlocked ✅" else "Status: hidden", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    NeonButton(if (s.gpuTuner) "Hide again" else "Unlock GPU settings", {
                        val on = !s.gpuTuner
                        s.gpuTuner = on
                        s.runTask("Applying…") { BoostEngine.setGpuTuner(ctx, on) }
                    }, enabled = ready && s.busy == null, color = NeonYellow, outlined = s.gpuTuner)
                }
            }
            item {
                NeonCard(title = "Joyose", accent = NeonYellow) {
                    Text(
                        "A Xiaomi system service that limits game performance and FPS based on cloud profiles. " +
                            "Disabling it can raise performance, but also temperatures. Careful: on some models it also enables " +
                            "high-FPS mode – if the game drops to 60 FPS after disabling it, turn it back on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (joyoseOn) "Status: enabled (limiting)" else "Status: disabled ✅",
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    NeonButton(if (joyoseOn) "Disable Joyose" else "Re-enable Joyose", {
                        s.runTask(if (joyoseOn) "Disabling Joyose…" else "Enabling Joyose…") {
                            BoostEngine.setJoyose(!joyoseOn)
                        }
                    }, enabled = ready && s.busy == null, color = NeonYellow)
                }
            }
        }

        // ---------- Systém ----------
        item { SectionHeader("System") }
        item {
            val list = s.obstacles
            NeonCard(title = "Gaming obstacles", accent = if (list.isNullOrEmpty()) NeonGreen else NeonYellow) {
                Text(
                    "Developer options that slow down gaming (often enabled by mistake or because of “gaming tips”).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    list == null -> Text("Checked once Shizuku is running.", color = TextDim)
                    list.isEmpty() -> Text("✅ Nothing is slowing you down", fontWeight = FontWeight.Bold, color = NeonGreen)
                    else -> {
                        list.forEach { o ->
                            Text("⚠️ ${o.title}", fontWeight = FontWeight.Bold)
                            Text(o.why, style = MaterialTheme.typography.bodySmall, color = TextDim)
                            Spacer(Modifier.height(6.dp))
                        }
                        NeonButton("Fix all", {
                            s.runTask("Fixing…") {
                                val msg = BoostEngine.fixObstacles(list)
                                s.obstacles = BoostEngine.findObstacles()
                                msg
                            }
                        }, enabled = ready && s.busy == null, color = NeonYellow)
                    }
                }
            }
        }
        item {
            NeonCard(title = "Background app freezer", accent = NeonCyan) {
                Text(
                    "Apps in memory are frozen 10 s after going to the background and don't take CPU from the game. " +
                        "HyperOS has its own freezer (Millet) and often turns Android's off after a restart – " +
                        "ZukuBoost turns it back on automatically once Shizuku is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(8.dp))
                val info = s.freezer
                val wanted = s.prefs.freezerWanted
                Text(
                    "Setting: " + when (info?.setting) {
                        "enabled" -> "zapnuto"
                        "disabled" -> "disabled by the system"
                        null -> "unknown (Shizuku not running)"
                        else -> "system default"
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Actual state: " + when (info?.active) {
                        true -> "running ✅"
                        false -> "not running"
                        null -> "can't be determined"
                    },
                    fontWeight = FontWeight.Bold,
                    color = if (info?.active == true) NeonGreen else TextDim,
                )
                if (wanted && info?.active == false) {
                    Text(
                        "If it stays “not running” even after a restart and opening ZukuBoost, your HyperOS doesn't allow Android's " +
                            "freezer. No problem – its own Millet does the freezing then.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonYellow,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                NeonButton(if (wanted) "Disable" else "Enable freezer", {
                    s.runTask("Applying…") {
                        val msg = BoostEngine.setFreezer(ctx, !wanted)
                        s.freezer = BoostEngine.freezerState()
                        msg
                    }
                }, enabled = ready && s.busy == null, color = NeonCyan, outlined = wanted)
            }
        }
        item {
            NeonCard(title = "Boost diagnostics", accent = NeonCyan) {
                Text(
                    "The system never reports that it ignores a setting. This reads the actual state (Hz, Game Mode, " +
                        "temperatures, performance mode). Best run during a game or right after it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(10.dp))
                NeonButton("Verify boost", { s.runDiagnostics() }, enabled = ready && s.busy == null, color = NeonCyan)
                s.diagText?.let { text ->
                    Spacer(Modifier.height(10.dp))
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFCFD3DC))
                }
            }
        }

        // ---------- Obnova ----------
        item { SectionHeader("Restore") }
        item {
            NeonCard {
                Text(
                    "If something stays on after a game (Do Not Disturb, Hz…), this restores everything to how it was.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(10.dp))
                NeonButton("Restore original settings", {
                    s.runTask("Restoring settings…") {
                        BoostEngine.restore(ctx)
                        "Original settings restored"
                    }
                }, enabled = ready && s.busy == null, outlined = true)
                Text(
                    "Tip: turn off HyperOS Game Turbo for the selected games so it doesn't fight ZukuBoost over settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

// ======================= Sdílené =======================

@Composable
fun ScreenTitle(text: String) {
    Column(Modifier.padding(top = 8.dp)) {
        GradientTitle(text, 28)
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(42.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(NeonRed, NeonCyan))),
        )
    }
}

@Composable
fun AppList(
    title: String,
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
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            ScreenTitle("$title (${selected.size})")
            Text(hint, style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(vertical = 8.dp))
        }
        header?.invoke()
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (apps == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonRed)
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
                        GameIcon(app, 44)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label + if (app.isGame) "  🎮" else "",
                                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                app.pkg,
                                fontSize = 11.sp,
                                color = TextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            extra?.invoke(app)
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggle(app.pkg, it) },
                            colors = CheckboxDefaults.colors(checkedColor = NeonRed, checkmarkColor = Color.White),
                        )
                    }
                }
            }
        }
    }
}


// ======================= Výběr Hz, nápověda, čistič =======================

@Composable
fun HzChooser(s: UiState) {
    var mode by remember { mutableIntStateOf(s.prefs.hzMode) }
    val supported = remember(s.maxHz) { BoostEngine.refreshRates(s.ctx).map { it.roundToInt() }.toSet() }
    Column(Modifier.padding(top = 10.dp)) {
        Text("Refresh rate in game", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Auto = based on the FPS cap and display. If the phone reports only 90 Hz but can do 120, pick 120 manually.",
            style = MaterialTheme.typography.bodySmall,
            color = TextDim,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 60, 90, 120, 144).forEach { hz ->
                val sel = mode == hz
                val known = hz == 0 || hz in supported
                val c = when {
                    sel -> NeonRed
                    known -> NeonCyan
                    else -> TextDim
                }
                Box(
                    Modifier
                        .clip(ChamferShape(8.dp))
                        .background(if (sel) NeonRed.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f))
                        .border(1.dp, c.copy(alpha = if (sel) 0.9f else 0.4f), ChamferShape(8.dp))
                        .clickable {
                            mode = hz
                            s.prefs.hzMode = hz
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (hz == 0) "Auto" else "$hz Hz" + if (!known) " ?" else "",
                        color = if (sel) Color.White else c,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        Text(
            "“?” = the system doesn't report this mode – it may work anyway. Applies from the next game launch. " +
                "Smart cooling may temporarily lower Hz when hot.",
            style = MaterialTheme.typography.bodySmall,
            color = TextDim,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun Hz120Help() {
    var open by remember { mutableStateOf(false) }
    NeonCard(title = "Game not running at 120 Hz?", accent = NeonPurple) {
        if (!open) {
            Text("Tap for a step-by-step guide.", color = TextDim, modifier = Modifier.clickable { open = true })
        } else {
            val steps = listOf(
                "In the game" to "Set 120 FPS in the game's graphics settings. The game must support 120 FPS on your phone – Wild Rift and other games allow it only on selected models.",
                "HyperOS" to "Settings → Display → Refresh rate → choose Custom / 120 Hz (not Default / Dynamic).",
                "Joyose" to "Must be ENABLED (Settings → Xiaomi). On Xiaomi, the list of games allowed high FPS goes through it.",
                "Game Turbo" to "In Game Turbo, check that the game has no power saving mode or lower refresh rate enabled.",
                "ZukuBoost" to "Pick 120 Hz manually above. If that doesn't help, turn on “Force display mode”.",
                "Heat and battery" to "When hot or on battery saver, HyperOS itself lowers to 60–90 Hz. Watch for the “🧊 Cooling” notification and keep the battery above 20 %.",
                "Verify" to "During a game run Settings → Diagnostics → Verify boost. The “Modes” line must contain fps=120 and mActiveMode shows the current Hz.",
            )
            steps.forEachIndexed { i, (head, body) ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(NeonPurple.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", color = NeonPurple, fontSize = 11.sp, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(head, fontWeight = FontWeight.Bold)
                        Text(body, style = MaterialTheme.typography.bodySmall, color = TextDim)
                    }
                }
            }
            Text(
                "If none of this helps, the game has 120 FPS blocked for your phone – that can't be bypassed from outside.",
                style = MaterialTheme.typography.bodySmall,
                color = NeonYellow,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
fun CleanerCard(s: UiState, ready: Boolean) {
    NeonCard(title = "Cleaner", accent = NeonGreen) {
        Text(
            "RAM: closes background apps and frees cached processes. Cache: clears the cache of all apps, " +
                "gallery thumbnails and the system log (everything is recreated automatically, no data is lost).",
            style = MaterialTheme.typography.bodySmall,
            color = TextDim,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton("🧹 RAM", {
                s.runTask("Cleaning RAM…") {
                    val am = s.ctx.getSystemService(ActivityManager::class.java)
                    fun free(): Long = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_048_576
                    val before = free()
                    val n = BoostEngine.cleanRam(s.ctx)
                    delay(800)
                    "Freed ${(free() - before).coerceAtLeast(0)} MB RAM · closed $n apps"
                }
            }, modifier = Modifier.weight(1f), enabled = ready && s.busy == null, color = NeonGreen)
            NeonButton("🗑 Cache", {
                s.runTask("Cleaning cache… (may take a moment)") {
                    val mb = BoostEngine.cleanStorage()
                    if (mb >= 1024) {
                        String.format(Locale.US, "Freed %.1f GB of storage", mb / 1024f)
                    } else {
                        "Freed $mb MB of storage"
                    }
                }
            }, modifier = Modifier.weight(1f), enabled = ready && s.busy == null, color = NeonGreen, outlined = true)
        }
    }
}


// ======================= Profil telefonu =======================

@Composable
fun DeviceCard(s: UiState, ready: Boolean) {
    val d = s.device
    NeonCard(title = "Your phone", accent = NeonCyan) {
        if (d == null) {
            Text(
                if (ready) "Detecting phone…" else "The phone is detected once Shizuku is running.",
                color = TextDim,
            )
            return@NeonCard
        }
        Text(d.model, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(10.dp))
        listOf(
            "System" to d.skin,
            "Chip" to "${d.socVendor.title} · ${d.soc}",
            "Memory" to "${d.ramGb} GB RAM",
            "Display" to "up to ${d.maxHz} Hz",
        ).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(k, color = TextDim, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Recommendations for this phone", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        DeviceDetector.recommend(d).tips.forEach { tip ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp)) { Diamond(NeonCyan, 7.dp) }
                Spacer(Modifier.width(10.dp))
                Text(tip, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCFD3DC))
            }
        }
        Spacer(Modifier.height(14.dp))
        NeonButton(
            if (s.prefs.deviceApplied) "Re-apply recommended settings" else "Apply recommended settings",
            { s.toast(s.applyDeviceRecommendation(d)) },
            color = NeonCyan,
            outlined = s.prefs.deviceApplied,
        )
    }
}
