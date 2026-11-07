package cz.zukuboost

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================= Zkratky na ikoně appky =======================

object Shortcuts {
    const val EXTRA_LAUNCH = "launch_pkg"

    /** Podržíš ikonu ZukuBoostu → rovnou spustíš hru s boostem (max 4 hry). */
    fun update(ctx: Context, games: Set<String>) {
        runCatching {
            val pm = ctx.packageManager
            val list = games.take(4).mapNotNull { pkg ->
                runCatching {
                    val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    val icon = pm.getApplicationIcon(pkg).toBitmap(192, 192)
                    ShortcutInfoCompat.Builder(ctx, "game_$pkg")
                        .setShortLabel(label)
                        .setLongLabel("Launch $label boosted")
                        .setIcon(IconCompat.createWithBitmap(icon))
                        .setIntent(
                            Intent(ctx, MainActivity::class.java)
                                .setAction(Intent.ACTION_VIEW)
                                .putExtra(EXTRA_LAUNCH, pkg)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        .build()
                }.getOrNull()
            }
            ShortcutManagerCompat.setDynamicShortcuts(ctx, list)
        }
    }
}

// ======================= Hlášení pádu =======================

fun readCrash(ctx: Context): String? = runCatching {
    val f = File(ctx.filesDir, App.CRASH_FILE)
    if (f.exists()) f.readText() else null
}.getOrNull()

fun clearCrash(ctx: Context) {
    runCatching { File(ctx.filesDir, App.CRASH_FILE).delete() }
}

@Composable
fun CrashDialog(report: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        NeonCard(Modifier.padding(20.dp), title = "ZukuBoost crashed last time", accent = NeonYellow) {
            Text(
                "Copy the report and send it to the developer – it says exactly what went wrong.",
                color = TextDim,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(Color(0xFF0A0B12))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(report, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFCFD3DC))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("Copy report", { clipboard.setText(AnnotatedString(report)) }, Modifier.weight(1f), color = NeonYellow)
                NeonButton("Close", onClose, Modifier.weight(1f), color = NeonYellow, outlined = true)
            }
        }
    }
}

// ======================= Log příkazů =======================

@Composable
fun CommandLogScreen(onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version) { CmdLog.all() }
    var onlyWarnings by remember { mutableIntStateOf(0) }
    val shown = if (onlyWarnings == 1) entries.filter { it.warning } else entries
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GradientTitle("Command log", 26)
                Text(
                    "${entries.size} commands · ${entries.count { it.warning }} refused by the system",
                    color = TextDim,
                    fontSize = 12.sp,
                )
            }
            NeonButton("Close", onClose, color = NeonCyan, outlined = true)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton(if (onlyWarnings == 1) "Show all" else "Only ⚠", { onlyWarnings = 1 - onlyWarnings }, Modifier.weight(1f), color = NeonYellow, outlined = true)
            NeonButton("Copy", { clipboard.setText(AnnotatedString(CmdLog.text())) }, Modifier.weight(1f), color = NeonCyan)
            NeonButton("Clear", {
                CmdLog.clear()
                version++
            }, Modifier.weight(1f), color = NeonRed, outlined = true)
        }
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Text("Nothing yet – run a game or any action.", color = TextDim, modifier = Modifier.padding(top = 20.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown) { e ->
                val c = if (e.warning) NeonYellow else NeonGreen
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(CardBg)
                        .border(1.dp, c.copy(alpha = 0.25f))
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Diamond(c, 7.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(fmt.format(Date(e.time)), color = TextDim, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("${e.ms} ms", color = TextDim, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(e.cmd, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Color.White, maxLines = 6)
                    if (e.error != null) {
                        Text(e.error, fontSize = 11.sp, color = NeonRed, fontWeight = FontWeight.SemiBold)
                    }
                    if (e.out.isNotBlank()) {
                        Text(e.out, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (e.warning) NeonYellow else TextDim, maxLines = 8)
                    }
                }
            }
        }
    }
}
