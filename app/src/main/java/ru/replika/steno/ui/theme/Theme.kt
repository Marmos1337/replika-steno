package ru.replika.steno.ui.theme

import android.provider.Settings as SysSettings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.replika.steno.R

/*
 * Every visual token of the app lives here: colors, spacing, radii, type and motion.
 * Screens never use raw hex values or ad-hoc dp gaps.
 */

@Immutable
data class Palette(
    val dark: Boolean,
    /** Page background. */
    val bg: Color,
    /** Cards, fields, rows: one step off the background. */
    val surface: Color,
    /** Navigation panel background (light grey in the light theme). */
    val nav: Color,
    /** Pressed/selected surfaces and chips: two steps off. */
    val raised: Color,
    /** Hairline borders. */
    val border: Color,
    /** Stronger outline (logo pill, focused field). */
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    /** The one inverted fill: filled buttons are a pill of [text] color with [onText] label. */
    val onText: Color,
    /** Used only for error icons and the recording dot. */
    val danger: Color,
    /** Background of a search match inside text. */
    val match: Color,
    /** Background of the active search match. */
    val matchActive: Color,
    /** Background of the utterance currently playing. */
    val playing: Color,
    /** Scrim behind sheets and the modal drawer. */
    val scrim: Color,
    /** Six calm, distinct tones for speakers; picked by the speaker's number, so they are stable. */
    val speakers: List<Color>,
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0B0B0C),
    surface = Color(0xFF151517),
    nav = Color(0xFF111113),
    raised = Color(0xFF1F1F22),
    border = Color(0x1AFFFFFF),
    borderStrong = Color(0x33FFFFFF),
    text = Color(0xFFF2F2F3),
    muted = Color(0xFF8A8A90),
    onText = Color(0xFF0B0B0C),
    danger = Color(0xFFFF6B6B),
    match = Color(0x33F2F2F3),
    matchActive = Color(0x80F2F2F3),
    playing = Color(0xFF1F1F22),
    scrim = Color(0x99000000),
    speakers = listOf(
        Color(0xFFC9CED6), Color(0xFFD9C6AE), Color(0xFFAFCDB8),
        Color(0xFFD0B4CB), Color(0xFFAFC2DE), Color(0xFFD3CC9E),
    ),
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF6F6F7),
    nav = Color(0xFFF3F3F4),
    raised = Color(0xFFECECEE),
    border = Color(0x14000000),
    borderStrong = Color(0x29000000),
    text = Color(0xFF111113),
    muted = Color(0xFF6E6E75),
    onText = Color(0xFFFFFFFF),
    danger = Color(0xFFD12F35),
    match = Color(0x26111113),
    matchActive = Color(0x5C111113),
    playing = Color(0xFFECECEE),
    scrim = Color(0x66000000),
    speakers = listOf(
        Color(0xFF5B6573), Color(0xFF85613A), Color(0xFF3F7352),
        Color(0xFF7A4870), Color(0xFF41618F), Color(0xFF6E6627),
    ),
)

/** Spacing scale 4/8/12/16/24/32. Siblings of the same kind always use the same step. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val r6 = RoundedCornerShape(6.dp)
    val r8 = RoundedCornerShape(8.dp)
    val r10 = RoundedCornerShape(10.dp)
    val r14 = RoundedCornerShape(14.dp)
    val r20 = RoundedCornerShape(20.dp)
    val r26 = RoundedCornerShape(26.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** Fixed sizes that several screens share. */
object Size {
    val touch = 48.dp
    val topBar = 64.dp
    val icon = 22.dp
    val iconSmall = 18.dp
    val logo = 40.dp
    val drawerWide = 280.dp
    val rail = 80.dp
    val contentMax = 760.dp
    val stroke = 1.dp
    val progress = 6.dp
    val dot = 8.dp
    val player = 104.dp
    val bottomBar = 80.dp
    /** Screens wider than this get a permanent drawer instead of a modal one. */
    val wideBreakpoint = 840.dp
}

val Artegra = FontFamily(
    Font(R.font.artegra_extended_500, FontWeight.Medium),
    Font(R.font.artegra_extended_700, FontWeight.Bold),
)

val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
)

/** Four sizes: display 26, heading 17, body 15, label 13. */
object Type {
    val display = TextStyle(fontFamily = Artegra, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp)
    val heading = TextStyle(fontFamily = Artegra, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    /** Tabular figures for timers and timestamps, so digits do not jitter. */
    val labelNum = label.copy(fontFeatureSettings = "tnum")
    val bodyNum = body.copy(fontFeatureSettings = "tnum")
    val displayNum = display.copy(fontFeatureSettings = "tnum")
}

/** 150-250 ms ease-out; collapses to an instant change when the system turned animations off. */
object Motion {
    const val FAST = 150
    const val BASE = 200
    const val SLOW = 250

    fun <T> spec(reduced: Boolean, ms: Int = BASE): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(ms, easing = LinearOutSlowInEasing)

    fun <T> move(reduced: Boolean, ms: Int = SLOW): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(ms, easing = FastOutSlowInEasing)
}

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val LocalReducedMotion = staticCompositionLocalOf { false }

object Theme {
    val colors: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

fun Palette.speakerTone(speakerId: String): Color {
    val n = speakerId.substringAfterLast('_').toIntOrNull() ?: speakerId.hashCode()
    return speakers[Math.floorMod(n - 1, speakers.size)]
}

@Composable
fun ReplikaTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val context = LocalContext.current
    val reduced = remember {
        SysSettings.Global.getFloat(context.contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.text, onPrimary = p.onText, secondary = p.text, onSecondary = p.onText,
            background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
            surfaceVariant = p.surface, onSurfaceVariant = p.muted, surfaceContainerLowest = p.bg,
            surfaceContainerLow = p.surface, surfaceContainer = p.surface, surfaceContainerHigh = p.surface,
            surfaceContainerHighest = p.raised, outline = p.borderStrong, outlineVariant = p.border,
            error = p.danger, inverseSurface = p.text, inverseOnSurface = p.onText, scrim = p.scrim,
            secondaryContainer = p.raised, onSecondaryContainer = p.text,
        )
    } else {
        lightColorScheme(
            primary = p.text, onPrimary = p.onText, secondary = p.text, onSecondary = p.onText,
            background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
            surfaceVariant = p.surface, onSurfaceVariant = p.muted, surfaceContainerLowest = p.bg,
            surfaceContainerLow = p.surface, surfaceContainer = p.surface, surfaceContainerHigh = p.surface,
            surfaceContainerHighest = p.raised, outline = p.borderStrong, outlineVariant = p.border,
            error = p.danger, inverseSurface = p.text, inverseOnSurface = p.onText, scrim = p.scrim,
            secondaryContainer = p.raised, onSecondaryContainer = p.text,
        )
    }
    val typography = MaterialTheme.typography.copy(
        bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.label,
        labelLarge = Type.label, labelMedium = Type.label, labelSmall = Type.label,
        titleLarge = Type.heading, titleMedium = Type.bodyStrong, titleSmall = Type.label,
        headlineSmall = Type.heading,
    )
    CompositionLocalProvider(LocalPalette provides p, LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
