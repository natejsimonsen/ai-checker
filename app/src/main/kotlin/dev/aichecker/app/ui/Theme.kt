package dev.aichecker.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.aichecker.core.detect.Verdict

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
)

@Composable
fun AiCheckerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Verdict colours stay fixed (not dynamic) so red/green keep their meaning. */
fun verdictColor(verdict: Verdict, dark: Boolean): Color = when (verdict) {
    Verdict.LIKELY_HUMAN -> if (dark) Color(0xFF6DD58C) else Color(0xFF1E8E3E)
    Verdict.POSSIBLY_AI -> if (dark) Color(0xFFFFC857) else Color(0xFFB06000)
    Verdict.LIKELY_AI -> if (dark) Color(0xFFFF8A80) else Color(0xFFC5221F)
    Verdict.INCONCLUSIVE -> if (dark) Color(0xFFB0B3B8) else Color(0xFF5F6368)
}

/** Colour for a raw probability, used by the segment timeline and history chips. */
fun probabilityColor(p: Float, dark: Boolean): Color = verdictColor(
    when {
        p >= 0.85f -> Verdict.LIKELY_AI
        p <= 0.15f -> Verdict.LIKELY_HUMAN
        else -> Verdict.POSSIBLY_AI
    },
    dark,
)

val Verdict.label: String
    get() = when (this) {
        Verdict.LIKELY_HUMAN -> "Likely human"
        Verdict.POSSIBLY_AI -> "Possibly AI / hybrid"
        Verdict.LIKELY_AI -> "Likely AI"
        Verdict.INCONCLUSIVE -> "Inconclusive"
    }

val Verdict.explanation: String
    get() = when (this) {
        Verdict.LIKELY_HUMAN ->
            "No sign of the spectral fingerprint that AI music generators leave behind."
        Verdict.POSSIBLY_AI ->
            "Part of the AI fingerprint is there, but not clearly. Common for tracks that mix AI parts (stems, vocals) with human-made ones, or AI audio that was heavily processed."
        Verdict.LIKELY_AI ->
            "The spectrum shows the regular peak pattern produced by the neural vocoders inside generators like Suno and Udio."
        Verdict.INCONCLUSIVE ->
            "The signal is ambiguous. Heavy mastering, lossy re-encoding or a generator the model hasn't seen can all blur the fingerprint."
    }
