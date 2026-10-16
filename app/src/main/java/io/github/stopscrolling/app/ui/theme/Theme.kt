package io.github.stopscrolling.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BC4A2),
    onPrimary = Color(0xFF0E1116),
    secondary = Color(0xFF5B8DB8),
    background = Color(0xFF101418),
    surface = Color(0xFF181D23),
    onBackground = Color(0xFFE8EDF2),
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF232A32),
    onSurfaceVariant = Color(0xFF9AA6B2),
    error = Color(0xFFE57373)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D5B),
    onPrimary = Color.White,
    secondary = Color(0xFF33689B),
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    onBackground = Color(0xFF1A2129),
    onSurface = Color(0xFF1A2129),
    surfaceVariant = Color(0xFFE7ECF1),
    onSurfaceVariant = Color(0xFF5A6672),
    error = Color(0xFFC62828)
)

@Composable
fun StopScrollTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
