package com.bdfutsal.campro.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Bd {
    val Bg = Color(0xFF09111D)
    val Surface = Color(0xFF0F1A2B)
    val SurfaceHigh = Color(0xFF16233A)
    val Outline = Color(0xFF24344F)
    val Accent = Color(0xFFC6F432)
    val Danger = Color(0xFFFF4D5E)
    val Warn = Color(0xFFFFB547)
    val Text = Color(0xFFE8EEF7)
    val Muted = Color(0xFF8A9BB4)
}

private val scheme = darkColorScheme(
    primary = Bd.Accent,
    onPrimary = Bd.Bg,
    secondary = Bd.Accent,
    onSecondary = Bd.Bg,
    background = Bd.Bg,
    onBackground = Bd.Text,
    surface = Bd.Surface,
    onSurface = Bd.Text,
    surfaceVariant = Bd.SurfaceHigh,
    onSurfaceVariant = Bd.Muted,
    surfaceContainer = Bd.Surface,
    surfaceContainerHigh = Bd.SurfaceHigh,
    surfaceContainerHighest = Bd.SurfaceHigh,
    outline = Bd.Outline,
    outlineVariant = Bd.Outline,
    error = Bd.Danger,
    onError = Color.White,
)

@Composable
fun BdTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
