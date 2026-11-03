package cz.zukuboost

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- Barvy ----------

val NeonRed = Color(0xFFFF2D55)
val NeonCyan = Color(0xFF00E5FF)
val NeonYellow = Color(0xFFFFC400)
val NeonGreen = Color(0xFF00E676)
val NeonPurple = Color(0xFF9D4DFF)
val Bg = Color(0xFF06070B)
val CardBg = Color(0xFF10131B)
val CardBgHi = Color(0xFF161A25)
val TextDim = Color(0xFF8A90A2)
val Disabled = Color(0xFF262A35)

@Composable
fun ZukuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonRed,
            onPrimary = Color.White,
            secondary = NeonCyan,
            background = Bg,
            surface = Bg,
            surfaceVariant = CardBg,
            onSurfaceVariant = TextDim,
        ),
        content = content,
    )
}

/** Ikona ke každé volbě (barevná bublina v Nastavení). */
fun optIcon(o: Opt): String = when (o) {
    Opt.KILL_BG -> "🧹"
    Opt.RESTRICT_BG -> "⛔"
    Opt.SYSTEM_HOGS -> "🛑"
    Opt.SYNC_OFF -> "🔄"
    Opt.DISPLAY -> "🔆"
    Opt.LOCATION_OFF -> "📍"
    Opt.NET_BLOCK_BG -> "🌐"
    Opt.NETWORK_QUIET -> "📶"
    Opt.SCAN_OFF -> "📡"
    Opt.MILLET -> "❄️"
    Opt.PROTECT_VOICE -> "🎧"
    Opt.HIDE_NOTIF -> "🔕"
    Opt.DND -> "🌙"
    Opt.BLOCK_CALLS -> "📵"
    Opt.HEADS_UP -> "💬"
    Opt.REFRESH -> "🖥️"
    Opt.BATTERY_SAVER -> "🔋"
    Opt.GAME_MODE -> "🎮"
    Opt.XIAOMI_PERF -> "⚡"
    Opt.FIXED_PERF -> "📏"
    Opt.THERMAL -> "🌡️"
    Opt.SMART_COOL -> "🧊"
    Opt.AUTO_HZ -> "🎯"
    Opt.EDGE_BLOCK -> "🖐"
    Opt.ANIMATIONS -> "✨"
    Opt.MEASURE -> "📊"
    Opt.GAME_PANEL -> "🕹️"
    Opt.OVERLAY -> "📈"
}

// ---------- Karty a nadpisy ----------

private val CardShape = RoundedCornerShape(22.dp)

/** Skleněná karta s jemným přechodovým rámečkem v barvě akcentu. */
@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = NeonRed,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(CardBgHi, CardBg)))
            .border(
                1.dp,
                Brush.linearGradient(listOf(accent.copy(alpha = 0.7f), Color.White.copy(alpha = 0.04f), NeonCyan.copy(alpha = 0.25f))),
                CardShape,
            )
            .padding(18.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    title.uppercase(),
                    color = accent,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 1.6.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@Composable
fun SectionHeader(text: String, color: Color = NeonCyan) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) {
        Box(
            Modifier
                .width(18.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)))),
        )
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = color, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 2.4.sp)
    }
}

// ---------- Tlačítka ----------

private val PillShape = RoundedCornerShape(50)

/** Tlačítko ve tvaru pilulky – plné s přechodem, nebo obrysové. Při stisku se lehce zmenší. */
@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = NeonRed,
    outlined: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(110), label = "press")
    val base = modifier.scale(scale).clip(PillShape)
    val styled = when {
        !enabled -> base.background(Disabled)
        outlined -> base.background(color.copy(alpha = 0.08f)).border(1.dp, color.copy(alpha = 0.8f), PillShape)
        else -> base.background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.72f))))
    }
    Box(
        styled
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                !enabled -> TextDim
                outlined -> color
                else -> Color.White
            },
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Velké tlačítko spuštění hry: zářivý přechod, pulzující záře a přejíždějící odlesk. */
@Composable
fun LaunchButton(enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "launch")
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
        label = "glow",
    )
    val shine by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "shine",
    )
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(110), label = "press")
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .scale(scale)
            .shadow(
                elevation = if (enabled) 26.dp else 0.dp,
                shape = shape,
                ambientColor = NeonRed.copy(alpha = glow),
                spotColor = NeonRed.copy(alpha = glow),
            )
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.horizontalGradient(listOf(Color(0xFFE0003C), NeonRed, Color(0xFFFF5E7E)))
                } else {
                    SolidColor(Disabled)
                }
            )
            .drawBehind {
                if (enabled) {
                    // přejíždějící odlesk
                    val x = size.width * shine
                    drawRect(
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                            start = Offset(x - size.width * 0.25f, 0f),
                            end = Offset(x + size.width * 0.25f, size.height),
                        ),
                    )
                }
            }
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▶", color = if (enabled) Color.White else TextDim, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                "SPUSTIT HRU",
                color = if (enabled) Color.White else TextDim,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 2.5.sp,
            )
        }
    }
}

// ---------- Ukazatele ----------

@Composable
fun StatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.10f), CardBg)))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = TextDim, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

/** Kruhový ukazatel (teplota, RAM…) s animovaným obloukem. fraction 0..1 */
@Composable
fun StatGauge(label: String, value: String, fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "gauge")
    val animColor by animateColorAsState(color, tween(500), label = "gaugeColor")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = Disabled,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(animColor.copy(alpha = 0.6f), animColor, animColor.copy(alpha = 0.6f))),
                    startAngle = 135f,
                    sweepAngle = 270f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Text(value, color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        Text(label, color = TextDim, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ProfileChip(text: String, color: Color = NeonCyan) {
    Box(
        Modifier
            .clip(PillShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Volby ----------

@Composable
fun OptionRow(o: Opt, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = if (checked) NeonRed else TextDim
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) NeonRed.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                .border(1.dp, accent.copy(alpha = if (checked) 0.5f else 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(optIcon(o), fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(o.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(o.desc, style = MaterialTheme.typography.bodySmall, color = TextDim, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NeonRed,
                checkedBorderColor = NeonRed,
                uncheckedThumbColor = TextDim,
                uncheckedTrackColor = Disabled,
                uncheckedBorderColor = Disabled,
            ),
        )
    }
}

// ---------- Navigace ----------

/** Plovoucí spodní lišta: skleněná pilulka, vybraná položka svítí s popiskem. */
@Composable
fun FloatingNavBar(items: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, PillShape, ambientColor = NeonRed.copy(alpha = 0.4f), spotColor = NeonRed.copy(alpha = 0.4f))
                .clip(PillShape)
                .background(Brush.verticalGradient(listOf(Color(0xF2181C28), Color(0xF20C0E14))))
                .border(1.dp, Brush.horizontalGradient(listOf(NeonRed.copy(alpha = 0.5f), NeonCyan.copy(alpha = 0.4f))), PillShape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, (icon, label) ->
                val isSel = i == selected
                val bg by animateColorAsState(if (isSel) NeonRed.copy(alpha = 0.22f) else Color.Transparent, tween(220), label = "nav")
                Row(
                    Modifier
                        .clip(PillShape)
                        .background(bg)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                        .padding(horizontal = if (isSel) 14.dp else 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(icon, fontSize = 18.sp)
                    if (isSel) {
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------- Načítání ----------

@Composable
fun BusyOverlay(text: String) {
    val transition = rememberInfiniteTransition(label = "busy")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CardBg)
                .border(1.dp, NeonRed.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text("⚡", fontSize = 44.sp, color = NeonRed.copy(alpha = pulse))
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(color = NeonRed, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
