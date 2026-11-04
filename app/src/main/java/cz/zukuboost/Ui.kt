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
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

// ---------- Barvy ----------

// Paleta „esports broadcast“: hluboká indigová tma, plazmová růžová pro akci, iontová modrá pro živá data,
// limetková pro „připraveno“. Názvy proměnných zůstávají kvůli zbytku kódu.
val NeonRed = Color(0xFFFF3D6E) // Plasma
val NeonCyan = Color(0xFF38E8FF) // Ion
val NeonYellow = Color(0xFFFFC94D) // Amber
val NeonGreen = Color(0xFFC6FF3D) // Volt
val NeonPurple = Color(0xFF8B6CFF) // Violet
val Bg = Color(0xFF07080F) // Void
val CardBg = Color(0xFF11131D) // Graphite
val CardBgHi = Color(0xFF151826)
val TextDim = Color(0xFF8C90A8) // Haze
val Disabled = Color(0xFF232634)

/** Chakra Petch – hranaté technické písmo pro nadpisy, čísla a tlačítka. Tělo textu zůstává systémové. */
val ChakraPetch = FontFamily(
    Font(R.font.chakra_medium, FontWeight.Medium),
    Font(R.font.chakra_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_bold, FontWeight.Bold),
)

// ---------- Tvary „Arena“ ----------

/** Zkosené rohy (vlevo nahoře a vpravo dole) – typický tvar herního HUD. */
class ChamferShape(private val cut: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val p = Path().apply {
            moveTo(c, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - c)
            lineTo(size.width - c, size.height)
            lineTo(0f, size.height)
            lineTo(0f, c)
            close()
        }
        return Outline.Generic(p)
    }
}

/** Rovnoběžník – šikmé tlačítko jako v herních menu. */
class SkewShape(private val skew: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val k = with(density) { skew.toPx() }
        val p = Path().apply {
            moveTo(k, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width - k, size.height)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(p)
    }
}

/** HUD rohové značky přes obsah. */
fun Modifier.hudBrackets(color: Color, len: Dp = 18.dp, inset: Dp = 10.dp, stroke: Dp = 2.dp): Modifier = drawWithContent {
    drawContent()
    val l = len.toPx()
    val o = inset.toPx()
    val s = stroke.toPx()
    val w = size.width
    val h = size.height
    drawLine(color, Offset(o, o), Offset(o + l, o), s)
    drawLine(color, Offset(o, o), Offset(o, o + l), s)
    drawLine(color, Offset(w - o, o), Offset(w - o - l, o), s)
    drawLine(color, Offset(w - o, o), Offset(w - o, o + l), s)
    drawLine(color, Offset(o, h - o), Offset(o + l, h - o), s)
    drawLine(color, Offset(o, h - o), Offset(o, h - o - l), s)
    drawLine(color, Offset(w - o, h - o), Offset(w - o - l, h - o), s)
    drawLine(color, Offset(w - o, h - o), Offset(w - o, h - o - l), s)
}

/** Kosočtverečná tečka stavu. */
@Composable
fun Diamond(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).rotate(45f).background(color))
}

/** Segmentová lišta jako HP/energie ve hře. */
@Composable
fun SegmentBar(fraction: Float, color: Color, modifier: Modifier = Modifier, segments: Int = 12) {
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(650), label = "seg")
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        val skew = size.height * 0.4f
        val on = (anim * segments).roundToInt()
        for (i in 0 until segments) {
            val x = i * (w + gap)
            val path = Path().apply {
                moveTo(x + skew, 0f)
                lineTo(x + w, 0f)
                lineTo(x + w - skew, size.height)
                lineTo(x, size.height)
                close()
            }
            drawPath(path, if (i < on) color else Color.White.copy(alpha = 0.08f))
        }
    }
}

/** Řádek HUD ukazatele: popisek – segmentová lišta – hodnota. */
@Composable
fun HudMetric(label: String, value: String, fraction: Float, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, fontSize = 13.sp, modifier = Modifier.width(86.dp))
        SegmentBar(fraction, color, Modifier.weight(1f).height(10.dp))
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(66.dp),
        )
    }
}

/** Diagonální „rychlostní“ pruhy, slábnoucí doprava (pozadí banneru). */
@Composable
fun SpeedLines(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val step = 14.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            val a = (1f - (x / size.width).coerceIn(0f, 1f)) * 0.06f
            drawLine(color.copy(alpha = a), Offset(x, size.height), Offset(x + size.height * 0.47f, 0f), 2.dp.toPx())
            x += step
        }
    }
}

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
        typography = Typography().let { t ->
            t.copy(
                headlineLarge = t.headlineLarge.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.Bold),
                headlineMedium = t.headlineMedium.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.Bold),
                titleLarge = t.titleLarge.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold),
                titleMedium = t.titleMedium.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold),
                labelLarge = t.labelLarge.copy(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold),
            )
        },
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
    Opt.FORCE_MODE -> "📺"
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

private val CardShape = ChamferShape(14.dp)

/**
 * Klidná karta: plochý grafit s vlasovou linkou. Výrazná je jen karta hry na domovské obrazovce –
 * ostatní karty nesoupeří o pozornost.
 */
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
            .background(CardBg)
            .border(1.dp, Color.White.copy(alpha = 0.06f), CardShape)
            .padding(18.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
fun SectionHeader(text: String, color: Color = NeonCyan) {
    Text(
        text,
        color = TextDim,
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(top = 14.dp, start = 4.dp),
    )
}

// ---------- Tlačítka ----------

private val PillShape = ChamferShape(9.dp)

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
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Šikmé tlačítko ▶▶ Spustit hru: plná plazma, běžící diagonální pruhy a záře pod ním. */
@Composable
fun LaunchButton(enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "launch")
    val stripe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "stripe",
    )
    val glow by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
        label = "glow",
    )
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(110), label = "press")
    val shape = SkewShape(16.dp)
    Box(Modifier.fillMaxWidth().height(62.dp).scale(scale)) {
        if (enabled) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.82f)
                    .height(18.dp)
                    .shadow(22.dp, RoundedCornerShape(50), ambientColor = NeonRed.copy(alpha = glow), spotColor = NeonRed.copy(alpha = glow)),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (enabled) NeonRed else Disabled)
                .drawBehind {
                    if (enabled) {
                        val step = 18.dp.toPx()
                        val band = 6.dp.toPx()
                        val k = size.height * 0.47f
                        var x = -size.height + stripe * step
                        while (x < size.width + size.height) {
                            val p = Path().apply {
                                moveTo(x, size.height)
                                lineTo(x + k, 0f)
                                lineTo(x + k + band, 0f)
                                lineTo(x + band, size.height)
                                close()
                            }
                            drawPath(p, Color.White.copy(alpha = 0.13f))
                            x += step
                        }
                    }
                }
                .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▶▶", color = if (enabled) Color.White else TextDim, fontSize = 17.sp, letterSpacing = (-3).sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Launch game",
                    color = if (enabled) Color.White else TextDim,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

// ---------- Ukazatele ----------

@Composable
fun StatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val shape = ChamferShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.10f), CardBg)))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 19.sp, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold)
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
            Text(value, color = Color.White, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        }
        Text(label, color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Štítek s barevnou hranou vlevo (FPS, Hz, kill list…). */
@Composable
fun ProfileChip(text: String, color: Color = NeonCyan) {
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(color))
        Text(
            text,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

// ---------- Volby ----------

@Composable
fun OptionRow(o: Opt, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = if (checked) NeonRed else TextDim
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(ChamferShape(9.dp))
                .background(if (checked) NeonRed.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                .border(1.dp, accent.copy(alpha = if (checked) 0.5f else 0.12f), ChamferShape(9.dp)),
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

/** Spodní lišta: zkosený grafit s plazmovou linkou nahoře, aktivní položka svítí podtržením. */
@Composable
fun FloatingNavBar(items: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = ChamferShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xF512141F))
                .drawBehind { drawLine(NeonRed.copy(alpha = 0.55f), Offset(12.dp.toPx(), 0f), Offset(size.width, 0f), 1.5.dp.toPx()) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, (icon, label) ->
                val isSel = i == selected
                val line by animateColorAsState(if (isSel) NeonRed else Color.Transparent, tween(200), label = "nav")
                Column(
                    Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(icon, fontSize = 18.sp)
                        if (isSel) {
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = Color.White, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .width(if (isSel) 34.dp else 0.dp)
                            .height(3.dp)
                            .shadow(if (isSel) 8.dp else 0.dp, RoundedCornerShape(2.dp), ambientColor = NeonRed, spotColor = NeonRed)
                            .background(line),
                    )
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
                .clip(ChamferShape(16.dp))
                .background(CardBg)
                .border(1.dp, NeonRed.copy(alpha = 0.4f), ChamferShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text("⚡", fontSize = 44.sp, color = NeonRed.copy(alpha = pulse))
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(color = NeonRed, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text,
                color = Color.White,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
