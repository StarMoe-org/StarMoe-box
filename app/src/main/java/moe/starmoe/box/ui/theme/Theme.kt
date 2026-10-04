package moe.starmoe.box.ui.theme

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

// StarMoe pink as the seed when the system offers no dynamic color (Android < 12).
private val Light = lightColorScheme(
    primary = Color(0xFFB4235D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E1),
    onPrimaryContainer = Color(0xFF3F001A),
    secondary = Color(0xFF74565E),
    secondaryContainer = Color(0xFFFFD9E1),
    onSecondaryContainer = Color(0xFF2B151B),
    tertiary = Color(0xFF7C5635),
    tertiaryContainer = Color(0xFFFFDCC1),
    background = Color(0xFFFFF8F8),
    surface = Color(0xFFFFF8F8),
    surfaceContainer = Color(0xFFF9EBED),
    surfaceContainerHigh = Color(0xFFF3E5E7),
    errorContainer = Color(0xFFFFDAD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB1C4),
    onPrimary = Color(0xFF65002E),
    primaryContainer = Color(0xFF8F0745),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFE3BDC6),
    secondaryContainer = Color(0xFF5A3F46),
    onSecondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFFEFBD94),
    tertiaryContainer = Color(0xFF613F20),
    background = Color(0xFF191113),
    surface = Color(0xFF191113),
    surfaceContainer = Color(0xFF261D1F),
    surfaceContainerHigh = Color(0xFF312729),
)

@Composable
fun StarMoeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
