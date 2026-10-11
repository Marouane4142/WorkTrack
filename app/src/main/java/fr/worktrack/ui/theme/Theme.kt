package fr.worktrack.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import fr.worktrack.domain.Appearance

val Navy = Color(0xFF142B45)
val Mint = Color(0xFF70E3C2)
val Teal = Color(0xFF087F6B)
val Amber = Color(0xFFBB741C)
val MutedBlue = Color(0xFF829CBC)

private val Light = lightColorScheme(primary = Teal, onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F5E9), onPrimaryContainer = Color(0xFF064A3E),
    secondary = Color(0xFF466585), secondaryContainer = Color(0xFFE1EBF6), onSecondaryContainer = Navy,
    tertiary = Amber, tertiaryContainer = Color(0xFFFFECCC), onTertiaryContainer = Color(0xFF654111),
    background = Color(0xFFF5F7FA), onBackground = Navy, surface = Color.White,
    onSurface = Navy, surfaceVariant = Color(0xFFEDF1F5), onSurfaceVariant = Color(0xFF5A6C80),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F4F7),
    outline = Color(0xFF7E8A98), outlineVariant = Color(0xFFDCE4ED))
private val Dark = darkColorScheme(primary = Mint, onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF164F43), onPrimaryContainer = Color(0xFFBCF5E2),
    secondary = Color(0xFFB5CCE6), secondaryContainer = Color(0xFF273F59), onSecondaryContainer = Color(0xFFE0EDFF),
    tertiary = Color(0xFFFFCA83), tertiaryContainer = Color(0xFF4B361E), onTertiaryContainer = Color(0xFFFFDFB1),
    background = Color(0xFF0E1824), onBackground = Color(0xFFE7EFF8), surface = Color(0xFF172638),
    onSurface = Color(0xFFE7EFF8), surfaceVariant = Color(0xFF243549), onSurfaceVariant = Color(0xFFA5B6CA),
    surfaceContainer = Color(0xFF172638), surfaceContainerLow = Color(0xFF122031),
    outline = Color(0xFF8595A7), outlineVariant = Color(0xFF314356))

@Composable
fun WorkTrackTheme(appearance: Appearance = Appearance.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (appearance) { Appearance.DARK -> true; Appearance.LIGHT -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialExpressiveTheme(colorScheme = if (dark) Dark else Light, motionScheme = MotionScheme.expressive(), content = content)
}
