package net.asksakis.massdroidv2.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun MassDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val base = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (!darkTheme) {
                // Strengthen light theme: slightly warmer surfaces, more contrast
                base.copy(
                    background = Color(0xFFDDDDDD),
                    surface = Color(0xFFDDDDDD),
                    surfaceVariant = Color(0xFFCCCCCC),
                    surfaceContainerHigh = Color(0xFFC8C8C8),
                    surfaceContainer = Color(0xFFD5D5D5),
                    surfaceContainerLow = Color(0xFFDADADA),
                    onBackground = Color(0xFF111111),
                    onSurface = Color(0xFF111111),
                    onSurfaceVariant = Color(0xFF333333)
                )
            } else base
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
