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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Statické herní pozadí: záře v rozích a jemná diagonální mřížka (bez animace = nežere baterii). */
@Composable
fun GamingBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Bg)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(NeonRed.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(0f, 0f),
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = Offset(0f, 0f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(NeonCyan.copy(alpha = 0.12f), Color.Transparent),
                center = Offset(size.width, size.height),
                radius = size.width,
            ),
            radius = size.width,
            center = Offset(size.width, size.height),
        )
        val step = 46.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(
                color = Color.White.copy(alpha = 0.025f),
                start = Offset(x, size.height),
                end = Offset(x + size.height, 0f),
                strokeWidth = 1f,
            )
            x += step
        }
    }
}

/** Nadpis s neonovým přechodem. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun GradientTitle(text: String, fontSize: Int) {
    Text(
        text,
        style = TextStyle(
            brush = Brush.horizontalGradient(listOf(Color.White, NeonRed, NeonCyan)),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        ),
    )
}

/** Celoobrazovková animace boostu před spuštěním hry. */
@Composable
fun LaunchOverlay(ui: LaunchUi) {
    val transition = rememberInfiniteTransition(label = "launch")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_300, easing = LinearEasing)),
        label = "angle",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulse",
    )
    val progress by animateFloatAsState(ui.progress, tween(350), label = "progress")
    val freed by animateIntAsState(ui.freedMb, tween(600), label = "freed")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2A0612), Bg), radius = 1400f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        ) {
            Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 7.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = Disabled,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke),
                    )
                    drawArc(
                        brush = Brush.sweepGradient(listOf(NeonRed, NeonCyan, NeonRed)),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    val gap = 18.dp.toPx()
                    rotate(angle) {
                        drawArc(
                            color = NeonCyan.copy(alpha = 0.85f),
                            startAngle = 0f,
                            sweepAngle = 50f,
                            useCenter = false,
                            topLeft = Offset(gap, gap),
                            size = Size(size.width - 2 * gap, size.height - 2 * gap),
                            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = NeonRed.copy(alpha = 0.85f),
                            startAngle = 180f,
                            sweepAngle = 50f,
                            useCenter = false,
                            topLeft = Offset(gap, gap),
                            size = Size(size.width - 2 * gap, size.height - 2 * gap),
                            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                val icon = ui.app?.icon
                Box(
                    Modifier
                        .size(96.dp)
                        .scale(pulse)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF1B1F2A)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(84.dp))
                    } else {
                        Text("⚡", fontSize = 44.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (ui.phase.isEmpty()) "BOOSTING  ${(progress * 100).toInt()} %" else ui.phase,
                color = if (ui.phase.isEmpty()) NeonRed else NeonCyan,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 4.sp,
            )
            Text(
                ui.label,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatChip("RAM UVOLNĚNO", "+$freed MB", NeonGreen, Modifier.weight(1f))
                StatChip("UKONČENO", "${ui.killed} appek", NeonRed, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth()) {
                ui.steps.forEach { st ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 3.dp),
                    ) {
                        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                            when (st.state) {
                                2 -> Text("✓", color = NeonGreen, fontWeight = FontWeight.Black, fontSize = 15.sp)
                                1 -> CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = NeonCyan,
                                )
                                else -> Text("•", color = Disabled, fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            st.title,
                            fontSize = 14.sp,
                            fontWeight = if (st.state == 1) FontWeight.Bold else FontWeight.Normal,
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
