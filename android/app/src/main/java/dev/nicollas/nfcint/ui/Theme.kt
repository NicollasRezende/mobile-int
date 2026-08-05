package dev.nicollas.nfcint.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Escuro = darkColorScheme(
    primary = Color(0xFF4DABF7),
    onPrimary = Color(0xFF06121F),
    secondary = Color(0xFF3FB950),
    background = Color(0xFF0E1116),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF1C232C),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    error = Color(0xFFF85149),
)

private val Claro = lightColorScheme(
    primary = Color(0xFF1971C2),
    secondary = Color(0xFF2F9E44),
)

@Composable
fun NfcIntTheme(escuro: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (escuro) Escuro else Claro,
        typography = Typography(),
        content = content,
    )
}
