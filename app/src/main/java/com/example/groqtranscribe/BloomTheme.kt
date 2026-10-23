package com.example.groqtranscribe

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.LineBreak

private val BloomLight = lightColorScheme(
    primary = Color(0xFF925379), onPrimary = Color.White,
    primaryContainer = Color(0xFFF6EDF3), onPrimaryContainer = Color(0xFF3E353A),
    secondary = Color(0xFF866048), secondaryContainer = Color(0xFFF8F0E7),
    onSecondaryContainer = Color(0xFF3E353A),
    background = Color(0xFFFFFCF9), onBackground = Color(0xFF3E353A),
    surface = Color(0xFFFFFCF9), onSurface = Color(0xFF3E353A),
    surfaceVariant = Color(0xFFFBF3EF), onSurfaceVariant = Color(0xFF76676F),
    outline = Color(0xFF8D7C86), outlineVariant = Color(0xFFEEE4E6)
)
private val BloomDark = darkColorScheme(
    primary = Color(0xFFE8AFD1), onPrimary = Color(0xFF49243B),
    primaryContainer = Color(0xFF493240), onPrimaryContainer = Color(0xFFF4DCEB),
    secondary = Color(0xFFE3BDA2), secondaryContainer = Color(0xFF483A31),
    onSecondaryContainer = Color(0xFFF4DFCF),
    background = Color(0xFF201A1E), onBackground = Color(0xFFEFE4EA),
    surface = Color(0xFF201A1E), onSurface = Color(0xFFEFE4EA),
    surfaceVariant = Color(0xFF3A3037), onSurfaceVariant = Color(0xFFD1BFC9),
    outline = Color(0xFFA18E99), outlineVariant = Color(0xFF51434D)
)

@Composable
fun BloomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) BloomDark else BloomLight,
        typography = Typography(
            headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 26.sp, lineBreak = LineBreak.Paragraph),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
        )) { CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp, content = content) }
}
