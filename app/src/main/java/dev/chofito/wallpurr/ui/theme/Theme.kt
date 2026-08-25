package dev.chofito.wallpurr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = GingerDark,
    onPrimary = GingerInkDark,
    primaryContainer = GingerSoftDark,
    onPrimaryContainer = GingerDark,
    secondary = NightBlueDark,
    onSecondary = FurDark,
    secondaryContainer = NightBlueSoftDark,
    onSecondaryContainer = NightBlueDark,
    background = FurDark,
    onBackground = FurTextDark,
    surface = FurRaisedDark,
    onSurface = FurTextDark,
    surfaceVariant = FurHighDark,
    onSurfaceVariant = FurMutedDark,
    outline = FurLineDark,
    outlineVariant = FurLineDark,
)

private val LightColorScheme = lightColorScheme(
    primary = GingerLight,
    onPrimary = GingerInkLight,
    primaryContainer = GingerSoftLight,
    onPrimaryContainer = GingerInkDark,
    secondary = NightBlueLight,
    onSecondary = FurLight,
    secondaryContainer = NightBlueSoftLight,
    onSecondaryContainer = NightBlueLight,
    background = FurLight,
    onBackground = FurTextLight,
    surface = FurRaisedLight,
    onSurface = FurTextLight,
    surfaceVariant = FurHighLight,
    onSurfaceVariant = FurMutedLight,
    outline = FurLineLight,
    outlineVariant = FurLineLight,
)

private val WallpurrShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WallpurrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = WallpurrShapes,
        content = content,
    )
}
