package cz.zukuboost

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- Barvy ----------

val NeonRed = Color(0xFFFF2D55)
val NeonCyan = Color(0xFF00E5FF)
val NeonYellow = Color(0xFFFFC400)
val NeonGreen = Color(0xFF00E676)
val Bg = Color(0xFF07080C)
val CardBg = Color(0xFF11141C)
val TextDim = Color(0xFF8A90A2)
val Disabled = Color(0xFF2A2D36)

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

// ---------- Komponenty ----------

@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = NeonRed,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBg)
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), NeonCyan.copy(alpha = 0.2f))), shape)
            .padding(16.dp),
    ) {
        if (title != null) {
            Text(
                title.uppercase(),
                color = accent,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

@Composable
fun SectionHeader(text: String, color: Color = NeonCyan) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Box(Modifier.width(4.dp).height(16.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = color, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 2.sp)
    }
}

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = NeonRed,
    outlined: Boolean = false,
) {
    val shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
    val base = modifier.clip(shape)
    val styled = when {
        !enabled -> base.background(Disabled)
        outlined -> base.border(1.dp, color, shape)
        else -> base.background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.7f))))
    }
    Box(
        styled.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = when {
                !enabled -> TextDim
                outlined -> color
                else -> Color.White
            },
            fontWeight = FontWeight.Black,
            fontSize = 13.sp,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun LaunchButton(enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "launch")
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "glow",
    )
    val shape = CutCornerShape(topStart = 22.dp, bottomEnd = 22.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(
                elevation = if (enabled) 22.dp else 0.dp,
                shape = shape,
                ambientColor = NeonRed.copy(alpha = glow),
                spotColor = NeonRed.copy(alpha = glow),
            )
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.horizontalGradient(listOf(Color(0xFFD50032), NeonRed, Color(0xFFFF6B81)))
                } else {
                    SolidColor(Disabled)
                }
            )
            .border(1.dp, Color.White.copy(alpha = if (enabled) glow * 0.6f else 0.1f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "▶  SPUSTIT HRU",
            color = if (enabled) Color.White else TextDim,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    Column(
        modifier
            .clip(shape)
            .background(CardBg)
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun ProfileChip(text: String, color: Color = NeonCyan) {
    val shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun OptionRow(o: Opt, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(o.title, fontWeight = FontWeight.Bold)
            Text(o.desc, style = MaterialTheme.typography.bodySmall, color = TextDim)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NeonRed,
                uncheckedThumbColor = TextDim,
                uncheckedTrackColor = Disabled,
            ),
        )
    }
}

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
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚡", fontSize = 56.sp, color = NeonRed.copy(alpha = pulse))
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(color = NeonRed)
            Spacer(Modifier.height(16.dp))
            Text(
                text.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}
