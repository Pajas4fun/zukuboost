package cz.zukuboost

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Statické pozadí: dvě měkké záře v rozích, nic víc (bez animace = nežere baterii). */
@Composable
fun GamingBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Bg)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(NeonRed.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.1f, 0f),
                radius = size.width * 0.85f,
            ),
            radius = size.width * 0.85f,
            center = Offset(size.width * 0.1f, 0f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(NeonPurple.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width, size.height * 0.85f),
                radius = size.width,
            ),
            radius = size.width,
            center = Offset(size.width, size.height * 0.85f),
        )
    }
}

/** Nadpis v technickém písmu Chakra Petch (osobnost nese písmo, ne efekty). */
@Composable
fun GradientTitle(text: String, fontSize: Int) {
    Text(
        text,
        color = Color.White,
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize.sp,
        letterSpacing = 0.5.sp,
    )
}

/** Celoobrazovková animace boostu před spuštěním hry – šestiúhelník, segmentový loading bar, kroky. */
@Composable
fun LaunchOverlay(ui: LaunchUi) {
    val transition = rememberInfiniteTransition(label = "launch")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulse",
    )
    val progress by animateFloatAsState(ui.progress, tween(350), label = "progress")
    val freed by animateIntAsState(ui.freedMb, tween(600), label = "freed")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2A0C26), Bg), radius = 1400f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        ) {
            // šestiúhelník s průběhem
            Box(Modifier.size(width = 170.dp, height = 194.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 6.dp.toPx()
                    val w = size.width
                    val h = size.height
                    val i = stroke
                    val hex = Path().apply {
                        moveTo(w / 2, i)
                        lineTo(w - i, h * 0.25f)
                        lineTo(w - i, h * 0.75f)
                        lineTo(w / 2, h - i)
                        lineTo(i, h * 0.75f)
                        lineTo(i, h * 0.25f)
                        close()
                    }
                    drawPath(hex, Color.White.copy(alpha = 0.03f))
                    drawPath(hex, Disabled, style = Stroke(stroke, join = StrokeJoin.Round))
                    val pm = PathMeasure()
                    pm.setPath(hex, true)
                    val seg = Path()
                    pm.getSegment(0f, pm.length * progress, seg, true)
                    drawPath(
                        seg,
                        Brush.linearGradient(listOf(NeonRed, NeonCyan)),
                        style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                val icon = ui.app?.icon
                Box(
                    Modifier
                        .size(92.dp)
                        .scale(pulse)
                        .clip(ChamferShape(16.dp))
                        .background(Color(0xFF1B1F2A)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(82.dp))
                    } else {
                        Text("⚡", fontSize = 44.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (ui.phase.isEmpty()) "${(progress * 100).toInt()} %" else ui.phase,
                color = if (ui.phase.isEmpty()) NeonCyan else NeonGreen,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = if (ui.phase.isEmpty()) 40.sp else 24.sp,
            )
            Text(
                ui.label,
                color = Color.White,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            SegmentBar(progress, NeonCyan, Modifier.fillMaxWidth().height(10.dp), segments = 20)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("RAM freed", color = TextDim, fontSize = 12.sp)
                    Text("+$freed MB", color = NeonGreen, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Closed", color = TextDim, fontSize = 12.sp)
                    Text("${ui.killed} apps", color = NeonRed, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth()) {
                ui.steps.forEach { st ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 3.dp),
                    ) {
                        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                            when (st.state) {
                                2 -> Diamond(NeonGreen, 8.dp)
                                1 -> CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = NeonCyan,
                                )
                                else -> Diamond(Disabled, 8.dp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            st.title,
                            fontSize = 14.sp,
                            fontWeight = if (st.state == 1) FontWeight.SemiBold else FontWeight.Normal,
                            color = when (st.state) {
                                2 -> Color.White
                                1 -> NeonCyan
                                else -> TextDim
                            },
                        )
                    }
                }
            }
        }
    }
}
