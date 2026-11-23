package dev.katv.tv.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Design tokens copied verbatim from design-reference.html tailwind.config.
 * Obsidian #121211 · surface #1B1B19 · rule #282826 · vermilion #E03C1F
 * paper #F4F2ED · quiet #94928B. Inter (sans) + JetBrains Mono (mono);
 * when the exact families are absent Android falls back to sans/monospace.
 */
object KaTvColors {
    const val Obsidian = 0xFF121211
    const val Surface = 0xFF1B1B19
    const val Rule = 0xFF282826
    const val Vermilion = 0xFFE03C1F
    const val Paper = 0xFFF4F2ED
    const val Quiet = 0xFF94928B
    const val ThumbnailBg = 0xFF30302D
}

val SansFont = FontFamily.SansSerif
val MonoFont = FontFamily.Monospace

/**
 * Sizes mirror the px values in the HTML (sp ≈ px after TV density scaling).
 * letter-spacing values are in em-equivalent sp per the HTML, e.g. 0.12em.
 */
val KaTvTypography = Typography(
    // wordmark: 18px font-semibold tracking-[-0.06em]
    titleLarge = TextStyle(
        fontFamily = SansFont, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, letterSpacing = (-0.06).sp
    ),
    // section title "Live channels": 19px font-semibold tracking-[-0.04em]
    titleMedium = TextStyle(
        fontFamily = SansFont, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, letterSpacing = (-0.04).sp
    ),
    // channel name: 14px font-bold
    bodyLarge = TextStyle(
        fontFamily = SansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp
    ),
    // program-title: 14px font-semibold
    bodyMedium = TextStyle(
        fontFamily = SansFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
    ),
    // muted card program: 12px quiet
    bodySmall = TextStyle(
        fontFamily = SansFont, fontWeight = FontWeight.Normal, fontSize = 12.sp
    ),
    // mono section header: 13px bold uppercase tracking-[0.12em]
    labelLarge = TextStyle(
        fontFamily = MonoFont, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, letterSpacing = 0.12.sp
    ),
    // mono tabs / CH badge / badge: 12px semibold
    labelMedium = TextStyle(
        fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp
    ),
    // mono micro labels: 10px tracking-[0.12em]
    labelSmall = TextStyle(
        fontFamily = MonoFont, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, letterSpacing = 0.12.sp
    )
)
