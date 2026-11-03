package cz.zukuboost

import android.app.ActivityManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
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

    LaunchedEffect(Unit) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        while (true) {
            batTemp = batteryTemp(ctx)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            freeRam = mi.availMem / 1_073_741_824f
            totalRam = (mi.totalMem / 1_073_741_824f).coerceAtLeast(1f)
            delay(2_000)
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- hlavička ---
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GradientTitle("ZUKUBOOST", 34)
                    Text("GAME SPACE", color = TextDim, fontSize = 11.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
                }
                val c = if (ready) NeonGreen else NeonRed
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(c.copy(alpha = 0.12f))
                        .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .clickable { onOpenSettings() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(6.dp))
                    Text(if (ready) "ONLINE" else "OFFLINE", color = c, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }

        // --- kruhové ukazatele ---
        item {
            NeonCard(accent = NeonCyan) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatGauge(
                        "BATERIE",
                        String.format(Locale.US, "%.0f°", batTemp),
                        (batTemp - 20f) / 30f,
                        when {
                            batTemp >= 44f -> NeonRed
                            batTemp >= 40f -> NeonYellow
                            else -> NeonGreen
                        },
                    )
                    StatGauge(
                        "VOLNÁ RAM",
                        String.format(Locale.US, "%.1f", freeRam),
                        freeRam / totalRam,
                        NeonCyan,
                    )
                    StatGauge("DISPLEJ", "${s.maxHz}", s.maxHz / 144f, NeonPurple)
                }
            }
        }

        // --- vybraná hra ---
        item {
            if (sel == null) {
                NeonCard(title = "Žádné hry") {
                    Text("Přidej hry a ZukuBoost je bude spouštět s boostem.", color = TextDim)
                    Spacer(Modifier.height(14.dp))
                    NeonButton("+ Přidat hry", onOpenGames)
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(CardBg)
                        .border(
                            1.dp,
                            Brush.linearGradient(listOf(NeonRed.copy(alpha = 0.8f), Color.Transparent, NeonCyan.copy(alpha = 0.5f))),
                            RoundedCornerShape(26.dp),
                        ),
                ) {
                    // rozmazaná ikona hry jako pozadí karty
                    sel.icon?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().blur(40.dp).alpha(0.35f),
                        )
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, CardBg.copy(alpha = 0.92f), CardBg))),
                    )
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .shadow(20.dp, RoundedCornerShape(22.dp), ambientColor = NeonRed, spotColor = NeonRed)
                                    .border(2.dp, Brush.linearGradient(listOf(NeonRed, NeonCyan)), RoundedCornerShape(22.dp)),
                            ) {
                                GameIcon(sel, 88)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("PŘIPRAVENO", color = NeonGreen, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Black)
                                Text(
                                    sel.label,
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(8.dp))
                                val cap = s.caps[sel.pkg] ?: s.prefs.fpsCap(sel.pkg)
                                val scale = s.scales[sel.pkg] ?: s.prefs.scale(sel.pkg)
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ProfileChip(if (cap == 0) "FPS MAX" else "$cap FPS")
                                    ProfileChip("${(scale * 100).roundToInt()} %", NeonPurple)
                                    ProfileChip("🧹 ${s.killList.size}", NeonRed)
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        LaunchButton(enabled = s.busy == null && s.launch == null) { s.launchGame(sel.pkg) }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenSettings() },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val c = if (s.monitorOn) NeonGreen else TextDim
                            Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (s.monitorOn) "Auto boost hlídá hry" else "Auto boost se zapne při spuštění",
                                color = c,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // --- knihovna her ---
        if (gameItems.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("Knihovna her")
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(gameItems, key = { it.pkg }) { g ->
                            GameTile(g, g.pkg == s.selected) {
                                s.selected = g.pkg
                                s.prefs.lastGame = g.pkg
                            }
                        }
                        item { AddTile(onOpenGames) }
                    }
                }
            }
        }

        if (lastSession.isNotBlank()) {
            item {
                NeonCard(title = "Poslední relace", accent = NeonPurple) {
                    Text(lastSession, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!ready) {
            item {
                NeonCard(title = "Shizuku neběží", accent = NeonYellow) {
                    Text("Bez Shizuku se hry spustí bez boostu.", color = TextDim)
                    Spacer(Modifier.height(12.dp))
                    NeonButton("Otevřít nastavení", onOpenSettings, color = NeonYellow, outlined = true)
                }
            }
        }
    }
}

@Composable
fun GameIcon(app: AppItem, sizeDp: Int) {
    val shape = RoundedCornerShape((sizeDp / 4).dp)
    val icon = app.icon
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1E2230), Color(0xFF12151D)))),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size((sizeDp - 6).dp).clip(shape))
        } else {
            Text("🎮", fontSize = (sizeDp / 2).sp)
        }
    }
}

@Composable
fun GameTile(app: AppItem, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val tileScale by animateFloatAsState(if (selected) 1.06f else 1f, tween(200), label = "tile")
    Column(
        Modifier
            .width(82.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .scale(tileScale)
                .then(
                    if (selected) {
                        Modifier
                            .shadow(14.dp, shape, ambientColor = NeonRed, spotColor = NeonRed)
                            .border(2.dp, Brush.linearGradient(listOf(NeonRed, NeonCyan)), shape)
                    } else {
                        Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                    }
                ),
        ) {
            GameIcon(app, 72)
        }
        Spacer(Modifier.height(8.dp))
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
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.width(82.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(72.dp)
                .clip(shape)
                .background(NeonCyan.copy(alpha = 0.06f))
                .border(1.dp, NeonCyan.copy(alpha = 0.5f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = NeonCyan, fontSize = 30.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(8.dp))
        Text("Přidat", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
    }
}

// ======================= HRY =======================

@Composable
fun GamesScreen(s: UiState, shizuku: Shell.State) {
    val ready = shizuku == Shell.State.READY
    AppList(
        title = "Hry",
        apps = s.apps,
        selected = s.games,
        exclude = emptySet(),
        hint = "Zaškrtni hry. 🎮 = appka se sama hlásí jako hra. U vybraných her nastavíš profil.",
        onToggle = { pkg, on -> s.updateGames(if (on) s.games + pkg else s.games - pkg) },
        header = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeonButton(
                    "+ Všechny 🎮",
                    onClick = {
                        val found = s.apps.orEmpty().filter { it.isGame }.map { it.pkg }.toSet()
                        s.updateGames(s.games + found)
                        s.toast("Přidáno ${found.size} her")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = s.apps != null,
                    color = NeonCyan,
                    outlined = true,
                )
                NeonButton(
                    "Kompilovat vše",
                    onClick = {
                        val list = s.games.toList()
                        s.runTask("Kompiluji hry…") {
                            var ok = 0
                            list.forEachIndexed { i, pkg ->
                                s.busy = "Kompiluji ${i + 1}/${list.size}: ${s.labelOf(pkg)}"
                                if (BoostEngine.compileSpeed(pkg).contains("Success")) ok++
                            }
                            "Zkompilováno $ok/${list.size} her"
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
                    SmallAction("Kompilovat", ready && s.busy == null) {
                        s.runTask("Kompiluji ${app.label}… (může trvat i minutu)") { BoostEngine.compileSpeed(app.pkg) }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        val curScale = s.scales[app.pkg] ?: s.prefs.scale(app.pkg)
                        val curCap = s.caps[app.pkg] ?: s.prefs.fpsCap(app.pkg)
                        SmallAction(if (curCap == 0) "FPS: max" else "FPS: $curCap", ready && s.busy == null) {
                            val next = nextCap(curCap, s.capOptions)
                            s.caps[app.pkg] = next
                            s.prefs.setFpsCap(app.pkg, next)
                            s.runTask("Nastavuji FPS limit…") {
                                val out = BoostEngine.setGameConfig(s.ctx, app.pkg, curScale, next)
                                val name = if (next == 0) "vypnutý" else "$next FPS"
                                "FPS limit $name: ${out.ifBlank { "OK" }}"
                            }
                        }
                        SmallAction("Rozlišení ${(curScale * 100).roundToInt()} %", ready && s.busy == null) {
                            val next = nextScale(curScale)
                            s.scales[app.pkg] = next
                            s.prefs.setScale(app.pkg, next)
                            s.runTask("Nastavuji rozlišení…") {
                                val out = BoostEngine.setGameConfig(s.ctx, app.pkg, next, curCap)
                                "Rozlišení ${(next * 100).roundToInt()} %: ${out.ifBlank { "OK" }}"
                            }
                        }
                    }
                    if (s.driverSupported) {
                        val gameDriver = app.pkg in s.driverApps
                        SmallAction(if (gameDriver) "GPU: herní" else "GPU: výchozí", ready && s.busy == null) {
                            val on = !gameDriver
                            s.driverApps = if (on) s.driverApps + app.pkg else s.driverApps - app.pkg
                            s.runTask("Nastavuji GPU ovladač…") { BoostEngine.setGameDriver(app.pkg, on) }
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
    AppList(
        title = "Kill list",
        apps = s.apps,
        selected = s.killList,
        exclude = s.games,
        hint = "Appky, které se při spuštění hry ukončí a nesmí běžet na pozadí (Facebook, Shopee, TikTok…). " +
            "Nedávej sem Discord, pokud s ním hraješ.",
        onToggle = { pkg, on -> s.updateKill(if (on) s.killList + pkg else s.killList - pkg) },
    )
}

// ======================= HISTORIE =======================

@Composable
fun HistoryScreen(s: UiState, tick: Int) {
    val history = remember(tick, s.recheck, s.historyTick) { s.prefs.history() }
    val fmt = remember { SimpleDateFormat("d. M. HH:mm", Locale.forLanguageTag("cs")) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Historie") }
        item {
            Column {
                Text(
                    "Každá hra delší než minuta se tu zapíše. Zahraj stejnou hru s Game Turbem a se ZukuBoostem " +
                        "a porovnej ø FPS, minimum a propady (⚠ = FPS kleslo pod 75 % průměru).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    NeonButton("Smazat historii", {
                        s.prefs.clearHistory()
                        s.historyTick++
                    }, outlined = true)
                }
            }
        }
        if (history.isEmpty()) {
            item { Text("Zatím žádné záznamy.", color = TextDim, modifier = Modifier.padding(top = 16.dp)) }
        }
        items(history, key = { "${it.pkg}_${it.start}" }) { h ->
            NeonCard(title = h.label, accent = NeonCyan) {
                Text("${fmt.format(Date(h.start))} · ${h.minutes} min", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("Ø FPS", h.avg?.toString() ?: "--", NeonCyan, Modifier.weight(1f))
                    StatChip("MIN", h.min?.toString() ?: "--", NeonYellow, Modifier.weight(1f))
                    StatChip("PROPADY", h.drops.toString(), if (h.drops > 5) NeonRed else NeonGreen, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("CPU max ").append(h.maxCpu?.let { "${it.roundToInt()} °C" } ?: "--")
                        append("   ·   baterie max ").append(h.maxBat?.let { String.format(Locale.US, "%.1f °C", it) } ?: "--")
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
        item { ScreenTitle("Nastavení") }

        // ---------- Připojení ----------
        item { SectionHeader("Připojení") }
        item {
            NeonCard(title = "Shizuku", accent = if (ready) NeonGreen else NeonRed) {
                Text(
                    when (shizuku) {
                        Shell.State.NOT_RUNNING -> "❌ Shizuku neběží. Spusť ho v aplikaci Shizuku Next."
                        Shell.State.NO_PERMISSION -> "⚠️ Shizuku běží, ale appka nemá oprávnění."
                        Shell.State.READY -> "✅ Připojeno"
                    }
                )
                when (shizuku) {
                    Shell.State.NO_PERMISSION -> {
                        Spacer(Modifier.height(10.dp))
                        NeonButton("Povolit přístup", onRequestShizuku)
                    }
                    Shell.State.NOT_RUNNING -> {
                        Spacer(Modifier.height(10.dp))
                        NeonButton("Otevřít Shizuku", { if (!openShizuku(ctx)) s.toast("Shizuku není nainstalované") })
                    }
                    Shell.State.READY -> Unit
                }
            }
        }
        item {
            NeonCard(title = "Oprávnění") {
                Text(if (usageOk) "✅ Detekce her (přístup k využití)" else "❌ Detekce her (přístup k využití)")
                Text(if (overlayOk) "✅ Overlay přes hru" else "❌ Overlay přes hru")
                when (s.writeOk) {
                    1 -> Text("✅ Zápis systémových nastavení")
                    0 -> {
                        Text("❌ Zápis systémových nastavení je blokovaný")
                        Text(
                            "HyperOS ho povolí až po zapnutí: Nastavení → Další nastavení → Možnosti pro vývojáře → " +
                                "Ladění USB (Bezpečnostní nastavení). Vyžaduje přihlášený Xiaomi účet a SIM kartu. " +
                                "Bez toho nefunguje Hz, Nerušit, spořič ani Xiaomi výkonový profil.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonYellow,
                        )
                    }
                    else -> Unit
                }
                Spacer(Modifier.height(10.dp))
                NeonButton("Nastavit vše přes Shizuku", {
                    s.runTask("Nastavuji oprávnění…") {
                        val result = BoostEngine.grantSelf(ctx)
                        delay(500) // systém chvíli propisuje změny
                        result
                    }
                }, enabled = ready && s.busy == null)
                if (!usageOk || !overlayOk) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Pořád ❌? Povol to ručně – najdi v seznamu ZukuBoost a zapni:",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonYellow,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!usageOk) {
                            NeonButton(
                                "Přístup k využití",
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
                    "Na HyperOS ještě v nastavení appky povol Autostart a baterii nastav na Bez omezení.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ---------- Automatika ----------
        item { SectionHeader("Automatika") }
        item {
            NeonCard(title = "Automatický boost") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !s.monitorOn -> "Vypnuto"
                            paused -> "Pozastaveno (dlaždice)"
                            else -> "Hlídám spuštění her"
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
                                    s.games.isEmpty() -> s.toast("Nejdřív vyber hry v záložce Hry")
                                    !s.startMonitor() -> s.toast("Chybí přístup k využití – klikni na Nastavit vše")
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
                    }) { Text("Pokračovat", color = NeonCyan) }
                }
                Text(
                    "Tip: přidej si dlaždici ZukuBoost do rychlého nastavení – jedním klepnutím boost pozastavíš.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ---------- Při hraní ----------
        item { SectionHeader("Při hraní") }
        item {
            NeonCard {
                Text(
                    "Proti propadům FPS nejvíc pomáhá FPS limit na hodnotu, kterou hra udrží i zahřátá (záložka Hry), " +
                        "vypnutý Joyose a stabilní výkon. Hlavní příčina propadů je teplo.",
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
                if (s.opts[Opt.SMART_COOL] == true) {
                    var coolStart by remember { mutableIntStateOf(s.prefs.coolStartC) }
                    TextButton(onClick = {
                        val next = if (coolStart >= 47) 41 else coolStart + 1
                        coolStart = next
                        s.prefs.coolStartC = next
                    }) {
                        Text(
                            "Chlazení začne při: $coolStart °C (2. stupeň ${coolStart + 3} °C, návrat pod ${coolStart - 3} °C)",
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
                        Text("Šířka blokovaných okrajů: $width dp (platí od další hry)", color = NeonCyan, fontSize = 13.sp)
                    }
                }
            }
        }

        // ---------- Xiaomi ----------
        val joyoseOn = joyose
        if (joyoseOn != null) {
            item { SectionHeader("Xiaomi", NeonYellow) }
            item {
                NeonCard(title = "Výkonový režim", accent = NeonYellow) {
                    Text(
                        "Skrytý výběr výkonového režimu Xiaomi (Úspora / Vyvážený / Výkon). Nastavení vydrží i po hře.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    NeonButton("Otevřít výkonový režim", {
                        s.runTask("Otevírám…") { BoostEngine.openXiaomiPowerMenu() }
                    }, enabled = ready && s.busy == null, color = NeonYellow, outlined = true)
                }
            }
            item {
                NeonCard(title = "GPU nastavení v Game Turbu", accent = NeonYellow) {
                    Text(
                        "Xiaomi na mnoha telefonech skrylo v Game Turbu volbu výkonu GPU. Tímhle ji zkusíš odemknout. " +
                            "Na některých modelech nefunguje – pak se nic nestane.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(if (s.gpuTuner) "Stav: odemčeno ✅" else "Stav: skryto", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    NeonButton(if (s.gpuTuner) "Skrýt zpět" else "Odemknout GPU nastavení", {
                        val on = !s.gpuTuner
                        s.gpuTuner = on
                        s.runTask("Nastavuji…") { BoostEngine.setGpuTuner(ctx, on) }
                    }, enabled = ready && s.busy == null, color = NeonYellow, outlined = s.gpuTuner)
                }
            }
            item {
                NeonCard(title = "Joyose", accent = NeonYellow) {
                    Text(
                        "Systémová služba Xiaomi, která podle cloudových profilů omezuje výkon a FPS ve hrách. " +
                            "Vypnutí může zvednout výkon, ale i teploty. Pozor: na některých modelech povoluje i režim " +
                            "vysokých FPS – když po vypnutí hra spadne na 60 FPS, zapni ho zpět.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (joyoseOn) "Stav: zapnutý (omezuje)" else "Stav: vypnutý ✅",
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    NeonButton(if (joyoseOn) "Vypnout Joyose" else "Zapnout Joyose zpět", {
                        s.runTask(if (joyoseOn) "Vypínám Joyose…" else "Zapínám Joyose…") {
                            BoostEngine.setJoyose(!joyoseOn)
                        }
                    }, enabled = ready && s.busy == null, color = NeonYellow)
                }
            }
        }

        // ---------- Systém ----------
        item { SectionHeader("Systém") }
        item {
            val list = s.obstacles
            NeonCard(title = "Herní překážky", accent = if (list.isNullOrEmpty()) NeonGreen else NeonYellow) {
                Text(
                    "Nastavení z vývojářských možností, která hraní zpomalují (často zapnutá omylem nebo podle „gaming tipů“).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    list == null -> Text("Zjistí se, až poběží Shizuku.", color = TextDim)
                    list.isEmpty() -> Text("✅ Nic nebrzdí", fontWeight = FontWeight.Bold, color = NeonGreen)
                    else -> {
                        list.forEach { o ->
                            Text("⚠️ ${o.title}", fontWeight = FontWeight.Bold)
                            Text(o.why, style = MaterialTheme.typography.bodySmall, color = TextDim)
                            Spacer(Modifier.height(6.dp))
                        }
                        NeonButton("Opravit vše", {
                            s.runTask("Opravuji…") {
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
            NeonCard(title = "Zmrazování appek na pozadí", accent = NeonCyan) {
                Text(
                    "Appky v paměti se 10 s po odchodu na pozadí zmrazí a neberou hře procesor. " +
                        "HyperOS má vlastní zmrazování (Millet) a to Androidí po restartu často vypne – " +
                        "ZukuBoost ho pak sám znovu zapne, jakmile běží Shizuku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(8.dp))
                val info = s.freezer
                val wanted = s.prefs.freezerWanted
                Text(
                    "Nastavení: " + when (info?.setting) {
                        "enabled" -> "zapnuto"
                        "disabled" -> "vypnuto systémem"
                        null -> "neznámé (Shizuku neběží)"
                        else -> "výchozí systému"
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Skutečný stav: " + when (info?.active) {
                        true -> "běží ✅"
                        false -> "neběží"
                        null -> "nejde zjistit"
                    },
                    fontWeight = FontWeight.Bold,
                    color = if (info?.active == true) NeonGreen else TextDim,
                )
                if (wanted && info?.active == false) {
                    Text(
                        "Pokud „neběží“ zůstane i po restartu a otevření ZukuBoostu, tvůj HyperOS Androidí zmrazování " +
                            "nepovoluje. Nevadí – zmrazování pak dělá jeho vlastní Millet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonYellow,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                NeonButton(if (wanted) "Vypnout" else "Zapnout zmrazování", {
                    s.runTask("Nastavuji…") {
                        val msg = BoostEngine.setFreezer(ctx, !wanted)
                        s.freezer = BoostEngine.freezerState()
                        msg
                    }
                }, enabled = ready && s.busy == null, color = NeonCyan, outlined = wanted)
            }
        }
        item {
            NeonCard(title = "Diagnostika boostu", accent = NeonCyan) {
                Text(
                    "Systém nikdy nehlásí, že nějaké nastavení ignoruje. Tohle přečte skutečný stav (Hz, Game Mode, " +
                        "teploty, výkonový režim). Nejlépe spusť během hry přes dlaždici nebo hned po ní.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(10.dp))
                NeonButton("Ověřit boost", { s.runDiagnostics() }, enabled = ready && s.busy == null, color = NeonCyan)
                s.diagText?.let { text ->
                    Spacer(Modifier.height(10.dp))
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFCFD3DC))
                }
            }
        }

        // ---------- Obnova ----------
        item { SectionHeader("Obnova") }
        item {
            NeonCard {
                Text(
                    "Kdyby něco zůstalo zapnuté po hře (Nerušit, Hz…), tímhle vše vrátíš do původního stavu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(10.dp))
                NeonButton("Vrátit původní nastavení", {
                    s.runTask("Vracím nastavení…") {
                        BoostEngine.restore(ctx)
                        "Původní nastavení vráceno"
                    }
                }, enabled = ready && s.busy == null, outlined = true)
                Text(
                    "Tip: pro vybrané hry vypni HyperOS Game Turbo, ať se se ZukuBoostem nepere o nastavení.",
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
        GradientTitle(text.uppercase(), 26)
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
            label = { Text("Hledat") },
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
