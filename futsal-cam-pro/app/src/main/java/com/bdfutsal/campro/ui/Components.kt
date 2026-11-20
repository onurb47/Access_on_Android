package com.bdfutsal.campro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun Kicker(text: String, color: Color = Bd.Accent) = Text(
    text,
    color = color,
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    letterSpacing = 1.5.sp,
    fontWeight = FontWeight.Bold,
)

@Composable
fun SectionHeading(kicker: String, title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Kicker(kicker)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        }
        trailing()
    }
}

@Composable
fun BdCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier
        .background(Bd.Surface, RoundedCornerShape(16.dp))
        .border(1.dp, Bd.Outline, RoundedCornerShape(16.dp))
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    content = content,
)

@Composable
fun Notice(title: String, body: String, modifier: Modifier = Modifier) = Row(
    modifier
        .fillMaxWidth()
        .background(Bd.Warn.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
        .border(1.dp, Bd.Warn.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
        .padding(14.dp),
) {
    Text(
        buildString { append(title); append(" "); append(body) },
        style = MaterialTheme.typography.bodySmall,
        color = Bd.Text,
    )
}

@Composable
fun Pill(text: String, color: Color = Bd.Muted, dot: Boolean = true) = Row(
    Modifier
        .border(1.dp, color.copy(alpha = 0.6f), CircleShape)
        .padding(horizontal = 10.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (dot) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
    }
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun FieldLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = Bd.Muted)

/** Row of mutually exclusive options. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Bd.Outline, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { opt ->
            val isSel = opt == selected
            Button(
                onClick = { onSelect(opt) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .semantics { role = Role.RadioButton; this.selected = isSel },
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSel) Bd.Accent else Color.Transparent,
                    contentColor = if (isSel) Bd.Bg else Bd.Text,
                    disabledContainerColor = if (isSel) Bd.Accent.copy(alpha = 0.4f) else Color.Transparent,
                    disabledContentColor = if (isSel) Bd.Bg else Bd.Muted,
                ),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text(label(opt), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1) }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) =
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = Bd.Danger, contentColor = Color.White)
        else ButtonDefaults.buttonColors(),
    ) { Text(text, fontWeight = FontWeight.Bold, maxLines = 1) }

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (enabled) Bd.Outline else Bd.Outline.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Bd.Text),
    ) { Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1) }

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) = Column(
    modifier
        .background(Bd.Surface, RoundedCornerShape(12.dp))
        .border(1.dp, Bd.Outline, RoundedCornerShape(12.dp))
        .padding(12.dp),
) {
    Kicker(label, Bd.Muted)
    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", b / 1e9)
    else -> String.format(Locale.US, "%.0f MB", b / 1e6)
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}

fun formatCountdown(ms: Long): String {
    val s = (ms + 999) / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
